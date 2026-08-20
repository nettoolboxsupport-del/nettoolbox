package de.nettoolbox.feature.tools.ui.ipscan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.database.entity.ToolType
import de.nettoolbox.core.datastore.SettingsRepository
import de.nettoolbox.core.database.repository.ToolRunRepository
import de.nettoolbox.feature.tools.domain.scan.DiscoveredHost
import de.nettoolbox.feature.tools.domain.scan.IpScanEvent
import de.nettoolbox.feature.tools.domain.scan.IpScanOptions
import de.nettoolbox.feature.tools.domain.scan.IpScanner
import de.nettoolbox.feature.tools.domain.scan.LocalNetwork
import de.nettoolbox.feature.tools.domain.scan.LocalNetworkProvider
import de.nettoolbox.feature.tools.domain.subnet.Ipv4Subnet
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IpScanUiState(
    val localNetwork: LocalNetwork? = null,
    val subnetInput: String = "",
    val isRunning: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val hosts: List<DiscoveredHost> = emptyList(),
    val useSsdp: Boolean = true,
    val useMdns: Boolean = true,
    val useNetBios: Boolean = true,
    val error: NetToolboxError? = null,
    val showDisclaimer: Boolean = false,
) {
    val subnet: Ipv4Subnet? get() = Ipv4Subnet.parseOrNull(subnetInput)

    val canStart: Boolean get() = subnet != null && !isRunning

    val progressFraction: Float get() = if (total == 0) 0f else completed.toFloat() / total
}

@HiltViewModel
class IpScanViewModel @Inject constructor(
    private val ipScanner: IpScanner,
    private val localNetworkProvider: LocalNetworkProvider,
    private val toolRunRepository: ToolRunRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(IpScanUiState())
    val uiState: StateFlow<IpScanUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null

    init {
        refreshNetwork()
    }

    /** Re-read on every open: the subnet changes with the connection. */
    fun refreshNetwork() {
        val network = localNetworkProvider.current()
        _uiState.update { current ->
            current.copy(
                localNetwork = network,
                subnetInput = current.subnetInput.ifEmpty {
                    network.subnet?.let { "${it.network}/${it.prefixLength}" }.orEmpty()
                },
            )
        }
    }

    fun onSubnetChange(value: String) = _uiState.update { it.copy(subnetInput = value) }

    fun onSsdpChange(value: Boolean) = _uiState.update { it.copy(useSsdp = value) }

    fun onMdnsChange(value: Boolean) = _uiState.update { it.copy(useMdns = value) }

    fun onNetBiosChange(value: Boolean) = _uiState.update { it.copy(useNetBios = value) }

    fun onStartRequested() {
        viewModelScope.launch {
            val accepted = settingsRepository.settings.first().scannerDisclaimerAccepted
            if (accepted) start() else _uiState.update { it.copy(showDisclaimer = true) }
        }
    }

    fun onDisclaimerDismissed() = _uiState.update { it.copy(showDisclaimer = false) }

    fun onDisclaimerAccepted() {
        _uiState.update { it.copy(showDisclaimer = false) }
        viewModelScope.launch {
            settingsRepository.update { it.copy(scannerDisclaimerAccepted = true) }
            start()
        }
    }

    private fun start() {
        val state = _uiState.value
        val subnet = state.subnet ?: return

        val options = IpScanOptions(
            useSsdp = state.useSsdp,
            useMdns = state.useMdns,
            useNetBios = state.useNetBios,
        )

        _uiState.update {
            it.copy(isRunning = true, hosts = emptyList(), completed = 0, total = 0, error = null)
        }

        runJob = viewModelScope.launch {
            val runId = toolRunRepository.startRun(
                toolType = ToolType.IP_SCAN,
                target = subnet.toString(),
                paramsJson = """{"ssdp":${state.useSsdp},"mdns":${state.useMdns},""" +
                    """"netbios":${state.useNetBios}}""",
            )

            var hostCount = 0
            try {
                ipScanner.scan(subnet, options).collect { event ->
                    when (event) {
                        is IpScanEvent.Progress -> _uiState.update {
                            it.copy(completed = event.completed, total = event.total)
                        }

                        is IpScanEvent.HostFound -> _uiState.update { current ->
                            // The scanner emits a merged host every time something
                            // new is learned about it, so replace by IP rather
                            // than append.
                            val others = current.hosts.filterNot { it.ip == event.host.ip }
                            current.copy(hosts = (others + event.host).sortedBy { it.ip })
                        }

                        is IpScanEvent.Failed -> _uiState.update { it.copy(error = event.error) }

                        is IpScanEvent.Completed -> {
                            hostCount = event.hosts.size
                            _uiState.update { it.copy(hosts = event.hosts) }
                        }
                    }
                }
            } finally {
                toolRunRepository.finishRun(
                    runId = runId,
                    resultJson = """{"hosts":$hostCount}""",
                    success = _uiState.value.error == null,
                )
                _uiState.update { it.copy(isRunning = false) }
            }
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
        appendLine("ip scan ${state.subnetInput}")
        appendLine()
        appendLine("ip;hostname;open_ports;rtt_ms;sources;services")
        state.hosts.forEach { host ->
            appendLine(
                listOf(
                    host.ip,
                    host.hostname.orEmpty(),
                    host.openPorts.joinToString(" "),
                    host.rttMillis?.toString().orEmpty(),
                    host.sources.joinToString(" ") { it.name },
                    host.services.joinToString(" | ").replace(';', ' '),
                ).joinToString(";"),
            )
        }
    }
}
