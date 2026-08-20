package de.nettoolbox.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.app.R
import de.nettoolbox.core.datastore.model.AppLanguage
import de.nettoolbox.core.datastore.model.ThemePreference

@Composable
fun SettingsScreen(
    onOpenAbout: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Deliberately not settings.userName: the field owns its text while typing.
    val nameInput by viewModel.nameInput.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionTitle(stringResource(R.string.settings_section_person))

        OutlinedTextField(
            value = nameInput,
            onValueChange = viewModel::onNameChange,
            label = { Text(stringResource(R.string.settings_name_label)) },
            supportingText = { Text(stringResource(R.string.settings_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle(stringResource(R.string.settings_section_language))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.entries.forEach { language ->
                FilterChip(
                    selected = settings.language == language,
                    onClick = { viewModel.onLanguageChange(language) },
                    label = { Text(stringResource(language.labelRes())) },
                )
            }
        }

        SectionTitle(stringResource(R.string.settings_section_appearance))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemePreference.entries.forEach { theme ->
                FilterChip(
                    selected = settings.theme == theme,
                    onClick = { viewModel.onThemeChange(theme) },
                    label = { Text(stringResource(theme.labelRes())) },
                )
            }
        }

        Text(
            text = stringResource(R.string.settings_theme_field_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_dynamic_color),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_dynamic_color_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.dynamicColor,
                onCheckedChange = viewModel::onDynamicColorChange,
                // The field theme is a fixed high-contrast palette; dynamic
                // colour would defeat the point of it.
                enabled = settings.theme != ThemePreference.FIELD,
            )
        }

        SectionTitle(stringResource(R.string.settings_section_about))

        TextButton(
            onClick = onOpenAbout,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.settings_open_about),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
}

private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.settings_language_system
    AppLanguage.GERMAN -> R.string.settings_language_german
    AppLanguage.ENGLISH -> R.string.settings_language_english
}

private fun ThemePreference.labelRes(): Int = when (this) {
    ThemePreference.SYSTEM -> R.string.settings_theme_system
    ThemePreference.LIGHT -> R.string.settings_theme_light
    ThemePreference.DARK -> R.string.settings_theme_dark
    ThemePreference.FIELD -> R.string.settings_theme_field
}
