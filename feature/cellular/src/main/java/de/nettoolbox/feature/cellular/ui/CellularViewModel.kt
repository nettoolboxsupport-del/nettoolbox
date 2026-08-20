package de.nettoolbox.feature.cellular.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.datastore.SettingsRepository
import de.nettoolbox.core.permissions.PermissionCoordinator
import de.nettoolbox.feature.cellular.data.TelephonyRepository
import de.nettoolbox.feature.cellular.domain.CellularSnapshot
import de.nettoolbox.feature.cellular.domain.SubscriptionInfo
import de.nettoolbox.feature.cellular.service.DriveTestController
import de.nettoolbox.feature.cellular.service.DriveTestState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CellularUiState(
    val subscriptions: List<SubscriptionInfo> = emptyList(),
    val selectedSubscriptionId: Int? = null,
    val snapshot: CellularSnapshot? = null,
    /** Rolling RSRP window for the chart, oldest first. */
    val rsrpHistory: List<Float> = emptyList(),
    val sampleIntervalSeconds: Int = 2,
    val isMultiSim: Boolean = false,
)

@HiltViewModel
class CellularViewModel @Inject constructor(
    private val telephonyRepository: TelephonyRepository,
    private val settingsRepository: SettingsRepository,
    private val driveTestController: DriveTestController,
    val permissionCoordinator: PermissionCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CellularUiState())
    val uiState: StateFlow<CellularUiState> = _uiState.asStateFlow()

    /** Owned by the service, so it survives rotation and this ViewModel's death. */
    val driveTestState: StateFlow<DriveTestState> = driveTestController.state

    private val _sessionName = MutableStateFlow("")
    val sessionName: StateFlow<String> = _sessionName.asStateFlow()

    fun onSessionNameChange(value: String) {
        _sessionName.value = value
    }

    fun startLogging() = driveTestController.start(_sessionName.value)

    fun stopLogging() = driveTestController.stop()

    private var observeJob: Job? = null
    private var pollJob: Job? = null
    private var started = false

    /**
     * Started from the screen once the permissions are granted: without fine
     * location Android reports cell identity as unavailable rather than failing,
     * and a pipeline started too early would show an empty serving cell.
     */
    fun start() {
        if (started) return
        started = true

        viewModelScope.launch {
            val interval = settingsRepository.settings.first().cellSampleIntervalSeconds
            val subscriptions = telephonyRepository.subscriptions()
            val selected = subscriptions.firstOrNull {
                it.subscriptionId == telephonyRepository.defaultSubscriptionId()
            } ?: subscriptions.firstOrNull()

            _uiState.update {
                it.copy(
                    subscriptions = subscriptions,
                    selectedSubscriptionId = selected?.subscriptionId,
                    isMultiSim = subscriptions.size > 1,
                    sampleIntervalSeconds = interval,
                )
            }

            selected?.subscriptionId?.let { observe(it) }
        }
    }

    fun onSubscriptionSelected(subscriptionId: Int) {
        if (_uiState.value.selectedSubscriptionId == subscriptionId) return
        _uiState.update {
            it.copy(selectedSubscriptionId = subscriptionId, snapshot = null, rsrpHistory = emptyList())
        }
        observe(subscriptionId)
    }

    fun refreshNow() {
        val subscriptionId = _uiState.value.selectedSubscriptionId ?: return
        telephonyRepository.requestUpdate(subscriptionId) { }
    }

    private fun observe(subscriptionId: Int) {
        observeJob?.cancel()
        pollJob?.cancel()

        observeJob = viewModelScope.launch {
            telephonyRepository.observe(subscriptionId)
                // The modem can fire faster than the UI can draw; conflate keeps
                // the newest snapshot instead of queueing stale ones.
                .conflate()
                .collect { snapshot ->
                    _uiState.update { current ->
                        current.copy(
                            snapshot = snapshot,
                            rsrpHistory = current.rsrpHistory.appendRsrp(snapshot),
                        )
                    }
                }
        }

        // From Android 10 the cached list only refreshes at the platform's pace,
        // so an explicit update request is the only way to get a live reading.
        pollJob = viewModelScope.launch {
            while (true) {
                telephonyRepository.requestUpdate(subscriptionId) { }
                delay(_uiState.value.sampleIntervalSeconds.coerceIn(1, 10) * 1000L)
            }
        }
    }

    private fun List<Float>.appendRsrp(snapshot: CellularSnapshot): List<Float> {
        val rsrp = snapshot.serving?.metrics?.rsrp?.toFloat() ?: return this
        return (this + rsrp).takeLast(MAX_HISTORY_SAMPLES)
    }

    override fun onCleared() {
        super.onCleared()
        observeJob?.cancel()
        pollJob?.cancel()
    }

    private companion object {
        /** 300 samples at a 1 s interval is the 5-minute window from the spec. */
        const val MAX_HISTORY_SAMPLES = 300
    }
}
