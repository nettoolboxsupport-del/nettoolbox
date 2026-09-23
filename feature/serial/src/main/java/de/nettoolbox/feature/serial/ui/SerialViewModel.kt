package de.nettoolbox.feature.serial.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.feature.serial.data.SerialConsoleManager
import de.nettoolbox.feature.serial.data.SerialSettingsStore
import de.nettoolbox.feature.serial.data.UsbSerialDevices
import de.nettoolbox.feature.serial.domain.SerialDevice
import de.nettoolbox.feature.serial.domain.SerialPreset
import de.nettoolbox.feature.serial.domain.SerialSessionState
import de.nettoolbox.feature.serial.domain.SerialSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin on purpose. The session itself lives in [SerialConsoleManager], which
 * outlives this screen - see its documentation for why. This class only adapts
 * it for Compose and keeps the device list current.
 */
@HiltViewModel
class SerialViewModel @Inject constructor(
    private val manager: SerialConsoleManager,
    private val devices: UsbSerialDevices,
    settingsStore: SerialSettingsStore,
) : ViewModel() {

    val session: StateFlow<SerialSessionState> = manager.state
    val notice = manager.notice
    val rxBytes = manager.rxBytes
    val txBytes = manager.txBytes
    val isPasting = manager.isPasting
    val supportedFlowControl = manager.supportedFlowControl

    val terminalHandle: Long? get() = manager.terminal.handle
    val redrawTrigger = manager.terminal.redrawTrigger
    val ctrlActive = manager.terminal.ctrlActive
    val altActive = manager.terminal.altActive

    val hostSupported: Boolean get() = devices.hostSupported

    val settings: StateFlow<SerialSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SerialSettings())

    private val _devices = MutableStateFlow<List<SerialDevice>>(emptyList())
    val deviceList: StateFlow<List<SerialDevice>> = _devices.asStateFlow()

    /** Opens on the console when a session is already running - that is why the user came back. */
    private val _selectedTab = MutableStateFlow(if (manager.isConnected) TAB_CONSOLE else TAB_CONNECT)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    init {
        refreshDevices()
        viewModelScope.launch {
            devices.changes.collect { refreshDevices() }
        }
    }

    fun selectTab(tab: Int) {
        _selectedTab.value = tab
    }

    fun refreshDevices() {
        _devices.value = devices.scan()
    }

    fun connect(device: SerialDevice, portIndex: Int = 0) {
        viewModelScope.launch {
            manager.connect(device.deviceName, portIndex)
            if (manager.isConnected) _selectedTab.value = TAB_CONSOLE
            // The permission dialog may just have made the product name
            // readable, so the list is worth refreshing either way.
            refreshDevices()
        }
    }

    /** Reconnects to the device a session just ended on, with the current settings. */
    fun reconnect() {
        val ended = session.value as? SerialSessionState.Ended ?: return
        val device = _devices.value.firstOrNull { it.deviceName == ended.device.deviceName } ?: return
        connect(device)
    }

    fun disconnect() = manager.disconnect()

    fun updateSettings(transform: (SerialSettings) -> SerialSettings) {
        viewModelScope.launch { manager.updateSettings(transform) }
    }

    /** A preset sets line speed and 8N1, and leaves every other preference alone. */
    fun applyPreset(preset: SerialPreset) = updateSettings {
        it.copy(
            baudRate = preset.baudRate,
            dataBits = 8,
            parity = de.nettoolbox.feature.serial.domain.SerialParity.NONE,
            stopBits = de.nettoolbox.feature.serial.domain.SerialStopBits.ONE,
        )
    }

    fun typeChar(char: Char) = manager.typeChar(char)

    fun typeKey(key: Int) = manager.typeKey(key)

    fun toggleCtrl() = manager.terminal.toggleCtrl()

    fun toggleAlt() = manager.terminal.toggleAlt()

    /** A serial line has no window-size negotiation; only the local screen changes. */
    fun onResize(rows: Int, cols: Int) {
        manager.terminal.resize(rows, cols)
    }

    fun sendBreak() = manager.sendBreak()

    fun paste(text: String) = manager.paste(text)

    fun cancelPaste() = manager.cancelPaste()

    fun clearScreen() = manager.clearScreen()

    fun dismissNotice() = manager.dismissNotice()

    companion object {
        const val TAB_CONNECT = 0
        const val TAB_CONSOLE = 1
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
