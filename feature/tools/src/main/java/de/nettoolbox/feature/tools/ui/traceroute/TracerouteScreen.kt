package de.nettoolbox.feature.tools.ui.traceroute

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import de.nettoolbox.core.ui.export.exportFileName
import de.nettoolbox.core.ui.export.rememberFileExporter
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.feature.tools.R
import de.nettoolbox.feature.tools.domain.traceroute.TracerouteHop
import java.util.Locale

@Composable
fun TracerouteScreen(
    modifier: Modifier = Modifier,
    viewModel: TracerouteViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val exporter = rememberFileExporter(content = viewModel::asPlainText)

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = uiState.target,
            onValueChange = viewModel::onTargetChange,
            label = { Text(stringResource(R.string.traceroute_target_label)) },
            placeholder = { Text("1.1.1.1") },
            singleLine = true,
            enabled = !uiState.isRunning,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { if (uiState.isRunning) viewModel.stop() else viewModel.start() },
            enabled = uiState.isRunning || uiState.canStart,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(stringResource(if (uiState.isRunning) R.string.action_stop else R.string.action_start))
        }

        Text(
            text = stringResource(R.string.traceroute_native_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        uiState.error?.let { error ->
            Text(
                text = errorTitle(error),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = errorHint(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
            items(uiState.hops) { hop -> HopRow(hop) }
        }

        if (uiState.hops.isNotEmpty()) {
            ResultActionBar(
                plainText = viewModel.asPlainText(),
                onExport = { exporter.export(exportFileName("traceroute", "txt")) },
            )
        }
    }
}

@Composable
private fun HopRow(hop: TracerouteHop) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = hop.ttl.toString(),
            style = MonospaceTextStyle,
            modifier = Modifier.width(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hop.address ?: "*",
                style = MonospaceTextStyle,
                color = if (hop.address == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (hop.isDestination) {
                Text(
                    text = stringResource(R.string.traceroute_destination),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (hop.isUnreachable) {
                Text(
                    text = stringResource(R.string.traceroute_unreachable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(
            text = hop.rttMillis?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: "*",
            style = MonospaceTextStyle,
        )
    }
    HorizontalDivider()
}
