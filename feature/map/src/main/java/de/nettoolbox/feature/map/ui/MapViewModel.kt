package de.nettoolbox.feature.map.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.common.result.Outcome
import de.nettoolbox.core.database.dao.CellSampleDao
import de.nettoolbox.core.database.dao.KnownCellDao
import de.nettoolbox.core.database.dao.MeasurementSessionDao
import de.nettoolbox.core.database.entity.CellSampleEntity
import de.nettoolbox.feature.map.data.KnownCellImporter
import de.nettoolbox.feature.map.domain.MapGeoJson
import de.nettoolbox.feature.map.navigation.MapRoute
import de.nettoolbox.feature.map.navigation.NO_SESSION_ID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

data class MapUiState(
    val sessionId: Long = NO_SESSION_ID,
    val sessionName: String? = null,
    val samplesGeoJson: String = EMPTY_FEATURE_COLLECTION,
    val knownCellsGeoJson: String = EMPTY_FEATURE_COLLECTION,
    val showSamples: Boolean = true,
    val showKnownCells: Boolean = true,
    val sampleCount: Int = 0,
    val displayedSampleCount: Int = 0,
    val knownCellCount: Int = 0,
    val isImporting: Boolean = false,
    val importedCount: Int = 0,
    val error: NetToolboxError? = null,
) {
    /** True when the display had to be thinned out, which the UI says out loud. */
    val isDownsampled: Boolean get() = displayedSampleCount in 1 until sampleCount
}

@HiltViewModel
class MapViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val cellSampleDao: CellSampleDao,
    private val knownCellDao: KnownCellDao,
    private val sessionDao: MeasurementSessionDao,
    private val importer: KnownCellImporter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    val sessions = sessionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        val sessionId = runCatching { savedStateHandle.toRoute<MapRoute>().sessionId }
            .getOrDefault(NO_SESSION_ID)

        viewModelScope.launch {
            _uiState.update { it.copy(knownCellCount = importer.count()) }
            if (sessionId != NO_SESSION_ID) loadSession(sessionId)
        }
    }

    fun onSessionSelected(sessionId: Long) {
        viewModelScope.launch { loadSession(sessionId) }
    }

    fun onShowSamplesChange(value: Boolean) = _uiState.update { it.copy(showSamples = value) }

    fun onShowKnownCellsChange(value: Boolean) = _uiState.update { it.copy(showKnownCells = value) }

    private suspend fun loadSession(sessionId: Long) {
        val samples = cellSampleDao.bySession(sessionId)
        val positioned = samples.filter { it.lat != null && it.lon != null }
        val displayed = MapGeoJson.downsample(positioned, MapGeoJson.MAX_DISPLAYED_POINTS)

        _uiState.update {
            it.copy(
                sessionId = sessionId,
                sessionName = sessionDao.findById(sessionId)?.name,
                samplesGeoJson = MapGeoJson.samplesToGeoJson(samples),
                sampleCount = positioned.size,
                displayedSampleCount = displayed.size,
            )
        }

        loadKnownCellsAround(positioned)
    }

    /**
     * Known cells are loaded for the bounding box of the recorded track, not for
     * the whole database: an imported country dump is millions of rows, and
     * pushing all of them into a GeoJSON string would be the end of the frame
     * budget - and of the map.
     */
    private suspend fun loadKnownCellsAround(samples: List<CellSampleEntity>) {
        val latitudes = samples.mapNotNull { it.lat }
        val longitudes = samples.mapNotNull { it.lon }
        if (latitudes.isEmpty() || longitudes.isEmpty()) return

        val cells = knownCellDao.inBounds(
            minLat = latitudes.min() - BOUNDS_PADDING_DEGREES,
            maxLat = latitudes.max() + BOUNDS_PADDING_DEGREES,
            minLon = longitudes.min() - BOUNDS_PADDING_DEGREES,
            maxLon = longitudes.max() + BOUNDS_PADDING_DEGREES,
            limit = MAX_KNOWN_CELLS,
        )

        _uiState.update { it.copy(knownCellsGeoJson = MapGeoJson.knownCellsToGeoJson(cells)) }
    }

    fun onImportCsv(uri: Uri) {
        _uiState.update { it.copy(isImporting = true, importedCount = 0, error = null) }

        viewModelScope.launch {
            when (val result = importer.importCsv(uri) { imported ->
                _uiState.update { it.copy(importedCount = imported) }
            }) {
                is Outcome.Success -> _uiState.update {
                    it.copy(isImporting = false, knownCellCount = importer.count())
                }

                is Outcome.Failure -> _uiState.update {
                    it.copy(isImporting = false, error = result.error)
                }

                Outcome.Loading -> Unit
            }
        }
    }

    fun onClearImported() {
        viewModelScope.launch {
            importer.clearImported()
            _uiState.update {
                it.copy(
                    knownCellCount = importer.count(),
                    knownCellsGeoJson = EMPTY_FEATURE_COLLECTION,
                )
            }
        }
    }

    private companion object {
        /** About a kilometre, so sites just off the route still show up. */
        const val BOUNDS_PADDING_DEGREES = 0.01

        const val MAX_KNOWN_CELLS = 5_000
    }
}
