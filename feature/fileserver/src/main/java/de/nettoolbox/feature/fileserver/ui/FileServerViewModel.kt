package de.nettoolbox.feature.fileserver.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.permissions.PermissionCoordinator
import de.nettoolbox.feature.fileserver.domain.ChecksumAlgorithm
import de.nettoolbox.feature.fileserver.domain.CopyProgress
import de.nettoolbox.feature.fileserver.domain.FileEntry
import de.nettoolbox.feature.fileserver.domain.FileOpError
import de.nettoolbox.feature.fileserver.domain.FileOpResult
import de.nettoolbox.feature.fileserver.domain.FileOperations
import de.nettoolbox.feature.fileserver.domain.FileServerConfig
import de.nettoolbox.feature.fileserver.domain.FileServerConfigRepository
import de.nettoolbox.feature.fileserver.domain.NetworkAddresses
import de.nettoolbox.feature.fileserver.domain.ServerAccount
import de.nettoolbox.core.common.storage.ShareStorage
import de.nettoolbox.feature.fileserver.domain.SortOrder
import de.nettoolbox.feature.fileserver.domain.TextPreview
import de.nettoolbox.feature.fileserver.domain.TransferLog
import de.nettoolbox.feature.fileserver.service.FileServerController
import de.nettoolbox.feature.fileserver.ssh.HostKeyStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.inject.Inject

/** Where the explorer currently is, and what is selected in it. */
data class ExplorerState(
    val path: String = "/",
    val entries: List<FileEntry> = emptyList(),
    val selection: Set<String> = emptySet(),
    val sortOrder: SortOrder = SortOrder.NAME,
    val ascending: Boolean = true,
    val showHidden: Boolean = false,
    val isLoading: Boolean = true,
    val freeSpaceBytes: Long = 0,
    /** Paths cut or copied but not yet pasted, and which of the two it was. */
    val clipboard: List<String> = emptyList(),
    val clipboardIsMove: Boolean = false,
    val copyProgress: CopyProgress? = null,
) {
    val isRoot: Boolean get() = path == "/"

    val selectionCount: Int get() = selection.size

    /** The path segments, for the breadcrumb. Empty at the root. */
    val segments: List<String>
        get() = path.trim('/').split('/').filter { it.isNotEmpty() }
}

/** A transient message the screen shows once and then forgets. */
data class UiMessage(val id: Long, val error: FileOpError?, val successKey: String? = null)

@HiltViewModel
class FileServerViewModel @Inject constructor(
    private val operations: FileOperations,
    private val configRepository: FileServerConfigRepository,
    private val controller: FileServerController,
    private val addresses: NetworkAddresses,
    private val hostKeys: HostKeyStore,
    val storage: ShareStorage,
    val transferLog: TransferLog,
    /** Exposed so the screen can gate the service on notification permission. */
    val permissionCoordinator: PermissionCoordinator,
) : ViewModel() {

    private val _explorer = MutableStateFlow(ExplorerState())
    val explorer: StateFlow<ExplorerState> = _explorer.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _preview = MutableStateFlow<PreviewState?>(null)
    val preview: StateFlow<PreviewState?> = _preview.asStateFlow()

    private val _checksum = MutableStateFlow<ChecksumState?>(null)
    val checksum: StateFlow<ChecksumState?> = _checksum.asStateFlow()

    private val _selectedTab = MutableStateFlow(TAB_EXPLORER)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    val config: StateFlow<FileServerConfig> = configRepository.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), FileServerConfig())

    /** Owned by the service, so it survives rotation and this ViewModel's death. */
    val serverState = controller.state

    private var transferJob: Job? = null

    init {
        refresh()
    }

    // --- navigation ---------------------------------------------------------

    fun selectTab(index: Int) = _selectedTab.update { index }

    fun open(entry: FileEntry) {
        if (entry.isDirectory) {
            navigateTo(entry.relativePath)
        } else {
            showPreview(entry)
        }
    }

    fun navigateTo(path: String) {
        // The selection belongs to the directory it was made in. Carrying it
        // across would let a delete act on paths the user can no longer see.
        _explorer.update { it.copy(path = path, selection = emptySet(), isLoading = true) }
        refresh()
    }

    fun navigateUp() {
        val current = _explorer.value.path
        if (current == "/") return
        navigateTo("/" + current.trim('/').substringBeforeLast('/', ""))
    }

    /** Jumps to a breadcrumb segment, counted from the root. */
    fun navigateToSegment(index: Int) {
        val segments = _explorer.value.segments
        navigateTo("/" + segments.take(index + 1).joinToString("/"))
    }

    fun refresh() {
        viewModelScope.launch {
            _explorer.update { it.copy(isLoading = true) }
            val state = _explorer.value
            when (
                val result = operations.list(
                    relativePath = state.path,
                    sortOrder = state.sortOrder,
                    ascending = state.ascending,
                    showHidden = state.showHidden,
                )
            ) {
                is FileOpResult.Ok -> _explorer.update {
                    it.copy(
                        entries = result.value,
                        isLoading = false,
                        freeSpaceBytes = operations.freeSpaceBytes(),
                        // Anything selected that no longer exists is dropped,
                        // rather than left behind to be acted on later.
                        selection = it.selection.intersect(
                            result.value.map { entry -> entry.relativePath }.toSet(),
                        ),
                    )
                }

                is FileOpResult.Failed -> {
                    _explorer.update { it.copy(isLoading = false) }
                    report(result.error)
                }
            }
        }
    }

    // --- selection ----------------------------------------------------------

    fun toggleSelection(entry: FileEntry) = _explorer.update { state ->
        state.copy(
            selection = if (entry.relativePath in state.selection) {
                state.selection - entry.relativePath
            } else {
                state.selection + entry.relativePath
            },
        )
    }

    fun clearSelection() = _explorer.update { it.copy(selection = emptySet()) }

    fun selectAll() = _explorer.update { state ->
        state.copy(selection = state.entries.map { it.relativePath }.toSet())
    }

    fun setSortOrder(order: SortOrder) {
        _explorer.update {
            // Tapping the active column reverses it, which is what every file
            // manager does and what the user will try first.
            if (it.sortOrder == order) {
                it.copy(ascending = !it.ascending)
            } else {
                it.copy(sortOrder = order, ascending = true)
            }
        }
        refresh()
    }

    fun setShowHidden(show: Boolean) {
        _explorer.update { it.copy(showHidden = show) }
        refresh()
    }

    // --- file operations ----------------------------------------------------

    fun createDirectory(name: String) = runOperation {
        operations.createDirectory(_explorer.value.path, name)
    }

    fun rename(entry: FileEntry, newName: String) = runOperation {
        operations.rename(entry.relativePath, newName)
    }

    fun deleteSelection() = runOperation {
        operations.delete(_explorer.value.selection.toList())
    }

    fun cut() = _explorer.update {
        it.copy(clipboard = it.selection.toList(), clipboardIsMove = true, selection = emptySet())
    }

    fun copy() = _explorer.update {
        it.copy(clipboard = it.selection.toList(), clipboardIsMove = false, selection = emptySet())
    }

    fun clearClipboard() = _explorer.update { it.copy(clipboard = emptyList()) }

    fun paste() {
        val state = _explorer.value
        if (state.clipboard.isEmpty()) return
        transferJob?.cancel()
        transferJob = viewModelScope.launch {
            val result = operations.transfer(
                sourcePaths = state.clipboard,
                targetDirectory = state.path,
                move = state.clipboardIsMove,
                onProgress = { progress -> _explorer.update { it.copy(copyProgress = progress) } },
            )
            _explorer.update { it.copy(copyProgress = null, clipboard = emptyList()) }
            if (result is FileOpResult.Failed) report(result.error)
            refresh()
        }
    }

    /** Cancels a paste in flight. Partial copies are left as they are and shown. */
    fun cancelTransfer() {
        transferJob?.cancel()
        transferJob = null
        _explorer.update { it.copy(copyProgress = null) }
        refresh()
    }

    fun importFrom(uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris) {
                val result = operations.importFrom(uri, _explorer.value.path)
                if (result is FileOpResult.Failed) {
                    report(result.error)
                    break
                }
            }
            refresh()
        }
    }

    fun exportTo(relativePath: String, uri: Uri) = runOperation {
        operations.exportTo(relativePath, uri)
    }

    // --- preview and checksum ----------------------------------------------

    private fun showPreview(entry: FileEntry) {
        viewModelScope.launch {
            _preview.update { PreviewState(entry, null, isLoading = true) }
            when (val result = operations.previewText(entry.relativePath)) {
                is FileOpResult.Ok ->
                    _preview.update { PreviewState(entry, result.value, isLoading = false) }

                is FileOpResult.Failed -> {
                    _preview.update { null }
                    report(result.error)
                }
            }
        }
    }

    fun dismissPreview() = _preview.update { null }

    fun computeChecksum(entry: FileEntry, algorithm: ChecksumAlgorithm) {
        viewModelScope.launch {
            _checksum.update { ChecksumState(entry, algorithm, null, 0f) }
            when (
                val result = operations.checksum(entry.relativePath, algorithm) { fraction ->
                    _checksum.update { it?.copy(progress = fraction) }
                }
            ) {
                is FileOpResult.Ok ->
                    _checksum.update { it?.copy(value = result.value, progress = 1f) }

                is FileOpResult.Failed -> {
                    _checksum.update { null }
                    report(result.error)
                }
            }
        }
    }

    fun dismissChecksum() = _checksum.update { null }

    // --- server -------------------------------------------------------------

    fun startServer() = controller.start()

    fun stopServer() = controller.stop()

    fun updateConfig(transform: (FileServerConfig) -> FileServerConfig) {
        viewModelScope.launch { configRepository.update(transform) }
    }

    fun addAccount(username: String, password: String, home: String, canWrite: Boolean) {
        viewModelScope.launch { configRepository.addAccount(username, password, home, canWrite) }
    }

    fun updateAccount(account: ServerAccount) {
        viewModelScope.launch { configRepository.replaceAccount(account) }
    }

    fun removeAccount(id: String) {
        viewModelScope.launch { configRepository.removeAccount(id) }
    }

    fun addAuthorizedKey(line: String) {
        viewModelScope.launch { configRepository.addAuthorizedKey(line) }
    }

    fun removeAuthorizedKey(line: String) {
        viewModelScope.launch { configRepository.removeAuthorizedKey(line) }
    }

    fun hostKeyFingerprint(): String = hostKeys.fingerprint()

    fun hostKeyLine(): String = hostKeys.publicKeyLine()

    fun regenerateHostKey() {
        viewModelScope.launch { hostKeys.regenerate() }
    }

    fun clearLog() = transferLog.clear()

    /** True when the current bind setting could not be satisfied right now. */
    fun wifiAvailable(): Boolean = addresses.wifiAvailable()

    /**
     * A password worth offering as a default.
     *
     * Generated from [SecureRandom] with an alphabet that leaves out the
     * characters people misread when copying from a phone screen to a console -
     * O and 0, l and 1, I. A password that gets mistyped once is a password the
     * user replaces with "test".
     */
    fun suggestPassword(): String {
        val random = SecureRandom()
        return (1..GENERATED_PASSWORD_LENGTH)
            .map { PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)] }
            .joinToString("")
    }

    // --- plumbing -----------------------------------------------------------

    private fun runOperation(block: suspend () -> FileOpResult<*>) {
        viewModelScope.launch {
            val result = block()
            if (result is FileOpResult.Failed) report(result.error)
            refresh()
        }
    }

    private fun report(error: FileOpError) {
        _message.update { UiMessage(System.nanoTime(), error) }
    }

    fun dismissMessage() = _message.update { null }

    companion object {
        const val TAB_EXPLORER = 0
        const val TAB_SERVERS = 1
        const val TAB_ACCOUNTS = 2
        const val TAB_LOG = 3

        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val GENERATED_PASSWORD_LENGTH = 12
        private const val PASSWORD_ALPHABET =
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}

data class PreviewState(
    val entry: FileEntry,
    val preview: TextPreview?,
    val isLoading: Boolean,
)

data class ChecksumState(
    val entry: FileEntry,
    val algorithm: ChecksumAlgorithm,
    val value: String?,
    val progress: Float,
)
