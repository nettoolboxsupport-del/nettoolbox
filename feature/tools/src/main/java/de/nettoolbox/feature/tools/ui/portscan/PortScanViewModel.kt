package de.nettoolbox.feature.tools.ui.portscan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.database.entity.ToolType
import de.nettoolbox.core.datastore.SettingsRepository
import de.nettoolbox.core.database.repository.ToolRunRepository
import de.nettoolbox.feature.tools.domain.scan.OpenPort
import de.nettoolbox.feature.tools.domain.scan.PortScanEvent
import de.nettoolbox.feature.tools.domain.scan.PortScanRequest
import de.nettoolbox.feature.tools.domain.scan.PortScanner
import de.nettoolbox.feature.tools.domain.scan.PortSpec
import de.nettoolbox.feature.tools.navigation.NO_RUN_ID
import de.nettoolbox.feature.tools.navigation.PortScanRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

enum class PortPreset { QUICK, COMMON, CUSTOM }

data class PortScanUiState(
    val host: String = "",
    val preset: PortPreset = PortPreset.QUICK,
    val customSpec: String = "22,80,443,8000-8100",
    val grabBanner: Boolean = false,
    val isRunning: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val openPorts: List<OpenPort> = emptyList(),
    val specError: String? = null,
    val error: NetToolboxError? = null,
    val showDisclaimer: Boolean = false,
) {
    val canStart: Boolean get() = host.isNotBlank() && !isRunning && specError == null

    val progressFraction: Float
        get() = if (total == 0) 0f else completed.toFloat() / total
}

@HiltViewModel
class PortScanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val portScanner: PortScanner,
    private val toolRunRepository: ToolRunRepository,
    private val settingsRepository: SettingsRepository,
    private val json: Json,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PortScanUiState())
    val uiState: StateFlow<PortScanUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null

    init {
        val runId = runCatching { savedStateHandle.toRoute<PortScanRoute>().runId }
            .getOrDefault(NO_RUN_ID)
        if (runId != NO_RUN_ID) {
            viewModelScope.launch { prefillFrom(runId) }
        }
    }

    /** Restores parameters without starting the scan - starting is a deliberate tap. */
    private suspend fun prefillFrom(runId: Long) {
        val run = toolRunRepository.find(runId) ?: return
        val request = runCatching {
            json.decodeFromString(PortScanRequest.serializer(), run.paramsJson)
        }.getOrNull() ?: return

        // Recognise the presets by their port list so a repeated quick scan comes
        // back as "quick" rather than as a custom list of ten numbers.
        val preset = when (request.ports) {
            PortSpec.QUICK_PORTS -> PortPreset.QUICK
            PortSpec.COMMON_PORTS -> PortPreset.COMMON
            else -> PortPreset.CUSTOM
        }

        _uiState.update {
            it.copy(
                host = request.host,
                preset = preset,
                customSpec = if (preset == PortPreset.CUSTOM) {
                    request.ports.joinToString(",")
                } else {
                    it.customSpec
                },
                grabBanner = request.grabBanner,
                specError = null,
            )
        }
    }

    fun onHostChange(value: String) = _uiState.update { it.copy(host = value, error = null) }

    fun onPresetChange(preset: PortPreset) = _uiState.update {
        it.copy(preset = preset, specError = validate(preset, it.customSpec))
    }

    fun onCustomSpecChange(value: String) = _uiState.update {
        it.copy(customSpec = value, specError = validate(it.preset, value))
    }

    fun onGrabBannerChange(value: Boolean) = _uiState.update { it.copy(grabBanner = value) }

    /**
     * Scanning someone else's network is not a neutral act, so the first scan
     * asks for an explicit confirmation. Section 9 of the spec requires it, and
     * the acknowledgement is stored so it is asked once, not nagged.
     */
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
        val ports = portsFor(state) ?: return

        val request = PortScanRequest(
            host = state.host.trim(),
            ports = ports,
            grabBanner = state.grabBanner,
        )

        _uiState.update {
            it.copy(
                isRunning = true,
                openPorts = emptyList(),
                completed = 0,
                total = ports.size,
                error = null,
            )
        }

        runJob = viewModelScope.launch {
            val runId = toolRunRepository.startRun(
                toolType = ToolType.PORT_SCAN,
                target = request.host,
                paramsJson = json.encodeToString(PortScanRequest.serializer(), request),
            )

            var openPorts = emptyList<OpenPort>()
            try {
                portScanner.scan(request).collect { event ->
                    when (event) {
                        is PortScanEvent.Found -> _uiState.update {
                            it.copy(openPorts = (it.openPorts + event.openPort).sortedBy { p -> p.port })
                        }

                        is PortScanEvent.Progress -> _uiState.update {
                            it.copy(completed = event.completed, total = event.total)
                        }

                        is PortScanEvent.Failed -> _uiState.update { it.copy(error = event.error) }

                        is PortScanEvent.Completed -> openPorts = event.openPorts
                    }
                }
            } finally {
                toolRunRepository.finishRun(
                    runId = runId,
                    resultJson = openPorts.joinToString(",") { it.port.toString() }.ifEmpty { null },
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
        appendLine("port scan ${state.host}")
        appendLine("scanned ${state.completed} of ${state.total} ports")
        appendLine()
        appendLine("port;service;connect_ms;banner")
        state.openPorts.forEach { open ->
            appendLine(
                listOf(
                    open.port.toString(),
                    open.service.orEmpty(),
                    open.connectMillis.toString(),
                    open.banner?.replace(';', ' ').orEmpty(),
                ).joinToString(";"),
            )
        }
    }

    private fun portsFor(state: PortScanUiState): List<Int>? = when (state.preset) {
        PortPreset.QUICK -> PortSpec.QUICK_PORTS
        PortPreset.COMMON -> PortSpec.COMMON_PORTS
        PortPreset.CUSTOM -> PortSpec.parseOrNull(state.customSpec)
    }

    private fun validate(preset: PortPreset, spec: String): String? {
        if (preset != PortPreset.CUSTOM) return null
        return try {
            PortSpec.parse(spec)
            null
        } catch (invalid: IllegalArgumentException) {
            invalid.message
        }
    }
}
