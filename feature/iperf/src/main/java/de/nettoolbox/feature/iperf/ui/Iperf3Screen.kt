package de.nettoolbox.feature.iperf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.core.permissions.PermissionBundle
import de.nettoolbox.core.permissions.rememberPermissionBundleState
import de.nettoolbox.core.ui.component.ResultActionBar
import de.nettoolbox.core.ui.error.errorHint
import de.nettoolbox.core.ui.error.errorTitle
import de.nettoolbox.core.ui.export.exportFileName
import de.nettoolbox.core.ui.export.rememberFileExporter
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.feature.iperf.R
import java.util.Date
import java.util.Locale

@Composable
fun Iperf3Screen(
    modifier: Modifier = Modifier,
    viewModel: Iperf3ViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val exporter = rememberFileExporter(content = { viewModel.asPlainText() })

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.feature_iperf_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.iperf3_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) {
            Iperf3Mode.entries.forEach { mode ->
                FilterChip(
                    selected = uiState.mode == mode,
                    onClick = { viewModel.onModeChange(mode) },
                    label = {
                        Text(
                            stringResource(
                                when (mode) {
                                    Iperf3Mode.CLIENT -> R.string.iperf3_mode_client
                                    Iperf3Mode.SERVER -> R.string.iperf3_mode_server
                                },
                            ),
                        )
                    },
                )
            }
        }

        if (uiState.mode == Iperf3Mode.SERVER) {
            ServerSection(viewModel = viewModel, uiState = uiState)
            return@Column
        }

        OutlinedTextField(
            value = uiState.host,
            onValueChange = viewModel::onHostChange,
            label = { Text(stringResource(R.string.iperf3_host_label)) },
            placeholder = { Text("192.168.1.50") },
            singleLine = true,
            enabled = !uiState.isRunning,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = uiState.port,
            onValueChange = viewModel::onPortChange,
            label = { Text(stringResource(R.string.iperf3_port_label)) },
            singleLine = true,
            enabled = !uiState.isRunning,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            FilterChip(
                selected = !uiState.useUdp,
                onClick = { viewModel.onUseUdpChange(false) },
                enabled = !uiState.isRunning,
                label = { Text("TCP") },
            )
            FilterChip(
                selected = uiState.useUdp,
                onClick = { viewModel.onUseUdpChange(true) },
                enabled = !uiState.isRunning,
                label = { Text("UDP") },
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            iperf3DurationOptions.forEach { seconds ->
                FilterChip(
                    selected = uiState.durationSeconds == seconds,
                    onClick = { viewModel.onDurationChange(seconds) },
                    enabled = !uiState.isRunning,
                    label = { Text("${seconds}s") },
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Switch(
                checked = uiState.reverse,
                onCheckedChange = viewModel::onReverseChange,
                enabled = !uiState.isRunning,
            )
            Text(
                text = stringResource(R.string.iperf3_reverse),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Button(
            onClick = viewModel::start,
            enabled = uiState.canStart,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            if (uiState.isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp).padding(end = 8.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(stringResource(R.string.iperf3_running, uiState.durationSeconds))
            } else {
                Text(stringResource(R.string.action_start))
            }
        }

        Text(
            text = stringResource(R.string.iperf3_no_cancel_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        uiState.error?.let { error ->
            Text(
                text = errorTitle(error),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = errorHint(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error.detail?.let {
                Text(
                    text = it,
                    style = MonospaceTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        uiState.summary?.let { summary ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    summary.sentBitsPerSecond?.let {
                        SummaryRow(stringResource(R.string.iperf3_sent), formatBits(it))
                    }
                    summary.receivedBitsPerSecond?.let {
                        SummaryRow(stringResource(R.string.iperf3_received), formatBits(it))
                    }
                    summary.retransmits?.let {
                        SummaryRow(stringResource(R.string.iperf3_retransmits), it.toString())
                    }
                    if (summary.sentBitsPerSecond == null && summary.receivedBitsPerSecond == null) {
                        Text(
                            text = stringResource(R.string.iperf3_summary_unparsed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (uiState.rawJson != null) {
            ResultActionBar(
                plainText = viewModel.asPlainText(),
                onExport = { exporter.export(exportFileName("iperf3", "json")) },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * Server mode. The device's own addresses are shown because the first thing
 * anyone needs after starting a server is where to point the client.
 */
@Composable
private fun ServerSection(
    viewModel: Iperf3ViewModel,
    uiState: Iperf3UiState,
) {
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()

    // From Android 13 a foreground service without notification permission is
    // invisible, which makes a listening server impossible to notice or stop.
    val notifications = rememberPermissionBundleState(
        bundle = PermissionBundle.SERVICE_NOTIFICATIONS,
        coordinator = viewModel.permissionCoordinator,
    )

    val portValue = uiState.serverPort.toIntOrNull()
    val portValid = portValue != null && portValue in 1024..65535

    OutlinedTextField(
        value = uiState.serverPort,
        onValueChange = viewModel::onServerPortChange,
        label = { Text(stringResource(R.string.iperf3_server_port_label)) },
        supportingText = { Text(stringResource(R.string.iperf3_server_port_hint)) },
        isError = uiState.serverPort.isNotEmpty() && !portValid,
        singleLine = true,
        enabled = !serverState.isRunning,
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = {
            when {
                serverState.isRunning -> viewModel.stopServer()
                notifications.allGranted -> viewModel.startServer()
                else -> notifications.request()
            }
        },
        // Disabled while stopping: a second start would try to bind a port the
        // old server has not released yet.
        enabled = when {
            serverState.isStopping -> false
            serverState.isRunning -> true
            else -> portValid
        },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Text(
            stringResource(
                when {
                    serverState.isStopping -> R.string.iperf3_server_stopping
                    serverState.isRunning -> R.string.iperf3_server_stop
                    else -> R.string.iperf3_server_start
                },
            ),
        )
    }

    if (serverState.isStopping) {
        Text(
            text = stringResource(R.string.iperf3_server_stopping_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    if (serverState.isRunning && !serverState.isStopping) {
        serverState.port?.let { port ->
            Text(
                text = stringResource(R.string.iperf3_server_listening, port),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        val addresses = viewModel.localAddresses()
        if (addresses.isNotEmpty()) {
            Text(
                text = stringResource(R.string.iperf3_server_addresses, addresses.joinToString(", ")),
                style = MonospaceTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Text(
            text = stringResource(R.string.iperf3_server_stop_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    val tests = serverState.completedTests
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Text(
            text = if (tests.isEmpty()) {
                stringResource(R.string.iperf3_server_no_tests)
            } else {
                pluralStringResource(R.plurals.iperf3_server_tests, tests.size, tests.size)
            },
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (tests.isNotEmpty()) {
            TextButton(onClick = viewModel::clearServerResults) {
                Text(stringResource(R.string.iperf3_server_clear_results))
            }
        }
    }

    tests.asReversed().forEach { test ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = TIME_FORMAT.format(Date(test.finishedAtMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                test.summary.sentBitsPerSecond?.let {
                    SummaryRow(stringResource(R.string.iperf3_sent), formatBits(it))
                }
                test.summary.receivedBitsPerSecond?.let {
                    SummaryRow(stringResource(R.string.iperf3_received), formatBits(it))
                }
                test.summary.retransmits?.let {
                    SummaryRow(stringResource(R.string.iperf3_retransmits), it.toString())
                }

                // Never render an empty card: a test that produced no readable
                // numbers has to say so, and say which of the two faults it was,
                // otherwise it is indistinguishable from nothing having happened.
                if (!test.hasNumbers) {
                    Text(
                        text = stringResource(
                            if (test.jsonWasEmpty) {
                                R.string.iperf3_server_no_json
                            } else {
                                R.string.iperf3_summary_unparsed
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (!test.jsonWasEmpty) {
                        Text(
                            text = test.rawJson.take(RAW_JSON_PREVIEW_CHARS),
                            style = MonospaceTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

    serverState.lastErrorDetail?.let { detail ->
        Text(
            text = detail,
            style = MonospaceTextStyle,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MonospaceTextStyle)
    }
}

private fun formatBits(bitsPerSecond: Double): String =
    String.format(Locale.ROOT, "%.2f Mbit/s", bitsPerSecond / 1_000_000.0)

/** Enough of the raw JSON to see which shape came back, without flooding the screen. */
private const val RAW_JSON_PREVIEW_CHARS = 600

private val TIME_FORMAT = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
