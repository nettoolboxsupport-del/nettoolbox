package de.nettoolbox.feature.tools.ui.dns

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.database.entity.ToolType
import de.nettoolbox.core.database.repository.ToolRunRepository
import de.nettoolbox.feature.tools.domain.dns.DnsLookupResult
import de.nettoolbox.feature.tools.domain.dns.DnsRecordType
import de.nettoolbox.feature.tools.domain.dns.DnsResolverService
import de.nettoolbox.feature.tools.domain.dns.DnsResolverTarget
import de.nettoolbox.feature.tools.domain.dns.DnsTransportKind
import de.nettoolbox.feature.tools.domain.dns.SystemDnsConfiguration
import de.nettoolbox.feature.tools.domain.dns.SystemDnsProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import de.nettoolbox.feature.tools.navigation.DnsRoute
import de.nettoolbox.feature.tools.navigation.NO_RUN_ID
import javax.inject.Inject

/** Stored with a run so it can be repeated. */
@Serializable
data class DnsQueryParams(
    val name: String,
    val type: DnsRecordType,
    val resolverLabels: List<String>,
)

data class DnsUiState(
    val name: String = "",
    val type: DnsRecordType = DnsRecordType.A,
    val systemConfiguration: SystemDnsConfiguration? = null,
    val availableTargets: List<DnsResolverTarget> = emptyList(),
    val selectedTargets: Set<String> = emptySet(),
    val isRunning: Boolean = false,
    val results: List<DnsLookupResult> = emptyList(),
) {
    val canStart: Boolean get() = name.isNotBlank() && selectedTargets.isNotEmpty() && !isRunning
}

@HiltViewModel
class DnsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val resolverService: DnsResolverService,
    private val systemDnsProvider: SystemDnsProvider,
    private val toolRunRepository: ToolRunRepository,
    private val json: Json,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DnsUiState())
    val uiState: StateFlow<DnsUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null

    init {
        refreshTargets()

        val runId = runCatching { savedStateHandle.toRoute<DnsRoute>().runId }
            .getOrDefault(NO_RUN_ID)
        if (runId != NO_RUN_ID) {
            viewModelScope.launch { prefillFrom(runId) }
        }
    }

    /** Restores name, type and resolver selection without running the lookup. */
    private suspend fun prefillFrom(runId: Long) {
        val run = toolRunRepository.find(runId) ?: return
        val params = runCatching {
            json.decodeFromString(DnsQueryParams.serializer(), run.paramsJson)
        }.getOrNull() ?: return

        _uiState.update { current ->
            // Only resolvers that still exist: the system ones change with the
            // network, and selecting a label that is gone would silently drop it.
            val available = current.availableTargets.map { it.label }.toSet()
            current.copy(
                name = params.name,
                type = params.type,
                selectedTargets = params.resolverLabels.filter { it in available }.toSet()
                    .ifEmpty { current.selectedTargets },
            )
        }
    }

    /**
     * The system resolvers change with the network, so they are re-read rather
     * than cached - a lookup against the Wi-Fi's resolver while on mobile data
     * would answer a question nobody asked.
     */
    fun refreshTargets() {
        val configuration = systemDnsProvider.current()
        val systemTargets = systemDnsProvider.asTargets()
        val targets = systemTargets + PUBLIC_TARGETS

        _uiState.update { current ->
            current.copy(
                systemConfiguration = configuration,
                availableTargets = targets,
                selectedTargets = current.selectedTargets.ifEmpty { defaultSelection(systemTargets) },
            )
        }
    }

    /**
     * Preselects one system resolver plus one public one.
     *
     * Comparing is the point of the tool, so a single resolver is never a useful
     * default. The system resolver is picked by IPv4 first: devices commonly
     * advertise an IPv6 resolver that does not actually answer - the emulator's
     * `fec0::3` being the obvious example - and starting with a guaranteed
     * timeout makes the tool look broken.
     */
    private fun defaultSelection(systemTargets: List<DnsResolverTarget>): Set<String> {
        val system = systemTargets.firstOrNull { !it.address.contains(':') }
            ?: systemTargets.firstOrNull()
        return setOfNotNull(system?.label, PUBLIC_TARGETS.first().label)
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value) }

    fun onTypeChange(type: DnsRecordType) = _uiState.update { it.copy(type = type) }

    fun onToggleTarget(target: DnsResolverTarget) = _uiState.update { current ->
        val selected = current.selectedTargets.toMutableSet()
        if (!selected.remove(target.label)) selected += target.label
        current.copy(selectedTargets = selected)
    }

    fun start() {
        if (!_uiState.value.canStart) return

        val state = _uiState.value
        val targets = state.availableTargets.filter { it.label in state.selectedTargets }

        _uiState.update { it.copy(isRunning = true, results = emptyList()) }

        runJob = viewModelScope.launch {
            val runId = toolRunRepository.startRun(
                toolType = ToolType.DNS,
                target = "${state.name} ${state.type.name}",
                paramsJson = json.encodeToString(
                    DnsQueryParams.serializer(),
                    DnsQueryParams(state.name, state.type, targets.map { it.label }),
                ),
            )

            val results = resolverService.lookup(state.name, state.type, targets)
            val anyAnswer = results.any { it.response != null }

            _uiState.update { it.copy(isRunning = false, results = results) }
            toolRunRepository.finishRun(runId, resultJson = null, success = anyAnswer)
        }
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
        _uiState.update { it.copy(isRunning = false) }
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    fun asPlainText(): String = buildString {
        val state = _uiState.value
        appendLine("${state.name} ${state.type.name}")
        state.results.forEach { result ->
            appendLine()
            appendLine(";; ${result.target.label} (${result.target.kind}) ${result.elapsedMillis} ms")
            val response = result.response
            when {
                result.error != null -> appendLine(";; error: ${result.error.reason}")
                response == null -> appendLine(";; no answer")
                else -> {
                    appendLine(";; status: ${response.responseCode}, flags:" + buildString {
                        if (response.authoritative) append(" aa")
                        if (response.truncated) append(" tc")
                        if (response.recursionAvailable) append(" ra")
                        if (response.authenticatedData) append(" ad")
                    })
                    response.answers.forEach { record ->
                        appendLine("${record.name}\t${record.ttlSeconds}\t${record.type?.name ?: record.typeCode}\t${record.data}")
                    }
                }
            }
        }
    }

    private companion object {
        /**
         * Well-known open resolvers. Not exhaustive on purpose - these are the
         * two a technician reaches for when checking whether a problem is local,
         * plus their encrypted variants.
         */
        val PUBLIC_TARGETS = listOf(
            DnsResolverTarget("1.1.1.1", DnsTransportKind.UDP, "1.1.1.1"),
            DnsResolverTarget("8.8.8.8", DnsTransportKind.UDP, "8.8.8.8"),
            DnsResolverTarget("Cloudflare DoT", DnsTransportKind.DOT, "one.one.one.one"),
            DnsResolverTarget("Cloudflare DoH", DnsTransportKind.DOH, "https://cloudflare-dns.com/dns-query"),
        )
    }
}
