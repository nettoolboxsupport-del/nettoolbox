package de.nettoolbox.feature.tools.ui.ping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.core.ui.component.BucketStrategy
import de.nettoolbox.core.ui.component.ChartSeries
import de.nettoolbox.core.ui.component.ErrorState
import de.nettoolbox.core.ui.component.LineChart
import de.nettoolbox.core.ui.component.ResultActionBar
import de.nettoolbox.core.ui.export.exportFileName
import de.nettoolbox.core.ui.export.rememberFileExporter
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.feature.tools.R
import de.nettoolbox.feature.tools.domain.ping.PingTransport

@Composable
fun PingScreen(
    modifier: Modifier = Modifier,
    viewModel: PingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val exporter = rememberFileExporter(content = viewModel::asPlainText)

    LaunchedEffect(uiState.log.size) {
        if (uiState.log.isNotEmpty()) {
            listState.animateScrollToItem(uiState.log.lastIndex)
        }
    }

    val error = uiState.error
    if (error != null && uiState.log.isEmpty()) {
        ErrorState(error = error, onRetry = { viewModel.clearError(); viewModel.start() }, modifier = modifier)
        return
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = uiState.target,
            onValueChange = viewModel::onTargetChange,
            label = { Text(stringResource(R.string.ping_target_label)) },
            placeholder = { Text("1.1.1.1") },
            singleLine = true,
            enabled = !uiState.isRunning,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            listOf(4, 10, null).forEach { count ->
                FilterChip(
                    selected = uiState.count == count,
                    onClick = { viewModel.onCountChange(count) },
                    enabled = !uiState.isRunning,
                    label = {
                        Text(count?.toString() ?: stringResource(R.string.ping_count_endless))
                    },
                )
            }
        }

        Button(
            onClick = { if (uiState.isRunning) viewModel.stop() else viewModel.start() },
            enabled = uiState.isRunning || uiState.canStart,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(
                stringResource(
                    if (uiState.isRunning) R.string.action_stop else R.string.action_start,
                ),
            )
        }

        uiState.transport?.let { transport ->
            // A TCP connect time is not an ICMP round trip; saying so is the
            // whole point of exposing the transport here.
            Text(
                text = stringResource(
                    when (transport) {
                        PingTransport.TCP_CONNECT -> R.string.ping_transport_tcp
                        PingTransport.SYSTEM_BINARY -> R.string.ping_transport_system
                        PingTransport.ICMP_DATAGRAM -> R.string.ping_transport_icmp
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (uiState.rtts.size >= 2) {
            val maximum = (uiState.rtts.max() * 1.2f).coerceAtLeast(1f)
            LineChart(
                series = listOf(
                    ChartSeries(
                        label = "RTT",
                        color = MaterialTheme.colorScheme.primary,
                        points = uiState.rtts,
                    ),
                ),
                valueRange = 0f..maximum,
                // Spikes are the finding; averaging them away would hide it.
                bucketStrategy = BucketStrategy.MAX,
                modifier = Modifier.fillMaxWidth().height(120.dp).padding(top = 12.dp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(top = 12.dp),
        ) {
            items(uiState.log) { entry ->
                Text(
                    text = entry.text,
                    style = MonospaceTextStyle,
                    color = if (entry.isLoss) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }

        uiState.statistics?.let { stats ->
            Text(
                text = stringResource(
                    R.string.ping_statistics,
                    stats.sent,
                    stats.received,
                    stats.lossPercent.formatMillis(1),
                    stats.avgMillis.formatMillis(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (uiState.log.isNotEmpty()) {
            ResultActionBar(
                plainText = viewModel.asPlainText(),
                onExport = { exporter.export(exportFileName("ping", "txt")) },
            )
        }
    }
}
