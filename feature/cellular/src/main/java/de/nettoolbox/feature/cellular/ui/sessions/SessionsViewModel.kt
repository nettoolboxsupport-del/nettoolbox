package de.nettoolbox.feature.cellular.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.database.entity.MeasurementSessionEntity
import de.nettoolbox.feature.cellular.data.DriveTestRepository
import de.nettoolbox.feature.cellular.domain.SessionStatistics
import de.nettoolbox.feature.cellular.domain.export.ExportFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionsUiState(
    val expandedSessionId: Long? = null,
    val statistics: Map<Long, SessionStatistics> = emptyMap(),
    val pendingFormat: ExportFormat? = null,
    val pendingSessionId: Long? = null,
)

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: DriveTestRepository,
) : ViewModel() {

    val sessions: StateFlow<List<MeasurementSessionEntity>> = repository.sessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    /**
     * Statistics are computed on expand, not for the whole list: analysing every
     * session up front would read every sample of every drive test to render a
     * list the user may only scroll past.
     */
    fun onToggleExpand(sessionId: Long) {
        val alreadyOpen = _uiState.value.expandedSessionId == sessionId
        _uiState.update { it.copy(expandedSessionId = if (alreadyOpen) null else sessionId) }
        if (alreadyOpen || _uiState.value.statistics.containsKey(sessionId)) return

        viewModelScope.launch {
            val statistics = repository.statisticsOf(sessionId)
            _uiState.update { it.copy(statistics = it.statistics + (sessionId to statistics)) }
        }
    }

    fun onExportRequested(sessionId: Long, format: ExportFormat) {
        _uiState.update { it.copy(pendingSessionId = sessionId, pendingFormat = format) }
    }

    fun onExportHandled() {
        _uiState.update { it.copy(pendingSessionId = null, pendingFormat = null) }
    }

    /** Called by the file picker once the user has chosen a destination. */
    suspend fun exportContent(): String {
        val state = _uiState.value
        val sessionId = state.pendingSessionId ?: return ""
        val format = state.pendingFormat ?: return ""
        return repository.export(sessionId, format)
    }

    fun delete(sessionId: Long) {
        viewModelScope.launch {
            repository.delete(sessionId)
            _uiState.update {
                it.copy(
                    statistics = it.statistics - sessionId,
                    expandedSessionId = if (it.expandedSessionId == sessionId) null else it.expandedSessionId,
                )
            }
        }
    }
}
