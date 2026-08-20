package de.nettoolbox.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.datastore.SettingsRepository
import de.nettoolbox.core.datastore.model.AppLanguage
import de.nettoolbox.core.datastore.model.ThemePreference
import de.nettoolbox.core.datastore.model.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    /**
     * The name field's own state.
     *
     * The text field must never read back from DataStore while the user types:
     * a write and its echo take a round trip through a file, and in the meantime
     * the field re-renders with the previous text, which moves the cursor and
     * swallows keystrokes. So the input is held here, applied to the field
     * immediately, and written to storage only once typing pauses.
     */
    private val _nameInput = MutableStateFlow("")
    val nameInput: StateFlow<String> = _nameInput.asStateFlow()

    init {
        viewModelScope.launch {
            _nameInput.value = settingsRepository.settings.first().userName

            _nameInput
                // The seeded value is what storage already holds.
                .drop(1)
                .debounce(WRITE_DELAY_MILLIS)
                .distinctUntilChanged()
                .collect { name ->
                    settingsRepository.update { it.copy(userName = name.trim()) }
                }
        }
    }

    fun onNameChange(value: String) {
        _nameInput.value = value.take(MAX_NAME_LENGTH)
    }

    fun onLanguageChange(language: AppLanguage) = update { it.copy(language = language) }

    fun onThemeChange(theme: ThemePreference) = update { it.copy(theme = theme) }

    fun onDynamicColorChange(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    private fun update(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    private companion object {
        /** Long enough for a full name, short enough not to break the dashboard. */
        const val MAX_NAME_LENGTH = 40

        /**
         * Long enough that a normal typing rhythm produces one write, short
         * enough that the greeting updates while the screen is still open.
         */
        const val WRITE_DELAY_MILLIS = 500L
    }
}
