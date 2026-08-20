package de.nettoolbox.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.datastore.SettingsRepository
import de.nettoolbox.core.datastore.model.UserSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface MainUiState {

    /** Settings are still being read; the splash screen stays up. */
    data object Loading : MainUiState

    data class Ready(val settings: UserSettings) : MainUiState
}

/**
 * Holds only what the activity itself needs: the theme settings.
 *
 * The Loading state exists so the splash screen can be held until the stored
 * theme is known - otherwise the app paints one frame in the system theme and
 * then flips, which is exactly the flash the field theme is meant to avoid.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = settingsRepository.settings
        .map<UserSettings, MainUiState> { MainUiState.Ready(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState.Loading,
        )

    /**
     * Records that the first-run introduction was seen.
     *
     * Written when the user leaves the screen, not when a permission is
     * granted: the introduction has been read either way, and tying it to
     * permissions would show it again to everyone who declined.
     */
    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(onboardingCompleted = true) }
        }
    }
}
