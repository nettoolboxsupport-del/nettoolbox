package de.nettoolbox.feature.serial.data

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import de.nettoolbox.core.common.di.ApplicationScope
import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.common.di.MainDispatcher
import de.nettoolbox.core.common.storage.ShareStorage
import de.nettoolbox.core.terminal.TerminalEmulator
import de.nettoolbox.feature.serial.domain.LineEnding
import de.nettoolbox.feature.serial.domain.SerialFailure
import de.nettoolbox.feature.serial.domain.SerialFlowControl
import de.nettoolbox.feature.serial.domain.SerialNotice
import de.nettoolbox.feature.serial.domain.SerialParity
import de.nettoolbox.feature.serial.domain.SerialSessionState
import de.nettoolbox.feature.serial.domain.SerialSettings
import de.nettoolbox.feature.serial.domain.SerialStopBits
import de.nettoolbox.feature.serial.domain.SerialText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one serial console session, and the terminal it draws into.
 *
 * ### Why a singleton and not the screen's ViewModel
 *
 * The workflow this feature exists for is: console cable in, TFTP server on,
 * `copy tftp: flash:` typed at the console. Reaching the file server means
 * leaving this screen, and a ViewModel scoped to the screen is cleared when the
 * user navigates back past it - closing the port in the middle of exactly the
 * job the two features are meant to do together. So the session, and the
 * screen contents with it, live here for as long as the process does.
 *
 * ### Threads
 *
 * - The library's [SerialInputOutputManager] reads on its own thread and writes
 *   from its own queue, so keystrokes never block the UI and never overtake
 *   each other.
 * - Everything that touches the terminal runs on the main thread (see
 *   [TerminalEmulator]); received bytes are handed over from the read thread.
 * - The session log is written on a single-threaded dispatcher, which keeps the
 *   bytes in arrival order without holding up either of the above.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SerialConsoleManager @Inject constructor(
    private val devices: UsbSerialDevices,
    private val settingsStore: SerialSettingsStore,
    private val storage: ShareStorage,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) {

    val terminal = TerminalEmulator()

    private val _state = MutableStateFlow<SerialSessionState>(SerialSessionState.Idle)
    val state: StateFlow<SerialSessionState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<SerialNotice?>(null)
    val notice: StateFlow<SerialNotice?> = _notice.asStateFlow()

    private val _rxBytes = MutableStateFlow(0L)
    val rxBytes: StateFlow<Long> = _rxBytes.asStateFlow()

    private val _txBytes = MutableStateFlow(0L)
    val txBytes: StateFlow<Long> = _txBytes.asStateFlow()

    private val _isPasting = MutableStateFlow(false)
    val isPasting: StateFlow<Boolean> = _isPasting.asStateFlow()

    /** Flow control modes the open port can actually do; empty when nothing is open. */
    private val _supportedFlowControl = MutableStateFlow<Set<SerialFlowControl>>(emptySet())
    val supportedFlowControl: StateFlow<Set<SerialFlowControl>> = _supportedFlowControl.asStateFlow()

    /** Serialises log writes so a capture is never reordered. */
    private val logDispatcher = ioDispatcher.limitedParallelism(1)

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var logStream: FileOutputStream? = null
    private var logFile: String? = null
    private var activeSettings: SerialSettings = SerialSettings()
    private var pasteJob: Job? = null

    val isConnected: Boolean get() = _state.value is SerialSessionState.Connected

    // --- session lifecycle --------------------------------------------------

    suspend fun connect(deviceName: String, portIndex: Int = 0) {
        if (isConnected) disconnect()
        if (!terminal.isAvailable) {
            _state.value = SerialSessionState.Failed(SerialFailure.TERMINAL_UNAVAILABLE)
            return
        }

        val listed = devices.scan().firstOrNull { it.deviceName == deviceName }
        val driver = devices.driverFor(deviceName)
        if (listed == null || driver == null) {
            _state.value = SerialSessionState.Failed(SerialFailure.DEVICE_DETACHED)
            return
        }
        _state.value = SerialSessionState.Connecting(listed)

        if (!devices.requestPermission(driver.device)) {
            _state.value = SerialSessionState.Failed(SerialFailure.PERMISSION_DENIED)
            return
        }

        val settings = settingsStore.current()
        val opened = withContext(ioDispatcher) { open(driver.ports.getOrNull(portIndex), driver.device, settings) }
        if (opened is OpenResult.Failed) {
            _state.value = SerialSessionState.Failed(opened.reason, opened.detail)
            return
        }
        opened as OpenResult.Opened

        port = opened.port
        activeSettings = settings
        _supportedFlowControl.value = opened.supportedFlow
        _rxBytes.value = 0
        _txBytes.value = 0
        if (opened.flowFellBack) _notice.value = SerialNotice.FLOW_CONTROL_UNSUPPORTED

        ioManager = SerialInputOutputManager(opened.port, listener).also { it.start() }
        if (settings.logToFile) openLog()

        // Re-read after the permission grant: the product name is only
        // readable now, and it is what the status line shows.
        val described = devices.scan().firstOrNull { it.deviceName == deviceName } ?: listed
        _state.value = SerialSessionState.Connected(described, settings, logFile)
    }

    fun disconnect() {
        val device = (_state.value as? SerialSessionState.Connected)?.device
        tearDown()
        _state.value = if (device != null) {
            SerialSessionState.Ended(reason = null, device = device)
        } else {
            SerialSessionState.Idle
        }
    }

    private sealed interface OpenResult {
        data class Opened(
            val port: UsbSerialPort,
            val supportedFlow: Set<SerialFlowControl>,
            val flowFellBack: Boolean,
        ) : OpenResult

        data class Failed(val reason: SerialFailure, val detail: String?) : OpenResult
    }

    private fun open(
        port: UsbSerialPort?,
        device: android.hardware.usb.UsbDevice,
        settings: SerialSettings,
    ): OpenResult {
        if (port == null) return OpenResult.Failed(SerialFailure.OPEN_FAILED, null)
        val connection = devices.openConnection(device)
            ?: return OpenResult.Failed(SerialFailure.OPEN_FAILED, null)
        try {
            port.open(connection)
        } catch (io: IOException) {
            connection.close()
            return OpenResult.Failed(SerialFailure.OPEN_FAILED, io.message)
        }

        return try {
            port.setParameters(
                settings.baudRate,
                settings.dataBits,
                settings.stopBits.toPort(),
                settings.parity.toPort(),
            )
            val supported = supportedFlowOf(port)
            val fellBack = applyFlowControl(port, settings.flowControl, supported)
            applyControlLines(port, settings)
            OpenResult.Opened(port, supported, fellBack)
        } catch (failure: Exception) {
            // IOException from the transfer, or IllegalArgumentException /
            // UnsupportedOperationException from the driver refusing the
            // values - for the user all three mean "this adapter will not do
            // those settings", and the port is released either way.
            runCatching { port.close() }
            OpenResult.Failed(SerialFailure.UNSUPPORTED_PARAMETERS, failure.message)
        }
    }

    /**
     * Applies changed settings to the open port without reconnecting.
     *
     * The common case is garbage on screen because the baud rate is wrong:
     * switching it live is the fix, and making the user reconnect for it would
     * lose whatever the device printed meanwhile. The new settings are always
     * persisted; they are applied only if a port is open.
     */
    suspend fun updateSettings(transform: (SerialSettings) -> SerialSettings) {
        settingsStore.update(transform)
        val current = port ?: return
        val updated = settingsStore.current()
        val previous = activeSettings
        activeSettings = updated

        withContext(ioDispatcher) {
            try {
                if (updated.baudRate != previous.baudRate || updated.dataBits != previous.dataBits ||
                    updated.parity != previous.parity || updated.stopBits != previous.stopBits
                ) {
                    current.setParameters(
                        updated.baudRate,
                        updated.dataBits,
                        updated.stopBits.toPort(),
                        updated.parity.toPort(),
                    )
                }
                if (updated.flowControl != previous.flowControl &&
                    applyFlowControl(current, updated.flowControl, _supportedFlowControl.value)
                ) {
                    _notice.value = SerialNotice.FLOW_CONTROL_UNSUPPORTED
                }
                if (updated.dtr != previous.dtr || updated.rts != previous.rts) {
                    applyControlLines(current, updated)
                }
            } catch (failure: Exception) {
                Log.w(TAG, "live settings change refused", failure)
            }
        }

        if (updated.logToFile != previous.logToFile) {
            if (updated.logToFile) openLog() else closeLog()
        }

        (_state.value as? SerialSessionState.Connected)?.let { connected ->
            _state.value = connected.copy(settings = updated, logFile = logFile)
        }
    }

    // --- keyboard and sending -----------------------------------------------

    /** Main thread. A typed character goes through the terminal, then onto the wire. */
    fun typeChar(char: Char) = transmitKeyboard(terminal.typeChar(char))

    /** Main thread. A special key from the extra-key bar or a hardware keyboard. */
    fun typeKey(key: Int) = transmitKeyboard(terminal.typeKey(key))

    private fun transmitKeyboard(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val settings = activeSettings
        // libvterm sends CR for Enter, which is right for almost everything.
        // Rewritten only when the user chose otherwise.
        val outgoing = SerialText.rewriteEnter(bytes, settings.enterSends)
        if (!send(outgoing)) return
        if (settings.localEcho) {
            // CR alone would return to the start of the same line; a local
            // echo has to move down as well to look like what was typed.
            terminal.feed(SerialText.rewriteEnter(bytes, LineEnding.CRLF))
        }
    }

    private fun send(bytes: ByteArray): Boolean {
        val manager = ioManager ?: return false
        manager.writeAsync(bytes)
        _txBytes.update { it + bytes.size }
        return true
    }

    /**
     * Pastes text line by line, with a pause after each.
     *
     * Pasting a configuration into a console at full speed is the classic way
     * to lose half of it: the device reads characters faster than it parses
     * commands, the input buffer overflows, and whole lines disappear without
     * an error. The pause gives the device time to finish each command.
     *
     * A final line without a trailing newline is sent without Enter, the way a
     * terminal pastes: the user can still look at it before running it.
     */
    fun paste(text: String) {
        if (!isConnected) return
        val lines = SerialText.pasteLines(text)
        if (lines.isEmpty()) {
            _notice.value = SerialNotice.PASTE_EMPTY
            return
        }
        pasteJob?.cancel()
        pasteJob = appScope.launch(mainDispatcher) {
            _isPasting.value = true
            try {
                for (line in lines) {
                    val body = line.text.toByteArray(Charsets.UTF_8)
                    val payload = if (line.pressEnter) body + activeSettings.enterSends.bytes else body
                    if (payload.isNotEmpty() && !send(payload)) break
                    if (activeSettings.localEcho) terminal.feed(if (line.pressEnter) body + CRLF else body)
                    if (line.pressEnter) delay(activeSettings.pasteLineDelayMillis.toLong())
                }
                _notice.value = SerialNotice.PASTE_DONE
            } finally {
                _isPasting.value = false
            }
        }
    }

    fun cancelPaste() {
        pasteJob?.cancel()
        pasteJob = null
    }

    /**
     * Holds the line in the break condition for a moment.
     *
     * Not a character but a line state, which is why it cannot be typed. Cisco
     * devices use it to drop into ROMMON during boot - the route to password
     * recovery - and other platforms have equivalents. 300 ms sits inside the
     * 0.25 to 0.5 s that POSIX tcsendbreak() specifies and is recognised
     * everywhere; the library's own example uses 100 ms.
     */
    fun sendBreak() {
        val current = port ?: return
        appScope.launch(ioDispatcher) {
            try {
                current.setBreak(true)
                delay(BREAK_MILLIS)
                current.setBreak(false)
                _notice.value = SerialNotice.BREAK_SENT
            } catch (unsupported: UnsupportedOperationException) {
                _notice.value = SerialNotice.BREAK_UNSUPPORTED
            } catch (io: IOException) {
                Log.w(TAG, "break failed", io)
                _notice.value = SerialNotice.BREAK_UNSUPPORTED
            }
        }
    }

    fun clearScreen() = terminal.clear()

    fun dismissNotice() {
        _notice.value = null
    }

    // --- receiving ----------------------------------------------------------

    private val listener = object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
            // Library read thread. Copied because the buffer may be reused.
            val chunk = data.copyOf()
            _rxBytes.update { it + chunk.size }
            appScope.launch(mainDispatcher) { terminal.feed(chunk) }
            logStream?.let { stream ->
                appScope.launch(logDispatcher) {
                    try {
                        stream.write(chunk)
                    } catch (io: IOException) {
                        _notice.value = SerialNotice.LOG_FAILED
                    }
                }
            }
        }

        override fun onRunError(e: Exception) {
            // Library read thread. Almost always the cable was pulled; the
            // device list tells that apart from a genuine I/O fault.
            val connected = _state.value as? SerialSessionState.Connected ?: return
            val detached = !devices.isAttached(connected.device.deviceName)
            Log.w(TAG, "serial session ended (detached=$detached)", e)
            appScope.launch(mainDispatcher) {
                tearDown()
                _state.value = SerialSessionState.Ended(
                    reason = if (detached) SerialFailure.DEVICE_DETACHED else SerialFailure.IO_ERROR,
                    device = connected.device,
                )
            }
        }
    }

    // --- session log --------------------------------------------------------

    private fun openLog() {
        if (logStream != null) return
        runCatching {
            val directory = File(storage.root, LOG_DIRECTORY).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val file = File(directory, "serial-$stamp.log")
            logStream = FileOutputStream(file, true)
            logFile = storage.relativeOf(file)
        }.onFailure {
            Log.w(TAG, "could not open session log", it)
            _notice.value = SerialNotice.LOG_FAILED
        }
    }

    private fun closeLog() {
        val stream = logStream ?: return
        logStream = null
        logFile = null
        // Through the log dispatcher, so the close queues behind any write
        // still in flight instead of cutting it off.
        appScope.launch(logDispatcher) {
            withContext(NonCancellable) { runCatching { stream.flush(); stream.close() } }
        }
    }

    private fun tearDown() {
        cancelPaste()
        ioManager?.let { manager ->
            manager.setListener(null)
            manager.stop()
        }
        ioManager = null
        port?.let { open -> runCatching { open.close() } }
        port = null
        _supportedFlowControl.value = emptySet()
        closeLog()
    }

    // --- mapping onto the library -------------------------------------------

    private fun supportedFlowOf(port: UsbSerialPort): Set<SerialFlowControl> =
        runCatching { port.getSupportedFlowControl() }.getOrNull()
            ?.mapNotNull { it.toDomain() }
            ?.toSet()
            ?: setOf(SerialFlowControl.NONE)

    /** @return true when the requested mode was unavailable and NONE was used instead. */
    private fun applyFlowControl(
        port: UsbSerialPort,
        wanted: SerialFlowControl,
        supported: Set<SerialFlowControl>,
    ): Boolean {
        val effective = if (wanted in supported) wanted else SerialFlowControl.NONE
        runCatching { port.setFlowControl(effective.toPort()) }
        return effective != wanted
    }

    /**
     * DTR and RTS, best effort.
     *
     * Some drivers do not implement them and throw; that is not a reason to
     * fail a console session, which works without either on almost all gear.
     * With RTS/CTS flow control the driver owns RTS and it is left alone.
     */
    private fun applyControlLines(port: UsbSerialPort, settings: SerialSettings) {
        // Explicit setters rather than Kotlin's synthetic properties: how
        // getDTR()/setDTR() map onto a property name is exactly the kind of
        // rule that differs from what one expects for all-caps acronyms.
        runCatching { port.setDTR(settings.dtr) }
        if (settings.flowControl != SerialFlowControl.RTS_CTS) {
            runCatching { port.setRTS(settings.rts) }
        }
    }

    private fun SerialParity.toPort(): Int = when (this) {
        SerialParity.NONE -> UsbSerialPort.PARITY_NONE
        SerialParity.ODD -> UsbSerialPort.PARITY_ODD
        SerialParity.EVEN -> UsbSerialPort.PARITY_EVEN
        SerialParity.MARK -> UsbSerialPort.PARITY_MARK
        SerialParity.SPACE -> UsbSerialPort.PARITY_SPACE
    }

    private fun SerialStopBits.toPort(): Int = when (this) {
        SerialStopBits.ONE -> UsbSerialPort.STOPBITS_1
        SerialStopBits.ONE_AND_HALF -> UsbSerialPort.STOPBITS_1_5
        SerialStopBits.TWO -> UsbSerialPort.STOPBITS_2
    }

    private fun SerialFlowControl.toPort(): UsbSerialPort.FlowControl = when (this) {
        SerialFlowControl.NONE -> UsbSerialPort.FlowControl.NONE
        SerialFlowControl.RTS_CTS -> UsbSerialPort.FlowControl.RTS_CTS
        SerialFlowControl.DTR_DSR -> UsbSerialPort.FlowControl.DTR_DSR
        SerialFlowControl.XON_XOFF -> UsbSerialPort.FlowControl.XON_XOFF
    }

    /**
     * XON_XOFF_INLINE is deliberately not mapped. It means the chip cannot do
     * software flow control itself and the app has to filter the control
     * characters out of the received stream - a second code path for a mode no
     * network console needs.
     */
    private fun UsbSerialPort.FlowControl.toDomain(): SerialFlowControl? = when (this) {
        UsbSerialPort.FlowControl.NONE -> SerialFlowControl.NONE
        UsbSerialPort.FlowControl.RTS_CTS -> SerialFlowControl.RTS_CTS
        UsbSerialPort.FlowControl.DTR_DSR -> SerialFlowControl.DTR_DSR
        UsbSerialPort.FlowControl.XON_XOFF -> SerialFlowControl.XON_XOFF
        else -> null
    }

    private companion object {
        const val TAG = "NetToolboxSerial"
        const val BREAK_MILLIS = 300L
        const val LOG_DIRECTORY = "Console-Logs"
        val CRLF = byteArrayOf(0x0D, 0x0A)
    }
}
