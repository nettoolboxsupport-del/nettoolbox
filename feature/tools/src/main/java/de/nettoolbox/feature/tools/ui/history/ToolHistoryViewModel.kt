package de.nettoolbox.feature.tools.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.database.entity.ToolRunEntity
import de.nettoolbox.core.database.entity.ToolType
import de.nettoolbox.core.database.repository.ToolRunRepository
import de.nettoolbox.feature.tools.navigation.ToolHistoryRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ToolHistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ToolRunRepository,
) : ViewModel() {

    val toolType: ToolType = runCatching {
        ToolType.valueOf(savedStateHandle.toRoute<ToolHistoryRoute>().toolTypeName)
    }.getOrDefault(ToolType.PING)

    val runs: StateFlow<List<ToolRunEntity>> = repository.history(toolType)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(runId: Long) = viewModelScope.launch { repository.delete(runId) }

    fun clear() = viewModelScope.launch { repository.clear(toolType) }
}
