package de.nettoolbox.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.datastore.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {

    /** Empty when no name is set; the dashboard then shows no greeting at all. */
    val userName: StateFlow<String> = settingsRepository.settings
        .map { it.userName.trim() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
}
