package de.nettoolbox.feature.wifi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.datastore.SettingsRepository
import de.nettoolbox.core.permissions.PermissionCoordinator
import de.nettoolbox.feature.wifi.data.ScanRequestResult
import de.nettoolbox.feature.wifi.data.WifiConnection
import de.nettoolbox.feature.wifi.data.WifiConnectionRepository
import de.nettoolbox.feature.wifi.data.WifiScanRepository
import de.nettoolbox.feature.wifi.domain.ScanThrottle
import de.nettoolbox.feature.wifi.domain.SignalHistoryTracker
import de.nettoolbox.feature.wifi.domain.SignalSample
import de.nettoolbox.feature.wifi.domain.WifiBand
import de.nettoolbox.feature.wifi.domain.WifiChannels
import de.nettoolbox.feature.wifi.domain.WifiNetwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class WifiSortOrder { SIGNAL, SSID, CHANNEL }

enum class WifiViewMode { NETWORKS, CHANNELS, HISTORY, CONNECTION }

data class WifiUiState(
    val mode: WifiViewMode = WifiViewMode.NETWORKS,
    val networks: List<WifiNetwork> = emptyList(),
    val query: String = "",
    val bandFilter: WifiBand? = null,
    val sortOrder: WifiSortOrder = WifiSortOrder.SIGNAL,
    val diagramBand: WifiBand = WifiBand.BAND_2_4_GHZ,
    val watchedBssids: Set<String> = emptySet(),
    val onlyWatched: Boolean = false,
    val history: Map<String, List<SignalSample>> = emptyMap(),
    val connection: WifiConnection? = null,
    val wifiEnabled: Boolean = true,
    val throttleSecondsRemaining: Int = 0,
    val scanUnavailable: Boolean = false,
) {
    val visibleNetworks: List<WifiNetwork>
        get() = networks
            .filter { bandFilter == null || it.band == bandFilter }
            .filter { !onlyWatched || it.bssid in watchedBssids }
            .filter { network ->
                query.isBlank() ||
                    network.ssid?.contains(query, ignoreCase = true) == true ||
                    network.bssid.contains(query, ignoreCase = true)
            }
            .let { filtered ->
                when (sortOrder) {
                    WifiSortOrder.SIGNAL -> filtered.sortedByDescending { it.rssiDbm }
                    WifiSortOrder.SSID -> filtered.sortedBy { it.displayName.lowercase() }
                    WifiSortOrder.CHANNEL -> filtered.sortedBy { it.channel ?: Int.MAX_VALUE }
                }
            }

    val canScanNow: Boolean get() = throttleSecondsRemaining == 0

    /**
     * Which BSSIDs the history chart draws: the watchlist, or the three strongest
     * networks when nothing is watched, so the chart is never empty for no reason.
     */
    val chartedBssids: List<String>
        get() = watchedBssids.toList().ifEmpty {
            networks.sortedByDescending { it.rssiDbm }.take(3).map { it.bssid }
        }

    val recommended2_4Channel: Int?
        get() = networks
            .filter { it.band == WifiBand.BAND_2_4_GHZ }
            .mapNotNull { network -> network.channel?.let { it to network.rssiDbm } }
            .takeIf { it.isNotEmpty() }
            ?.let { WifiChannels.recommend2_4Channel(it) }

    fun ssidOf(bssid: String): String =
        networks.firstOrNull { it.bssid == bssid }?.displayName ?: bssid
}

@HiltViewModel
class WifiViewModel @Inject constructor(
    private val scanRepository: WifiScanRepository,
    private val connectionRepository: WifiConnectionRepository,
    private val historyTracker: SignalHistoryTracker,
    private val settingsRepository: SettingsRepository,
    /** Exposed so the screen can gate itself; the coordinator is stateless. */
    val permissionCoordinator: PermissionCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WifiUiState())
    val uiState: StateFlow<WifiUiState> = _uiState.asStateFlow()

    private var collecting = false

    /**
     * Started from the screen once the permission is granted, not in `init`:
     * without location permission the platform returns an empty list, and a list
     * collected too early would show "no networks" instead of the permission gate.
     */
    fun start() {
        if (collecting) return
        collecting = true

        viewModelScope.launch {
            scanRepository.observeScanResults().collect { networks ->
                historyTracker.record(networks)
                _uiState.update { current ->
                    current.copy(
                        networks = networks,
                        wifiEnabled = scanRepository.isWifiEnabled(),
                        connection = connectionRepository.current(),
                        history = current.chartedBssidsFor(networks)
                            .associateWith { historyTracker.historyOf(it) },
                    )
                }
            }
        }

        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(watchedBssids = settings.watchedBssids) }
            }
        }

        viewModelScope.launch { tickThrottle() }

        requestScan()
    }

    fun onModeChange(mode: WifiViewMode) = _uiState.update { it.copy(mode = mode) }

    fun onQueryChange(value: String) = _uiState.update { it.copy(query = value) }

    fun onBandFilterChange(band: WifiBand?) = _uiState.update { it.copy(bandFilter = band) }

    fun onSortOrderChange(order: WifiSortOrder) = _uiState.update { it.copy(sortOrder = order) }

    fun onDiagramBandChange(band: WifiBand) = _uiState.update { it.copy(diagramBand = band) }

    fun onOnlyWatchedChange(value: Boolean) = _uiState.update { it.copy(onlyWatched = value) }

    fun onToggleWatch(bssid: String) {
        viewModelScope.launch {
            settingsRepository.update { settings ->
                val watched = settings.watchedBssids.toMutableSet()
                if (!watched.remove(bssid)) watched += bssid
                settings.copy(watchedBssids = watched)
            }
        }
    }

    fun requestScan() {
        when (val result = scanRepository.requestScan()) {
            is ScanRequestResult.Started ->
                _uiState.update { it.copy(scanUnavailable = false) }

            is ScanRequestResult.Throttled -> _uiState.update {
                it.copy(
                    throttleSecondsRemaining = (result.retryAfterMillis / 1000).toInt() + 1,
                    scanUnavailable = false,
                )
            }

            ScanRequestResult.Unavailable ->
                _uiState.update { it.copy(scanUnavailable = true) }
        }
        updateThrottle()
    }

    /**
     * Recomputes the countdown once a second.
     *
     * The countdown is the honest part of this screen: the platform limit cannot
     * be worked around, so the UI says when the next scan is possible instead of
     * offering a button that quietly does nothing.
     */
    private suspend fun tickThrottle() {
        while (true) {
            updateThrottle()
            delay(1_000)
        }
    }

    private fun updateThrottle() {
        val now = System.currentTimeMillis()
        val wait = ScanThrottle.millisUntilNextScan(scanRepository.recentScanTimestamps, now)
        val seconds = if (wait <= 0) 0 else (wait / 1000).toInt() + 1
        _uiState.update { it.copy(throttleSecondsRemaining = seconds) }
    }

    private fun WifiUiState.chartedBssidsFor(networks: List<WifiNetwork>): List<String> =
        watchedBssids.toList().ifEmpty {
            networks.sortedByDescending { it.rssiDbm }.take(3).map { it.bssid }
        }
}
