package de.nettoolbox.feature.cellular

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.core.common.radio.RadioAccessTechnology
import de.nettoolbox.core.common.signal.SignalMetric
import de.nettoolbox.core.permissions.PermissionBundle
import de.nettoolbox.core.permissions.PermissionGate
import de.nettoolbox.core.permissions.rememberPermissionBundleState
import de.nettoolbox.core.ui.component.BucketStrategy
import de.nettoolbox.core.ui.component.ChartSeries
import de.nettoolbox.core.ui.component.EmptyState
import de.nettoolbox.core.ui.component.LineChart
import de.nettoolbox.core.ui.component.SignalGauge
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.core.ui.theme.color
import de.nettoolbox.core.ui.theme.labelRes
import de.nettoolbox.feature.cellular.domain.NeighborCell
import de.nettoolbox.feature.cellular.domain.ServingCell
import de.nettoolbox.feature.cellular.ui.CellularUiState
import de.nettoolbox.feature.cellular.ui.CellularViewModel

@Composable
fun CellularScreen(
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CellularViewModel = hiltViewModel(),
) {
    PermissionGate(
        bundle = PermissionBundle.CELLULAR_LIVE,
        coordinator = viewModel.permissionCoordinator,
        modifier = modifier,
    ) {
        CellularContent(
            viewModel = viewModel,
            onOpenSessions = onOpenSessions,
            modifier = modifier,
        )
    }
}

@Composable
private fun CellularContent(
    viewModel: CellularViewModel,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.start() }

    val snapshot = uiState.snapshot
    val serving = snapshot?.serving

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        if (uiState.isMultiSim) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                uiState.subscriptions.forEach { subscription ->
                    FilterChip(
                        selected = uiState.selectedSubscriptionId == subscription.subscriptionId,
                        onClick = { viewModel.onSubscriptionSelected(subscription.subscriptionId) },
                        label = { Text(subscription.displayName) },
                    )
                }
            }
        }

        if (snapshot != null && !snapshot.hasService) {
            Text(
                text = stringResource(R.string.cellular_no_service),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (serving == null) {
            EmptyState(
                title = stringResource(R.string.cellular_waiting_title),
                description = stringResource(R.string.cellular_waiting_description),
                modifier = Modifier.fillMaxWidth().height(280.dp),
            )
        } else {
            GaugeRow(serving)
            ServingCellDetails(serving, snapshot?.networkOperatorName)

            if (uiState.rsrpHistory.size >= 2) {
                Text(
                    text = stringResource(R.string.cellular_rsrp_history),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                LineChart(
                    series = listOf(
                        ChartSeries(
                            label = "RSRP",
                            color = MaterialTheme.colorScheme.primary,
                            points = uiState.rsrpHistory,
                        ),
                    ),
                    valueRange = -140f..-40f,
                    // Drops are what a coverage complaint is about; averaging
                    // them away would hide the reason for the measurement.
                    bucketStrategy = BucketStrategy.MIN,
                    modifier = Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp),
                )
            }

            NeighborList(snapshot?.neighbors.orEmpty(), serving)
        }

        OutlinedButton(
            onClick = viewModel::refreshNow,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text(stringResource(R.string.cellular_refresh))
        }

        LoggingCard(viewModel = viewModel, onOpenSessions = onOpenSessions)

        // The platform limit, stated rather than worked around.
        Text(
            text = stringResource(R.string.cellular_foreign_operator_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
        )
    }
}

/**
 * Start and stop of the recording. Deliberately on the live screen: a technician
 * starts a drive test from the same place they check whether the signal is worth
 * recording at all.
 */
@Composable
private fun LoggingCard(
    viewModel: CellularViewModel,
    onOpenSessions: () -> Unit,
) {
    val logging by viewModel.driveTestState.collectAsStateWithLifecycle()
    val sessionName by viewModel.sessionName.collectAsStateWithLifecycle()

    // From Android 13 a foreground service without notification permission is
    // invisible, which makes a running recording impossible to notice or stop.
    val notifications = rememberPermissionBundleState(
        bundle = PermissionBundle.SERVICE_NOTIFICATIONS,
        coordinator = viewModel.permissionCoordinator,
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.logging_title),
                style = MaterialTheme.typography.titleSmall,
            )

            if (logging.isRecording) {
                Text(
                    text = stringResource(
                        R.string.logging_status,
                        logging.sampleCount,
                        logging.handoverCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (!logging.hasFix) {
                    Text(
                        text = stringResource(R.string.logging_no_fix),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    logging.lastAccuracyMeters?.let { accuracy ->
                        Text(
                            text = stringResource(R.string.logging_accuracy, accuracy),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(
                    onClick = viewModel::stopLogging,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.logging_stop))
                }
            } else {
                OutlinedTextField(
                    value = sessionName,
                    onValueChange = viewModel::onSessionNameChange,
                    label = { Text(stringResource(R.string.logging_session_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.logging_background_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedButton(
                    onClick = {
                        if (notifications.allGranted) {
                            viewModel.startLogging()
                        } else {
                            // Ask first; the user taps start again once granted.
                            notifications.request()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(
                        stringResource(
                            if (notifications.allGranted) {
                                R.string.logging_start
                            } else {
                                R.string.logging_allow_notifications
                            },
                        ),
                    )
                }
            }

            TextButton(
                onClick = onOpenSessions,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.sessions_open))
            }
        }
    }
}

@Composable
private fun GaugeRow(serving: ServingCell) {
    val metrics = serving.metrics

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        when (serving.rat) {
            RadioAccessTechnology.UMTS -> {
                Gauge(SignalMetric.RSCP, metrics.rscp, "RSCP", Modifier.weight(1f))
                Gauge(SignalMetric.ECNO, metrics.ecno, "Ec/No", Modifier.weight(1f))
            }

            RadioAccessTechnology.GSM -> {
                Gauge(SignalMetric.RSSI, metrics.rssi, "RSSI", Modifier.weight(1f))
            }

            else -> {
                Gauge(SignalMetric.RSRP, metrics.rsrp, "RSRP", Modifier.weight(1f))
                Gauge(SignalMetric.RSRQ, metrics.rsrq, "RSRQ", Modifier.weight(1f))
                Gauge(SignalMetric.SINR, metrics.sinr, "SINR", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Gauge(
    metric: SignalMetric,
    value: Int?,
    label: String,
    modifier: Modifier = Modifier,
) {
    SignalGauge(
        metric = metric,
        value = value?.toFloat(),
        label = label,
        modifier = modifier,
    )
}

@Composable
private fun ServingCellDetails(serving: ServingCell, operatorName: String?) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            DetailRow(
                stringResource(R.string.cellular_technology),
                buildString {
                    append(serving.rat.name.replace('_', ' '))
                    serving.displayNetworkType
                        ?.takeIf { it != "—" }
                        ?.let { append(" · $it") }
                },
            )
            DetailRow(
                stringResource(R.string.cellular_operator),
                serving.operatorName ?: operatorName,
            )
            DetailRow(stringResource(R.string.cellular_plmn), serving.plmn)
            DetailRow(stringResource(R.string.cellular_band), serving.band?.toString())
            DetailRow(stringResource(R.string.cellular_arfcn), serving.arfcn?.toString())
            DetailRow(stringResource(R.string.cellular_cell_id), serving.cellId?.toString())
            DetailRow(stringResource(R.string.cellular_pci), serving.pci?.toString())
            DetailRow(stringResource(R.string.cellular_tac), serving.tac?.toString())
            DetailRow(
                stringResource(R.string.cellular_bandwidth),
                serving.bandwidthKhz?.let { "${it / 1000} MHz" },
            )
            serving.timingAdvance?.let { ta ->
                DetailRow(
                    stringResource(R.string.cellular_timing_advance),
                    stringResource(
                        R.string.cellular_timing_advance_value,
                        ta,
                        serving.estimatedDistanceMeters ?: 0,
                    ),
                )
            }
            if (serving.isRoaming) {
                DetailRow(
                    stringResource(R.string.cellular_roaming),
                    stringResource(R.string.cellular_roaming_yes),
                )
            }
        }
    }
}

@Composable
private fun NeighborList(neighbors: List<NeighborCell>, serving: ServingCell) {
    Text(
        text = stringResource(R.string.cellular_neighbors, neighbors.size),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp),
    )

    if (neighbors.isEmpty()) {
        Text(
            text = stringResource(R.string.cellular_neighbors_empty),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }

    neighbors.forEach { neighbor ->
        val quality = SignalMetric.RSRP.classify(neighbor.metrics.rsrp?.toFloat())
        val delta = neighbor.rsrpDeltaTo(serving)

        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row {
                Text(
                    text = buildString {
                        append(neighbor.rat.name.replace('_', ' '))
                        neighbor.pci?.let { append(" · PCI $it") }
                        neighbor.band?.let { append(" · $it") }
                    },
                    style = MonospaceTextStyle,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = neighbor.metrics.rsrp?.let { "$it dBm" } ?: "—",
                    style = MaterialTheme.typography.labelLarge,
                    color = quality.color(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(quality.labelRes()),
                    style = MaterialTheme.typography.labelSmall,
                    color = quality.color(),
                )
                delta?.let {
                    // A positive delta means the neighbour is stronger than the
                    // serving cell - the moment before a handover.
                    Text(
                        text = stringResource(R.string.cellular_delta, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value ?: "—",
            style = MonospaceTextStyle,
            modifier = Modifier.weight(1.3f),
        )
    }
}
