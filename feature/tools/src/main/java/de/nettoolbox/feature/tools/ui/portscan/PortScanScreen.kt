package de.nettoolbox.feature.tools.ui.portscan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import de.nettoolbox.core.ui.component.ResultActionBar
import de.nettoolbox.core.ui.error.errorHint
import de.nettoolbox.core.ui.error.errorTitle
import de.nettoolbox.core.ui.export.ExportMimeType
import de.nettoolbox.core.ui.export.exportFileName
import de.nettoolbox.core.ui.export.rememberFileExporter
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.feature.tools.R

@Composable
fun PortScanScreen(
    modifier: Modifier = Modifier,
    viewModel: PortScanViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val exporter = rememberFileExporter(
        content = viewModel::asPlainText,
        mimeType = ExportMimeType.CSV,
    )

    if (uiState.showDisclaimer) {
        ScannerDisclaimerDialog(
            onAccept = viewModel::onDisclaimerAccepted,
            onDismiss = viewModel::onDisclaimerDismissed,
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = uiState.host,
            onValueChange = viewModel::onHostChange,
            label = { Text(stringResource(R.string.portscan_host_label)) },
            placeholder = { Text("192.168.1.1") },
            singleLine = true,
            enabled = !uiState.isRunning,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            PortPreset.entries.forEach { preset ->
                FilterChip(
                    selected = uiState.preset == preset,
                    onClick = { viewModel.onPresetChange(preset) },
                    enabled = !uiState.isRunning,
                    label = {
                        Text(
                            stringResource(
                                when (preset) {
                                    PortPreset.QUICK -> R.string.portscan_preset_quick
                                    PortPreset.COMMON -> R.string.portscan_preset_common
                                    PortPreset.CUSTOM -> R.string.portscan_preset_custom
                                },
                            ),
                        )
                    },
                )
            }
        }

        if (uiState.preset == PortPreset.CUSTOM) {
            OutlinedTextField(
                value = uiState.customSpec,
                onValueChange = viewModel::onCustomSpecChange,
                label = { Text(stringResource(R.string.portscan_ports_label)) },
                isError = uiState.specError != null,
                supportingText = {
                    Text(uiState.specError ?: stringResource(R.string.portscan_ports_hint))
                },
                singleLine = true,
                enabled = !uiState.isRunning,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Switch(
                checked = uiState.grabBanner,
                onCheckedChange = viewModel::onGrabBannerChange,
                enabled = !uiState.isRunning,
            )
            Text(
                text = stringResource(R.string.portscan_banner),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Button(
            onClick = { if (uiState.isRunning) viewModel.stop() else viewModel.onStartRequested() },
            enabled = uiState.isRunning || uiState.canStart,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(
                stringResource(
                    if (uiState.isRunning) R.string.action_stop else R.string.action_start,
                ),
            )
        }

        if (uiState.total > 0) {
            LinearProgressIndicator(
                progress = { uiState.progressFraction },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Text(
                text = stringResource(
                    R.string.portscan_progress,
                    uiState.completed,
                    uiState.total,
                    uiState.openPorts.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        uiState.error?.let { error ->
            Text(
                text = errorTitle(error),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = errorHint(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
            items(uiState.openPorts) { open ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        text = buildString {
                            append(open.port)
                            open.service?.let { append("  $it") }
                            append("  ·  ${open.connectMillis} ms")
                        },
                        style = MonospaceTextStyle,
                    )
                    open.banner?.let { banner ->
                        Text(
                            text = banner,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
        }

        if (uiState.openPorts.isNotEmpty()) {
            ResultActionBar(
                plainText = viewModel.asPlainText(),
                onExport = { exporter.export(exportFileName("portscan", "csv")) },
            )
        }
    }
}

/**
 * Shown once before the first scan. Section 9 of the spec requires it, and it is
 * a genuine legal boundary rather than a formality.
 */
@Composable
private fun ScannerDisclaimerDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scanner_disclaimer_title)) },
        text = { Text(stringResource(R.string.scanner_disclaimer_text)) },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.scanner_disclaimer_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.scanner_disclaimer_cancel))
            }
        },
    )
}
