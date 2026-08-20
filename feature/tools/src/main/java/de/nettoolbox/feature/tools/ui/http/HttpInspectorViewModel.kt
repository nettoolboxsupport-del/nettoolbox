package de.nettoolbox.feature.tools.ui.http

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.common.result.Outcome
import de.nettoolbox.core.database.entity.ToolType
import de.nettoolbox.core.database.repository.ToolRunRepository
import de.nettoolbox.feature.tools.domain.http.HttpInspection
import de.nettoolbox.feature.tools.domain.http.HttpInspector
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class HttpMethod { GET, HEAD }

data class HttpInspectorUiState(
    val url: String = "",
    val method: HttpMethod = HttpMethod.GET,
    val isRunning: Boolean = false,
    val inspection: HttpInspection? = null,
    val error: NetToolboxError? = null,
) {
    val canStart: Boolean get() = url.isNotBlank() && !isRunning
}

@HiltViewModel
class HttpInspectorViewModel @Inject constructor(
    private val inspector: HttpInspector,
    private val toolRunRepository: ToolRunRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HttpInspectorUiState())
    val uiState: StateFlow<HttpInspectorUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null

    fun onUrlChange(value: String) = _uiState.update { it.copy(url = value, error = null) }

    fun onMethodChange(method: HttpMethod) = _uiState.update { it.copy(method = method) }

    fun start() {
        if (!_uiState.value.canStart) return
        val state = _uiState.value

        _uiState.update { it.copy(isRunning = true, inspection = null, error = null) }

        runJob = viewModelScope.launch {
            val runId = toolRunRepository.startRun(
                toolType = ToolType.HTTP_TLS,
                target = state.url.trim(),
                paramsJson = """{"method":"${state.method.name}"}""",
            )

            when (val result = inspector.inspect(state.url, state.method.name)) {
                is Outcome.Success -> {
                    _uiState.update { it.copy(isRunning = false, inspection = result.data) }
                    toolRunRepository.finishRun(
                        runId = runId,
                        resultJson = null,
                        success = result.data.finalStep?.statusCode?.let { it < 400 } == true,
                    )
                }

                is Outcome.Failure -> {
                    _uiState.update { it.copy(isRunning = false, error = result.error) }
                    toolRunRepository.finishRun(runId, resultJson = null, success = false)
                }

                Outcome.Loading -> Unit
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
        val inspection = _uiState.value.inspection ?: return@buildString

        appendLine("${_uiState.value.method} ${inspection.requestedUrl}")
        appendLine()

        inspection.steps.forEachIndexed { index, step ->
            appendLine("[${index + 1}] ${step.url}")
            appendLine("    ${step.protocol} ${step.statusCode} ${step.statusMessage} · ${step.elapsedMillis} ms")
            step.location?.let { appendLine("    Location: $it") }
            step.tls?.let { tls ->
                appendLine("    TLS: ${tls.version} · ${tls.cipherSuite}")
                tls.leaf?.let { leaf ->
                    appendLine("    Subject: ${leaf.subject}")
                    appendLine("    Issuer: ${leaf.issuer}")
                    appendLine(
                        "    Valid until: ${DATE_FORMAT.format(Date(leaf.notAfterMillis))}" +
                            " (${leaf.daysUntilExpiry()} days)",
                    )
                    if (leaf.subjectAlternativeNames.isNotEmpty()) {
                        appendLine("    SAN: ${leaf.subjectAlternativeNames.joinToString(", ")}")
                    }
                }
            }
            step.headers.forEach { header ->
                appendLine("    ${header.name}: ${header.value}")
            }
            appendLine()
        }

        if (inspection.loopDetected) appendLine("!! redirect loop")
        if (inspection.redirectLimitReached) appendLine("!! redirect limit reached")
        appendLine("total ${inspection.totalElapsedMillis} ms")
    }

    private companion object {
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    }
}
