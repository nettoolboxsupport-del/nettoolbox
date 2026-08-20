package de.nettoolbox.feature.cellular.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.core.database.entity.MeasurementSessionEntity
import de.nettoolbox.core.ui.component.EmptyState
import de.nettoolbox.core.ui.export.exportFileName
import de.nettoolbox.core.ui.export.rememberFileExporter
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.feature.cellular.R
import de.nettoolbox.feature.cellular.domain.SessionStatistics
import de.nettoolbox.feature.cellular.domain.export.ExportFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun SessionsScreen(
    onShowOnMap: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val exporter = rememberFileExporter(
        content = viewModel::exportContent,
        mimeType = uiState.pendingFormat?.mimeType ?: ExportFormat.CSV.mimeType,
        onResult = { _, _ -> viewModel.onExportHandled() },
    )

    // The picker is opened from an effect rather than from the click handler so
    // the requested format is already in state when the exporter reads it.
    LaunchedEffect(uiState.pendingSessionId, uiState.pendingFormat) {
        val format = uiState.pendingFormat ?: return@LaunchedEffect
        exporter.export(exportFileName("drivetest", format.extension))
    }

    if (sessions.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.sessions_empty_title),
            description = stringResource(R.string.sessions_empty_description),
            modifier = modifier,
        )
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(sessions, key = { it.id }) { session ->
            SessionCard(
                session = session,
                isExpanded = uiState.expandedSessionId == session.id,
                statistics = uiState.statistics[session.id],
                onToggle = { viewModel.onToggleExpand(session.id) },
                onExport = { format -> viewModel.onExportRequested(session.id, format) },
                onDelete = { viewModel.delete(session.id) },
                onShowOnMap = { onShowOnMap(session.id) },
            )
        }
    }
}

@Composable
private fun SessionCard(
    session: MeasurementSessionEntity,
    isExpanded: Boolean,
    statistics: SessionStatistics?,
    onToggle: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    onDelete: () -> Unit,
    onShowOnMap: () -> Unit,
) {
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (session.endedAt == null) {
                            R.string.sessions_running
                        } else {
                            R.string.sessions_finished
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (session.endedAt == null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Text(
                text = buildString {
                    append(TIMESTAMP_FORMAT.format(Date(session.startedAt)))
                    session.endedAt?.let { end ->
                        append(" · ")
                        append(formatDuration(end - session.startedAt))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "${session.deviceModel} · ${session.osVersion}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isExpanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (statistics == null) {
                    Text(
                        text = stringResource(R.string.sessions_loading_statistics),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    StatisticsBlock(statistics)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    ExportFormat.entries.forEach { format ->
                        TextButton(onClick = { onExport(format) }) {
                            Text(format.name)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onShowOnMap) {
                        Text(stringResource(R.string.sessions_show_on_map))
                    }
                    TextButton(onClick = onDelete) {
                        Text(
                            text = stringResource(R.string.sessions_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticsBlock(statistics: SessionStatistics) {
    StatisticRow(
        stringResource(R.string.sessions_stat_samples),
        stringResource(
            R.string.sessions_stat_samples_value,
            statistics.sampleCount,
            statistics.positionedCount,
        ),
    )
    StatisticRow(
        stringResource(R.string.sessions_stat_handovers),
        statistics.handoverCount.toString(),
    )
    StatisticRow(
        stringResource(R.string.sessions_stat_rsrp),
        if (statistics.medianRsrp == null) {
            "—"
        } else {
            stringResource(
                R.string.sessions_stat_rsrp_value,
                statistics.medianRsrp,
                statistics.worstRsrp ?: 0,
                statistics.bestRsrp ?: 0,
            )
        },
    )
    StatisticRow(
        stringResource(R.string.sessions_stat_duration),
        formatDuration(statistics.durationMillis),
    )
    if (statistics.ratShare.isNotEmpty()) {
        StatisticRow(
            stringResource(R.string.sessions_stat_rat),
            statistics.ratShare.entries
                .sortedByDescending { it.value }
                .joinToString(", ") { "${it.key.name.replace('_', ' ')} ${it.value}" },
        )
    }
}

@Composable
private fun StatisticRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MonospaceTextStyle,
            modifier = Modifier.weight(1.3f),
        )
    }
}

private fun formatDuration(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

private val TIMESTAMP_FORMAT = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
