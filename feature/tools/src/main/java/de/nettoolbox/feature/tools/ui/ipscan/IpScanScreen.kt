package de.nettoolbox.feature.tools.ui.ipscan

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import de.nettoolbox.feature.tools.domain.scan.DiscoveredHost

@Composable
fun IpScanScreen(
    modifier: Modifier = Modifier,
    viewModel: IpScanViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val exporter = rememberFileExporter(
        content = viewModel::asPlainText,
        mimeType = ExportMimeType.CSV,
    )

    if (uiState.showDisclaimer) {
        AlertDialog(
            onDismissRequest = viewModel::onDisclaimerDismissed,
            title = { Text(stringResource(R.string.scanner_disclaimer_title)) },
            text = { Text(stringResource(R.string.scanner_disclaimer_text)) },
            confirmButton = {
                TextButton(onClick = viewModel::onDisclaimerAccepted) {
                    Text(stringResource(R.string.scanner_disclaimer_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDisclaimerDismissed) {
                    Text(stringResource(R.string.scanner_disclaimer_cancel))
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        uiState.localNetwork?.let { network ->
            Text(
                text = buildString {
                    append(network.interfaceName ?: "-")
                    network.ipv4Address?.let { append(" · $it") }
                    if (network.dnsServers.isNotEmpty()) {
                        append(" · DNS ${network.dnsServers.joinToString(", ")}")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        OutlinedTextField(
            value = uiState.subnetInput,
            onValueChange = viewModel::onSubnetChange,
            label = { Text(stringResource(R.string.ipscan_subnet_label)) },
            placeholder = { Text("192.168.1.0/24") },
            isError = uiState.subnetInput.isNotEmpty() && uiState.subnet == null,
            supportingText = {
                if (uiState.subnetInput.isNotEmpty() && uiState.subnet == null) {
                    Text(stringResource(R.string.ipscan_subnet_invalid))
                } else {
                    Text(stringResource(R.string.ipscan_subnet_hint))
                }
            },
            singleLine = true,
            enabled = !uiState.isRunning,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            FilterChip(
                selected = uiState.useMdns,
                onClick = { viewModel.onMdnsChange(!uiState.useMdns) },
                enabled = !uiState.isRunning,
                label = { Text("mDNS") },
            )
            FilterChip(
                selected = uiState.useSsdp,
                onClick = { viewModel.onSsdpChange(!uiState.useSsdp) },
                enabled = !uiState.isRunning,
                label = { Text("SSDP") },
            )
            FilterChip(
                selected = uiState.useNetBios,
                onClick = { viewModel.onNetBiosChange(!uiState.useNetBios) },
                enabled = !uiState.isRunning,
                label = { Text("NetBIOS") },
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
                    R.string.ipscan_progress,
                    uiState.completed,
                    uiState.total,
                    uiState.hosts.size,
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
            items(uiState.hosts) { host -> HostRow(host) }
        }

        if (uiState.hosts.isNotEmpty()) {
            ResultActionBar(
                plainText = viewModel.asPlainText(),
                onExport = { exporter.export(exportFileName("ipscan", "csv")) },
            )
        }
    }
}

@Composable
private fun HostRow(host: DiscoveredHost) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = host.ip, style = MonospaceTextStyle)

        host.hostname?.let { name ->
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (host.openPorts.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.ipscan_open_ports,
                    host.openPorts.joinToString(", "),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        host.services.take(3).forEach { service ->
            Text(
                text = service,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Naming the source is not decoration: "found via SSDP only" tells the
        // reader the host answered a multicast query but has no open TCP port.
        Text(
            text = host.sources.joinToString(", ") { it.name },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    HorizontalDivider()
}
