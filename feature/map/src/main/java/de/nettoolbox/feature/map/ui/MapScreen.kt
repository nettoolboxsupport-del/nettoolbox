package de.nettoolbox.feature.map.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import de.nettoolbox.core.ui.error.errorHint
import de.nettoolbox.core.ui.error.errorTitle
import de.nettoolbox.feature.map.R

@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    // OpenDocument rather than a path: the app needs no storage permission and
    // reads nothing it was not explicitly handed.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::onImportCsv) }

    Box(modifier = modifier.fillMaxSize()) {
        MapLibreMapView(
            samplesGeoJson = uiState.samplesGeoJson,
            knownCellsGeoJson = uiState.knownCellsGeoJson,
            showSamples = uiState.showSamples,
            showKnownCells = uiState.showKnownCells,
            modifier = Modifier.fillMaxSize(),
        )

        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        FilterChip(
                            selected = uiState.showSamples,
                            onClick = { viewModel.onShowSamplesChange(!uiState.showSamples) },
                            label = { Text(stringResource(R.string.map_layer_samples)) },
                        )
                        FilterChip(
                            selected = uiState.showKnownCells,
                            onClick = { viewModel.onShowKnownCellsChange(!uiState.showKnownCells) },
                            label = { Text(stringResource(R.string.map_layer_known_cells)) },
                        )
                    }

                    if (sessions.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 8.dp),
                        ) {
                            sessions.take(MAX_SESSION_CHIPS).forEach { session ->
                                FilterChip(
                                    selected = uiState.sessionId == session.id,
                                    onClick = { viewModel.onSessionSelected(session.id) },
                                    label = { Text(session.name) },
                                )
                            }
                        }
                    }

                    Text(
                        text = stringResource(
                            R.string.map_status,
                            uiState.sampleCount,
                            uiState.knownCellCount,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    if (uiState.isDownsampled) {
                        // Said out loud: a thinned-out track is not the raw data.
                        Text(
                            text = stringResource(
                                R.string.map_downsampled,
                                uiState.displayedSampleCount,
                                uiState.sampleCount,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (uiState.isImporting) {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.map_importing, uiState.importedCount),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }

            uiState.error?.let { error ->
                Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = errorTitle(error),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = errorHint(error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        ) {
            Row {
                TextButton(onClick = { importLauncher.launch(arrayOf("text/*", "*/*")) }) {
                    Text(stringResource(R.string.map_import_csv))
                }
                if (uiState.knownCellCount > 0) {
                    TextButton(onClick = viewModel::onClearImported) {
                        Text(stringResource(R.string.map_clear_imported))
                    }
                }
            }

            // Attribution is a licence obligation, not decoration: OSM tiles and
            // OpenCelliD data both require it (spec section 9).
            Text(
                text = stringResource(R.string.map_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

private const val MAX_SESSION_CHIPS = 6
