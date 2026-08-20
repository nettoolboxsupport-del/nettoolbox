package de.nettoolbox.feature.iperf.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.database.entity.ToolType
import de.nettoolbox.core.database.repository.ToolRunRepository
import de.nettoolbox.feature.iperf.domain.Iperf3ClientRequest
import de.nettoolbox.feature.iperf.domain.Iperf3ClientRunner
import de.nettoolbox.feature.iperf.domain.Iperf3RunResult
import de.nettoolbox.feature.iperf.domain.Iperf3Summary
import de.nettoolbox.feature.iperf.service.Iperf3ServerController
import de.nettoolbox.feature.iperf.service.Iperf3ServerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow as KStateFlow
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

enum class Iperf3Mode { CLIENT, SERVER }

data class Iperf3UiState(
    val mode: Iperf3Mode = Iperf3Mode.CLIENT,
    val serverPort: String = "5201",
    val host: String = "",
    val port: String = "5201",
    val useUdp: Boolean = false,
    val reverse: Boolean = false,
    val durationSeconds: Int = 10,
    val isRunning: Boolean = false,
    val summary: Iperf3Summary? = null,
    val rawJson: String? = null,
    val error: NetToolboxError? = null,
) {
    val canStart: Boolean
        get() = host.isNotBlank() && port.toIntOrNull() in 1..65535 && !isRunning
}

/**
 * Test durations offered in the UI, capped short on purpose: a run cannot be
 * cancelled once started (see [Iperf3ClientRunner]'s doc comment for why),
 * so the longest option here is also the longest the user could ever be
 * stuck waiting for.
 */
val iperf3DurationOptions = listOf(5, 10, 20)

@HiltViewModel
class Iperf3ViewModel @Inject constructor(
    private val runner: Iperf3ClientRunner,
    private val toolRunRepository: ToolRunRepository,
    private val serverController: Iperf3ServerController,
    private val json: Json,
    /** Exposed so the screen can gate the server on notification permission. */
    val permissionCoordinator: de.nettoolbox.core.permissions.PermissionCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(Iperf3UiState())
    val uiState: StateFlow<Iperf3UiState> = _uiState.asStateFlow()

    /** Owned by the service, so it survives rotation and this ViewModel's death. */
    val serverState: KStateFlow<Iperf3ServerState> = serverController.state

    private var runJob: Job? = null

    fun onModeChange(mode: Iperf3Mode) = _uiState.update { it.copy(mode = mode) }

    fun onServerPortChange(value: String) = _uiState.update {
        it.copy(serverPort = value.filter { c -> c.isDigit() }.take(5))
    }

    fun startServer() {
        val port = _uiState.value.serverPort.toIntOrNull() ?: return
        if (port < 1024 || port > 65535) return
        serverController.start(port)
    }

    fun stopServer() = serverController.stop()

    /** Results now outlive the server, so the user needs a way to drop them. */
    fun clearServerResults() = serverController.clearResults()

    /**
     * The device's own addresses, so the user knows where to point a client.
     * Loopback and IPv6 link-local are filtered out - neither is reachable
     * from another machine.
     */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .filterNot { it.contains(':') }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    fun onHostChange(value: String) = _uiState.update { it.copy(host = value, error = null) }

    fun onPortChange(value: String) = _uiState.update {
        it.copy(port = value.filter { c -> c.isDigit() }.take(5))
    }

    fun onUseUdpChange(value: Boolean) = _uiState.update { it.copy(useUdp = value) }

    fun onReverseChange(value: Boolean) = _uiState.update { it.copy(reverse = value) }

    fun onDurationChange(seconds: Int) = _uiState.update { it.copy(durationSeconds = seconds) }

    fun start() {
        val state = _uiState.value
        if (!state.canStart) return

        val request = Iperf3ClientRequest(
            host = state.host.trim(),
            port = state.port.toInt(),
            useUdp = state.useUdp,
            durationSeconds = state.durationSeconds,
            reverse = state.reverse,
        )

        _uiState.update { it.copy(isRunning = true, summary = null, rawJson = null, error = null) }

        // Not cancellable from here - see the Iperf3ClientRunner doc comment.
        runJob = viewModelScope.launch {
            val runId = toolRunRepository.startRun(
                toolType = ToolType.IPERF3,
                target = "${request.host}:${request.port}",
                paramsJson = json.encodeToString(Iperf3ClientRequest.serializer(), request),
            )

            when (val result = runner.run(request)) {
                is Iperf3RunResult.Success -> {
                    _uiState.update {
                        it.copy(isRunning = false, summary = result.summary, rawJson = result.rawJson)
                    }
                    toolRunRepository.finishRun(runId, resultJson = result.rawJson, success = true)
                }

                is Iperf3RunResult.Failed -> {
                    _uiState.update { it.copy(isRunning = false, error = result.error) }
                    toolRunRepository.finishRun(runId, resultJson = null, success = false)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // The coroutine is cancelled here, but if iperf_run_client() is
        // already inside its blocking call, cancellation only takes effect
        // once that call returns - it cannot interrupt it early.
        runJob?.cancel()
    }

    fun asPlainText(): String {
        val state = _uiState.value
        val summary = state.summary
        return buildString {
            appendLine("iperf3 ${state.host}:${state.port} (${if (state.useUdp) "UDP" else "TCP"})")
            if (summary != null) {
                summary.sentBitsPerSecond?.let { appendLine("sent:     ${formatBits(it)}") }
                summary.receivedBitsPerSecond?.let { appendLine("received: ${formatBits(it)}") }
                summary.retransmits?.let { appendLine("retransmits: $it") }
            }
            state.rawJson?.let {
                appendLine()
                appendLine(it)
            }
        }
    }

    private fun formatBits(bitsPerSecond: Double): String {
        val mbps = bitsPerSecond / 1_000_000.0
        return String.format(java.util.Locale.ROOT, "%.2f Mbit/s", mbps)
    }
}
