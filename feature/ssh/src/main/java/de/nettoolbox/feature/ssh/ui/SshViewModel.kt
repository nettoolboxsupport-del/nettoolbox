package de.nettoolbox.feature.ssh.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.feature.ssh.data.HostKeyVerifier
import de.nettoolbox.feature.ssh.data.KnownHost
import de.nettoolbox.feature.ssh.data.KnownHostsStore
import de.nettoolbox.feature.ssh.data.SshAuthType
import de.nettoolbox.feature.ssh.data.SshConnector
import de.nettoolbox.feature.ssh.data.SshProfile
import de.nettoolbox.feature.ssh.data.SshProfileStore
import de.nettoolbox.feature.ssh.data.SshShellSession
import de.nettoolbox.feature.ssh.domain.HostKeyVerdict
import de.nettoolbox.feature.ssh.domain.SshAuthMethod
import de.nettoolbox.feature.ssh.domain.SshConnectionState
import de.nettoolbox.feature.ssh.domain.SshTarget
import de.nettoolbox.feature.ssh.domain.TerminalSize
import de.nettoolbox.vterm.VtermBridge
import de.nettoolbox.vterm.VtermModifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class SshViewModel @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val connector: SshConnector,
    private val verifier: HostKeyVerifier,
    private val knownHostsStore: KnownHostsStore,
    private val profileStore: SshProfileStore,
) : ViewModel() {

    private val _connectionState = MutableStateFlow<SshConnectionState>(SshConnectionState.Idle)
    val connectionState: StateFlow<SshConnectionState> = _connectionState.asStateFlow()

    // Form inputs
    val host = MutableStateFlow("")
    val port = MutableStateFlow("22")
    val username = MutableStateFlow("")
    val authType = MutableStateFlow(SshAuthType.PASSWORD)
    val password = MutableStateFlow("")
    val privateKeyPem = MutableStateFlow("")
    val passphrase = MutableStateFlow("")
    val saveProfile = MutableStateFlow(false)
    val profileName = MutableStateFlow("")

    // Tab state (0: Connect, 1: Terminal, 2: Known Hosts & Profiles)
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    // Modifier states
    private val _ctrlActive = MutableStateFlow(false)
    val ctrlActive: StateFlow<Boolean> = _ctrlActive.asStateFlow()

    private val _altActive = MutableStateFlow(false)
    val altActive: StateFlow<Boolean> = _altActive.asStateFlow()

    // Redraw trigger counter for Canvas
    private val _redrawTrigger = MutableStateFlow(0L)
    val redrawTrigger: StateFlow<Long> = _redrawTrigger.asStateFlow()

    // DataStore streams
    val profiles: StateFlow<List<SshProfile>> = profileStore.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val knownHosts: StateFlow<List<KnownHost>> = knownHostsStore.knownHosts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Native handle and active session
    private var vtermPtr: Long? = null
    val terminalPtr: Long? get() = vtermPtr

    private var activeSession: SshShellSession? = null
    private var ioJob: Job? = null

    private var currentRows = 24
    private var currentCols = 80

    init {
        // Create initial terminal instance
        ensureVtermInitialized(currentRows, currentCols)
    }

    private fun ensureVtermInitialized(rows: Int, cols: Int) {
        if (vtermPtr == null || vtermPtr == 0L) {
            vtermPtr = VtermBridge.create(rows, cols)
        } else {
            VtermBridge.setSize(vtermPtr!!, rows, cols)
        }
        currentRows = rows
        currentCols = cols
    }

    fun selectTab(tabIndex: Int) {
        _selectedTab.value = tabIndex
    }

    fun toggleCtrl() {
        _ctrlActive.value = !_ctrlActive.value
    }

    fun toggleAlt() {
        _altActive.value = !_altActive.value
    }

    fun loadProfile(profile: SshProfile) {
        host.value = profile.host
        port.value = profile.port.toString()
        username.value = profile.username
        authType.value = profile.authType
        profile.privateKeyPem?.let { privateKeyPem.value = it }
        profileName.value = profile.name
        _selectedTab.value = 0
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            profileStore.deleteProfile(profileId)
        }
    }

    fun forgetHostKey(hostId: String, keyType: String) {
        viewModelScope.launch {
            knownHostsStore.forget(hostId, keyType)
        }
    }

    /**
     * Called from Compose layout, so this runs on the MAIN THREAD.
     *
     * Resizing the local terminal is pure memory work and stays here. Telling
     * the server is a network operation and must not: it used to be done
     * inline, where Android's NetworkOnMainThreadException aborted jsch in the
     * middle of writing a packet and corrupted the SSH stream. See the comment
     * on SshShellSession.resize.
     */
    fun onResize(rows: Int, cols: Int) {
        if (rows <= 0 || cols <= 0) return
        if (rows == currentRows && cols == currentCols) return
        currentRows = rows
        currentCols = cols
        vtermPtr?.let { ptr ->
            VtermBridge.setSize(ptr, rows, cols)
        }
        _redrawTrigger.value++

        val session = activeSession ?: return
        viewModelScope.launch(ioDispatcher) {
            val ok = session.resize(TerminalSize(rows, cols))
            Log.d("NetToolboxSSH", "onResize: told server ${cols}x$rows, ok=$ok")
        }
    }

    fun connect() {
        val targetHost = host.value.trim()
        if (targetHost.isBlank()) {
            _connectionState.value = SshConnectionState.Failed(
                NetToolboxError(ErrorReason.INVALID_INPUT, "Host cannot be empty"),
            )
            return
        }

        val targetPort = port.value.trim().toIntOrNull() ?: 22
        val targetUser = username.value.trim()
        if (targetUser.isBlank()) {
            _connectionState.value = SshConnectionState.Failed(
                NetToolboxError(ErrorReason.INVALID_INPUT, "Username cannot be empty"),
            )
            return
        }

        val target = SshTarget(
            host = targetHost,
            port = targetPort,
            username = targetUser,
        )

        val auth: SshAuthMethod = when (authType.value) {
            SshAuthType.PASSWORD -> SshAuthMethod.Password(password.value.toCharArray())
            SshAuthType.PRIVATE_KEY -> SshAuthMethod.PrivateKey(
                privateKeyPem = privateKeyPem.value.toCharArray(),
                passphrase = passphrase.value.ifBlank { null }?.toCharArray(),
            )
        }

        // Save profile if requested
        if (saveProfile.value) {
            val name = profileName.value.ifBlank { "${target.username}@${target.host}" }
            viewModelScope.launch {
                profileStore.saveProfile(
                    SshProfile(
                        name = name,
                        host = target.host,
                        port = target.port,
                        username = target.username,
                        authType = authType.value,
                        privateKeyPem = if (authType.value == SshAuthType.PRIVATE_KEY) privateKeyPem.value else null,
                    ),
                )
            }
        }

        executeConnect(target, auth)
    }

    private fun executeConnect(target: SshTarget, auth: SshAuthMethod) {
        disconnectSession()
        ensureVtermInitialized(currentRows, currentCols)

        _connectionState.value = SshConnectionState.Connecting

        viewModelScope.launch {
            Log.d("NetToolboxSSH", "Connecting to ${target.username}@${target.host}:${target.port} (size=${currentRows}x${currentCols})...")
            val attempt = connector.connect(
                target = target,
                auth = auth,
                size = TerminalSize(currentRows, currentCols),
            )

            when (attempt) {
                is SshConnector.Attempt.Success -> {
                    Log.d("NetToolboxSSH", "SSH connection successful! Starting I/O loop...")
                    activeSession = attempt.session
                    _connectionState.value = SshConnectionState.Connected(target)
                    _selectedTab.value = 1 // Switch to terminal tab

                    startIoLoop(attempt.session)
                }

                is SshConnector.Attempt.HostKeyRejected -> {
                    Log.d("NetToolboxSSH", "SSH Host key rejected/awaiting decision: ${attempt.verdict}")
                    _connectionState.value = SshConnectionState.AwaitingHostKeyDecision(attempt.verdict)
                }

                is SshConnector.Attempt.Failure -> {
                    Log.e("NetToolboxSSH", "SSH Connection failed: ${attempt.error}")
                    _connectionState.value = SshConnectionState.Failed(attempt.error)
                }
            }
        }
    }

    fun onAcceptHostKey(verdict: HostKeyVerdict) {
        viewModelScope.launch {
            verifier.trust(verdict, System.currentTimeMillis())
            // Reconnect immediately
            connect()
        }
    }

    fun onRejectHostKey() {
        _connectionState.value = SshConnectionState.Failed(
            NetToolboxError(ErrorReason.HOST_KEY_REJECTED),
        )
    }

    private fun startIoLoop(session: SshShellSession) {
        ioJob?.cancel()
        ioJob = viewModelScope.launch(ioDispatcher) {
            val buffer = ByteArray(4096)
            val ptr = vtermPtr ?: run {
                Log.e("NetToolboxSSH", "startIoLoop aborted: vtermPtr is null!")
                return@launch
            }
            Log.d("NetToolboxSSH", "startIoLoop started with vtermPtr=$ptr")

            try {
                while (isActive && session.isConnected) {
                    val bytesRead = session.input.read(buffer)
                    if (bytesRead < 0) {
                        Log.d("NetToolboxSSH", "session.input reached EOF (bytesRead < 0)")
                        break
                    }
                    if (bytesRead > 0) {
                        Log.d("NetToolboxSSH", "session.input read $bytesRead bytes: ${String(buffer, 0, minOf(bytesRead, 80))}")
                        VtermBridge.write(ptr, buffer, bytesRead)
                        _redrawTrigger.value++
                    }
                }
            } catch (e: IOException) {
                Log.e("NetToolboxSSH", "startIoLoop IOException: ${e.message}", e)
            } finally {
                // Why the channel ended, not just that it did. Without this the
                // only evidence is "read returned -1", which cannot tell a
                // shell that exited from a connection that was severed.
                Log.d("NetToolboxSSH", "startIoLoop exited: ${session.closureDiagnostics()}")
                withContext(kotlinx.coroutines.NonCancellable) {
                    // Drop the session handle. Keeping it meant every later
                    // keystroke was written into a closed channel and logged as
                    // a success - the app reporting work it had not done.
                    if (activeSession === session) {
                        activeSession = null
                    }
                    if (_connectionState.value is SshConnectionState.Connected) {
                        _connectionState.value = SshConnectionState.Disconnected
                    }
                }
            }
        }
    }

    fun sendChar(char: Char) {
        val ptr = vtermPtr ?: return
        val session = activeSession ?: return

        var modifier = VtermModifier.NONE
        if (_ctrlActive.value) modifier = modifier or VtermModifier.CTRL
        if (_altActive.value) modifier = modifier or VtermModifier.ALT

        Log.d("NetToolboxSSH", "sendChar: '$char' (code=${char.code}, mod=$modifier)")
        VtermBridge.keyUnichar(ptr, char.code, modifier)
        flushOutput(session, ptr)

        // Reset single-use modifiers
        if (_ctrlActive.value) _ctrlActive.value = false
        if (_altActive.value) _altActive.value = false
        _redrawTrigger.value++
    }

    fun sendKey(key: Int) {
        val ptr = vtermPtr ?: return
        val session = activeSession ?: return

        var modifier = VtermModifier.NONE
        if (_ctrlActive.value) modifier = modifier or VtermModifier.CTRL
        if (_altActive.value) modifier = modifier or VtermModifier.ALT

        Log.d("NetToolboxSSH", "sendKey: $key (mod=$modifier)")
        VtermBridge.keyKey(ptr, key, modifier)
        flushOutput(session, ptr)

        if (_ctrlActive.value) _ctrlActive.value = false
        if (_altActive.value) _altActive.value = false
        _redrawTrigger.value++
    }

    /**
     * Drains libvterm's keyboard output and sends it to the remote shell.
     *
     * The write is serialised inside [SshShellSession.write]; it used to be a
     * bare write from whichever Dispatchers.IO thread happened to run the
     * coroutine, which can interleave two writes inside jsch's single output
     * buffer and put a malformed packet on the wire.
     */
    private fun flushOutput(session: SshShellSession, ptr: Long) {
        val outBuf = ByteArray(512)
        val count = VtermBridge.readOutput(ptr, outBuf)
        if (count <= 0) return

        val bytesToSend = outBuf.copyOf(count)
        Log.d(
            "NetToolboxSSH",
            "flushOutput: sending $count bytes: ${bytesToSend.joinToString(" ") { "%02X".format(it) }}",
        )
        viewModelScope.launch(ioDispatcher) {
            if (!session.write(bytesToSend)) {
                Log.w("NetToolboxSSH", "flushOutput: channel gone, ${'$'}count bytes dropped")
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (activeSession === session) activeSession = null
                    if (_connectionState.value is SshConnectionState.Connected) {
                        _connectionState.value = SshConnectionState.Disconnected
                    }
                }
            }
        }
    }

    /**
     * Clears the visible screen.
     *
     * Done by feeding libvterm the ordinary erase sequences rather than by
     * freeing the terminal and making a new one. Freeing it was a real hazard:
     * the renderer holds the pointer as a plain value, not as Compose state, so
     * a frame drawn between the free and the next recomposition would read
     * released memory. Erasing sidesteps the lifetime question entirely, and it
     * is what a terminal does anyway.
     *
     * ESC[2J clears the screen, ESC[3J the scrollback, ESC[H homes the cursor.
     */
    fun clearScreen() {
        val ptr = vtermPtr ?: return
        VtermBridge.write(ptr, CLEAR_SEQUENCE)
        _redrawTrigger.value++
    }

    fun disconnect() {
        disconnectSession()
        _connectionState.value = SshConnectionState.Disconnected
    }

    /**
     * Tears the session down off the main thread.
     *
     * close() sends SSH_MSG_CHANNEL_CLOSE and SSH_MSG_DISCONNECT, so it is
     * network work like any other. Calling it inline from a button handler put
     * it on the main thread, where it hit the same
     * NetworkOnMainThreadException trap that corrupted the packet stream in
     * resize().
     */
    private fun disconnectSession() {
        ioJob?.cancel()
        ioJob = null
        val session = activeSession ?: return
        activeSession = null
        CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            session.close()
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnectSession()
        vtermPtr?.let { ptr ->
            VtermBridge.free(ptr)
            vtermPtr = null
        }
    }

    private companion object {
        /* ESC [ 2 J  erase screen, ESC [ 3 J  erase scrollback, ESC [ H  home.
         * Written as explicit bytes rather than as a string literal with
         * unicode escapes: such an escape is one editing mistake away from
         * becoming a raw control character in the source file, which is
         * exactly what happened here once. Bytes cannot be mangled. */
        val CLEAR_SEQUENCE: ByteArray = byteArrayOf(
            0x1B, 0x5B, 0x32, 0x4A,
            0x1B, 0x5B, 0x33, 0x4A,
            0x1B, 0x5B, 0x48,
        )
    }
}
