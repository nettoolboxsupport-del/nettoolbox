package de.nettoolbox.feature.fileserver.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.feature.fileserver.R
import de.nettoolbox.feature.fileserver.domain.LogLevel
import de.nettoolbox.feature.fileserver.domain.Protocol
import de.nettoolbox.feature.fileserver.domain.TransferDirection
import de.nettoolbox.feature.fileserver.domain.TransferEvent
import de.nettoolbox.feature.fileserver.domain.TransferLog

/**
 * The live log.
 *
 * This is what separates "it doesn't work" from a diagnosis. A client that
 * fails to authenticate, one that asks for a path outside the share, and one
 * that aborts halfway through a firmware image each produce a distinct line -
 * with the client's address, so it is clear which of the three devices on the
 * bench it was.
 */
@Composable
internal fun LogTab(viewModel: FileServerViewModel) {
    val events by viewModel.transferLog.events.collectAsStateWithLifecycle()
    val totals by viewModel.transferLog.totals.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Follows the tail as lines arrive. Scrolling to the newest is what a log
    // is read for; having to chase it downwards while a transfer runs is not.
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResourceTotals(totals.bytesSent, totals.bytesReceived),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::clearLog) {
                Text(androidx.compose.ui.res.stringResource(R.string.log_clear))
            }
        }
        HorizontalDivider()

        if (events.isEmpty()) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.log_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(32.dp),
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(events) { event -> EventRow(event) }
            }
        }
    }
}

@Composable
private fun stringResourceTotals(sent: Long, received: Long): String =
    androidx.compose.ui.res.stringResource(
        R.string.log_totals,
        formatBytes(sent),
        formatBytes(received),
    )

@Composable
private fun EventRow(event: TransferEvent) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTimeOfDay(event.atMillis),
                style = MonospaceTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "  ${event.protocol.label()}  ",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = event.clientAddress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Text(
            text = logMessageText(event.message),
            style = MaterialTheme.typography.bodyMedium,
            color = when (event.level) {
                LogLevel.ERROR -> MaterialTheme.colorScheme.error
                LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
            },
        )

        event.path?.let { path ->
            Text(
                text = "${event.direction.arrow()}$path",
                style = MonospaceTextStyle,
                maxLines = 1,
            )
        }

        // Size and rate only for a completed transfer, and the rate only when
        // it was long enough to mean something. A 40-byte file finishing in a
        // millisecond would otherwise be reported at 40 MB/s.
        val bytes = event.bytes
        val duration = event.durationMillis
        if (bytes != null && duration != null) {
            Text(
                text = androidx.compose.ui.res.stringResource(
                    R.string.log_throughput,
                    formatBytes(bytes),
                    formatDuration(duration),
                    event.throughputBytesPerSecond?.let { formatRate(it) } ?: "—",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

private fun Protocol.label(): String = when (this) {
    Protocol.TFTP -> "TFTP"
    Protocol.FTP -> "FTP"
    Protocol.SFTP -> "SFTP"
    Protocol.SCP -> "SCP"
}

private fun TransferDirection.arrow(): String = when (this) {
    TransferDirection.UPLOAD -> "↑ "
    TransferDirection.DOWNLOAD -> "↓ "
    TransferDirection.NONE -> ""
}

/** Kept next to the log so the cap and the reason for it stay together. */
internal const val LOG_CAPACITY = TransferLog.MAX_EVENTS
