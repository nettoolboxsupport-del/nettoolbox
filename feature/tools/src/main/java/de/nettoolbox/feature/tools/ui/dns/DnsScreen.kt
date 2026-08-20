package de.nettoolbox.feature.tools.ui.dns

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import de.nettoolbox.feature.tools.domain.dns.DnsLookupResult
import de.nettoolbox.feature.tools.domain.dns.DnsRecordType

@Composable
fun DnsScreen(
    modifier: Modifier = Modifier,
    viewModel: DnsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val exporter = rememberFileExporter(content = viewModel::asPlainText)

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = uiState.name,
            onValueChange = viewModel::onNameChange,
            label = { Text(stringResource(R.string.dns_name_label)) },
            placeholder = { Text("example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
        ) {
            DnsRecordType.entries.forEach { type ->
                FilterChip(
                    selected = uiState.type == type,
                    onClick = { viewModel.onTypeChange(type) },
                    label = { Text(type.name) },
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
        ) {
            uiState.availableTargets.forEach { target ->
                FilterChip(
                    selected = target.label in uiState.selectedTargets,
                    onClick = { viewModel.onToggleTarget(target) },
                    label = { Text(target.label) },
                )
            }
        }

        uiState.systemConfiguration?.let { configuration ->
            if (configuration.privateDnsActive) {
                Text(
                    text = stringResource(
                        R.string.dns_private_active,
                        configuration.privateDnsServerName ?: "-",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
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
                    if (uiState.isRunning) R.string.action_stop else R.string.dns_lookup,
                ),
            )
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
            items(uiState.results) { result -> ResolverResultCard(result) }
        }

        if (uiState.results.isNotEmpty()) {
            ResultActionBar(
                plainText = viewModel.asPlainText(),
                onExport = { exporter.export(exportFileName("dns", "txt")) },
            )
        }
    }
}

@Composable
private fun ResolverResultCard(result: DnsLookupResult) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = "${result.target.label} · ${result.target.kind} · ${result.elapsedMillis} ms",
                style = MaterialTheme.typography.titleSmall,
            )

            val error = result.error
            val response = result.response

            when {
                error != null -> {
                    Text(
                        text = errorTitle(error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = errorHint(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                response == null -> Text(
                    text = stringResource(R.string.dns_no_answer),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )

                else -> {
                    Text(
                        text = buildString {
                            append(response.responseCode.name)
                            if (response.authoritative) append(" · AA")
                            if (response.truncated) append(" · TC")
                            if (response.authenticatedData) append(" · AD")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    if (response.answers.isEmpty()) {
                        Text(
                            text = stringResource(R.string.dns_no_records),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    response.answers.forEach { record ->
                        Text(
                            text = "${record.type?.name ?: record.typeCode}  ${record.data}",
                            style = MonospaceTextStyle,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = stringResource(R.string.dns_ttl, record.ttlSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
