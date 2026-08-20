package de.nettoolbox.feature.tools.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import de.nettoolbox.core.database.entity.ToolRunEntity
import de.nettoolbox.core.ui.component.EmptyState
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.feature.tools.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ToolHistoryScreen(
    onRepeat: (ToolRunEntity) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ToolHistoryViewModel = hiltViewModel(),
) {
    val runs by viewModel.runs.collectAsStateWithLifecycle()

    if (runs.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.history_empty_title),
            description = stringResource(R.string.history_empty_description),
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = viewModel::clear) {
                Text(stringResource(R.string.history_clear))
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(runs) { run ->
                HistoryCard(
                    run = run,
                    onRepeat = { onRepeat(run) },
                    onDelete = { viewModel.delete(run.id) },
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(
    run: ToolRunEntity,
    onRepeat: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = run.target,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (run.success) R.string.history_success else R.string.history_failed,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (run.success) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Text(
                text = buildString {
                    append(TIMESTAMP_FORMAT.format(Date(run.startedAt)))
                    run.durationMs?.let { append(" · ${it} ms") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = run.paramsJson,
                style = MonospaceTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRepeat) {
                    Text(stringResource(R.string.history_repeat))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.history_delete))
                }
            }
        }
    }
}

private val TIMESTAMP_FORMAT = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
