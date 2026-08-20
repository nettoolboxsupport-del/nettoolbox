package de.nettoolbox.feature.tools.ui.ping

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.database.entity.ToolType
import de.nettoolbox.core.database.repository.ToolRunRepository
import de.nettoolbox.feature.tools.domain.ping.PingEvent
import de.nettoolbox.feature.tools.domain.ping.PingRequest
import de.nettoolbox.feature.tools.domain.ping.PingService
import de.nettoolbox.feature.tools.domain.ping.PingStatistics
import de.nettoolbox.feature.tools.domain.ping.PingTransport
import de.nettoolbox.feature.tools.navigation.NO_RUN_ID
import de.nettoolbox.feature.tools.navigation.PingRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject

/** A line in the live output, closely mirroring what `ping` itself prints. */
data class PingLogEntry(
    val sequence: Int,
    val text: String,
    val rttMillis: Double?,
    val isLoss: Boolean,
)

data class PingUiState(
    val target: String = "",
    val count: Int? = 4,
    val intervalMillis: Long = 1_000,
    val payloadSizeBytes: Int = 56,
    val isRunning: Boolean = false,
    val transport: PingTransport? = null,
    val log: List<PingLogEntry> = emptyList(),
    val rtts: List<Float> = emptyList(),
    val statistics: PingStatistics? = null,
    val error: NetToolboxError? = null,
) {
    val canStart: Boolean get() = target.isNotBlank() && !isRunning
}

@HiltViewModel
class PingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val pingService: PingService,
    private val toolRunRepository: ToolRunRepository,
    private val json: Json,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PingUiState())
    val uiState: StateFlow<PingUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null

    init {
        val runId = runCatching { savedStateHandle.toRoute<PingRoute>().runId }
            .getOrDefault(NO_RUN_ID)
        if (runId != NO_RUN_ID) {
            viewModelScope.launch { prefillFrom(runId) }
        }
    }

    /**
     * Restores the parameters of an earlier run without starting it. Repeating a
     * measurement should be a deliberate tap, not a side effect of opening a
     * history entry.
     */
    private suspend fun prefillFrom(runId: Long) {
        val run = toolRunRepository.find(runId) ?: return
        val request = runCatching {
            json.decodeFromString(PingRequest.serializer(), run.paramsJson)
        }.getOrNull() ?: return

        _uiState.update {
            it.copy(
                target = request.target,
                count = request.count,
                intervalMillis = request.intervalMillis,
                payloadSizeBytes = request.payloadSizeBytes,
            )
        }
    }

    fun onTargetChange(value: String) = _uiState.update { it.copy(target = value) }

    fun onCountChange(value: Int?) = _uiState.update { it.copy(count = value) }

    fun onIntervalChange(millis: Long) = _uiState.update { it.copy(intervalMillis = millis) }

    fun start() {
        if (!_uiState.value.canStart) return

        val state = _uiState.value
        val request = PingRequest(
            target = state.target.trim(),
            count = state.count,
            intervalMillis = state.intervalMillis,
            payloadSizeBytes = state.payloadSizeBytes,
        )

        _uiState.update {
            it.copy(isRunning = true, log = emptyList(), rtts = emptyList(), statistics = null, error = null)
        }

        runJob = viewModelScope.launch {
            val transport = pingService.resolveTransport()
            _uiState.update { it.copy(transport = transport) }

            val runId = toolRunRepository.startRun(
                toolType = ToolType.PING,
                target = request.target,
                paramsJson = json.encodeToString(PingRequest.serializer(), request),
            )

            var statistics: PingStatistics? = null
            var failure: NetToolboxError? = null

            try {
                pingService.run(request, transport).collect { event ->
                    when (event) {
                        is PingEvent.Reply -> _uiState.update { current ->
                            current.copy(
                                log = current.log + PingLogEntry(
                                    sequence = event.sequence,
                                    text = "seq=${event.sequence} from ${event.from}" +
                                        (event.ttl?.let { " ttl=$it" } ?: "") +
                                        " time=${event.rttMillis.formatMillis()} ms",
                                    rttMillis = event.rttMillis,
                                    isLoss = false,
                                ),
                                rtts = current.rtts + event.rttMillis.toFloat(),
                            )
                        }

                        is PingEvent.Loss -> _uiState.update { current ->
                            current.copy(
                                log = current.log + PingLogEntry(
                                    sequence = event.sequence,
                                    text = "seq=${event.sequence} ${event.reason.name.lowercase()}" +
                                        (event.from?.let { " from $it" } ?: ""),
                                    rttMillis = null,
                                    isLoss = true,
                                ),
                            )
                        }

                        is PingEvent.Failed -> {
                            failure = event.error
                            _uiState.update { it.copy(error = event.error) }
                        }

                        is PingEvent.Completed -> {
                            statistics = event.statistics
                            _uiState.update { it.copy(statistics = event.statistics) }
                        }
                    }
                }
            } finally {
                // Runs also on cancellation, so a stopped run still gets its
                // history entry and its statistics instead of vanishing.
                val partial = statistics ?: PingStatistics.of(
                    sent = _uiState.value.log.size,
                    rtts = _uiState.value.rtts.map { it.toDouble() },
                )
                toolRunRepository.finishRun(
                    runId = runId,
                    resultJson = json.encodeToString(PingStatistics.serializer(), partial),
                    success = failure == null && partial.received > 0,
                )
                _uiState.update { it.copy(isRunning = false, statistics = it.statistics ?: partial) }
            }
        }
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    /** Plain-text rendering for copy, share and export. */
    fun asPlainText(): String {
        val state = _uiState.value
        return buildString {
            appendLine("ping ${state.target} (${state.transport?.name ?: "?"})")
            state.log.forEach { appendLine(it.text) }
            state.statistics?.let { stats ->
                appendLine(
                    "--- ${stats.sent} sent, ${stats.received} received, " +
                        "${stats.lossPercent.formatMillis(1)}% loss",
                )
                if (stats.received > 0) {
                    appendLine(
                        "rtt min/avg/max/mdev = " +
                            "${stats.minMillis.formatMillis()}/${stats.avgMillis.formatMillis()}/" +
                            "${stats.maxMillis.formatMillis()}/${stats.mdevMillis.formatMillis()} ms",
                    )
                }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}

/**
 * Fixed-point formatting in the root locale.
 *
 * Not the user's locale on purpose: these numbers end up in the clipboard, in
 * exports and in bug reports, and a German decimal comma would break every CSV
 * consumer downstream.
 */
internal fun Double?.formatMillis(decimals: Int = 2): String =
    this?.let { String.format(Locale.ROOT, "%.${decimals}f", it) } ?: "--"
