package de.nettoolbox.feature.tools.ui.traceroute

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.database.entity.ToolType
import de.nettoolbox.core.database.repository.ToolRunRepository
import de.nettoolbox.feature.tools.domain.traceroute.TracerouteEvent
import de.nettoolbox.feature.tools.domain.traceroute.TracerouteHop
import de.nettoolbox.feature.tools.domain.traceroute.TracerouteRequest
import de.nettoolbox.feature.tools.domain.traceroute.TracerouteRunner
import de.nettoolbox.feature.tools.navigation.NO_RUN_ID
import de.nettoolbox.feature.tools.navigation.TracerouteRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject

data class TracerouteUiState(
    val target: String = "",
    val isRunning: Boolean = false,
    val hops: List<TracerouteHop> = emptyList(),
    val error: NetToolboxError? = null,
) {
    val canStart: Boolean get() = target.isNotBlank() && !isRunning
}

@HiltViewModel
class TracerouteViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val runner: TracerouteRunner,
    private val toolRunRepository: ToolRunRepository,
    private val json: Json,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TracerouteUiState())
    val uiState: StateFlow<TracerouteUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null

    init {
        val runId = runCatching { savedStateHandle.toRoute<TracerouteRoute>().runId }
            .getOrDefault(NO_RUN_ID)
        if (runId != NO_RUN_ID) {
            viewModelScope.launch { prefillFrom(runId) }
        }
    }

    private suspend fun prefillFrom(runId: Long) {
        val run = toolRunRepository.find(runId) ?: return
        val request = runCatching {
            json.decodeFromString(TracerouteRequest.serializer(), run.paramsJson)
        }.getOrNull() ?: return

        _uiState.update { it.copy(target = request.target) }
    }

    fun onTargetChange(value: String) = _uiState.update { it.copy(target = value, error = null) }

    fun start() {
        if (!_uiState.value.canStart) return
        val target = _uiState.value.target.trim()
        val request = TracerouteRequest(target = target)

        _uiState.update { it.copy(isRunning = true, hops = emptyList(), error = null) }

        runJob = viewModelScope.launch {
            val runId = toolRunRepository.startRun(
                toolType = ToolType.TRACEROUTE,
                target = target,
                paramsJson = json.encodeToString(TracerouteRequest.serializer(), request),
            )

            var reachedDestination = false
            try {
                runner.run(request).collect { event ->
                    when (event) {
                        is TracerouteEvent.Hop -> {
                            if (event.hop.isDestination) reachedDestination = true
                            _uiState.update { it.copy(hops = it.hops + event.hop) }
                        }

                        is TracerouteEvent.Failed -> _uiState.update { it.copy(error = event.error) }

                        TracerouteEvent.Completed -> Unit
                    }
                }
            } finally {
                toolRunRepository.finishRun(runId, resultJson = null, success = reachedDestination)
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
        appendLine("traceroute ${state.target}")
        state.hops.forEach { hop ->
            val address = hop.address ?: "*"
            val rtt = hop.rttMillis?.let { String.format(Locale.ROOT, "%.2f ms", it) } ?: "*"
            val suffix = if (hop.isUnreachable) " (unreachable)" else ""
            appendLine("${hop.ttl}\t$address\t$rtt$suffix")
        }
    }
}
