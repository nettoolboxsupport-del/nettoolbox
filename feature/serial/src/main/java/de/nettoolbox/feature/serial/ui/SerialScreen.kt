package de.nettoolbox.feature.serial.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.core.terminal.TerminalPane
import de.nettoolbox.core.ui.icon.NetToolboxIcons
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.feature.serial.R
import de.nettoolbox.feature.serial.domain.LineEnding
import de.nettoolbox.feature.serial.domain.SerialChip
import de.nettoolbox.feature.serial.domain.SerialDevice
import de.nettoolbox.feature.serial.domain.SerialFailure
import de.nettoolbox.feature.serial.domain.SerialFlowControl
import de.nettoolbox.feature.serial.domain.SerialNotice
import de.nettoolbox.feature.serial.domain.SerialParity
import de.nettoolbox.feature.serial.domain.SerialPreset
import de.nettoolbox.feature.serial.domain.SerialSessionState
import de.nettoolbox.feature.serial.domain.SerialSettings
import de.nettoolbox.feature.serial.domain.SerialStopBits
import de.nettoolbox.feature.serial.domain.SerialText
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SerialScreen(
    modifier: Modifier = Modifier,
    viewModel: SerialViewModel = hiltViewModel(),
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab, modifier = Modifier.fillMaxWidth()) {
            Tab(
                selected = selectedTab == SerialViewModel.TAB_CONNECT,
                onClick = { viewModel.selectTab(SerialViewModel.TAB_CONNECT) },
                text = { Text(stringResource(R.string.serial_tab_connect)) },
            )
            Tab(
                selected = selectedTab == SerialViewModel.TAB_CONSOLE,
                onClick = { viewModel.selectTab(SerialViewModel.TAB_CONSOLE) },
                text = { Text(stringResource(R.string.serial_tab_console)) },
            )
        }
        when (selectedTab) {
            SerialViewModel.TAB_CONSOLE -> ConsoleTab(viewModel)
            else -> ConnectTab(viewModel)
        }
    }
}

// --- console -----------------------------------------------------------------

@Composable
private fun ConsoleTab(viewModel: SerialViewModel) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val redrawTrigger by viewModel.redrawTrigger.collectAsStateWithLifecycle()
    val ctrlActive by viewModel.ctrlActive.collectAsStateWithLifecycle()
    val altActive by viewModel.altActive.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val rx by viewModel.rxBytes.collectAsStateWithLifecycle()
    val tx by viewModel.txBytes.collectAsStateWithLifecycle()
    val isPasting by viewModel.isPasting.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingPaste by remember { mutableStateOf<String?>(null) }

    val connected = session as? SerialSessionState.Connected
    KeepScreenOn(enabled = connected != null && settings.keepScreenOn)

    // Notices clear themselves. They report something that just happened; left
    // standing they would read as a state that still applies.
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MILLIS)
            viewModel.dismissNotice()
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(
                    text = statusLine(session, rx, tx),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (connected != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                // One scrolling line of small buttons: every row of height
                // spent here is a row of console output the user does not see.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    if (connected != null) {
                        StripButton(stringResource(R.string.serial_action_break), viewModel::sendBreak)
                        if (isPasting) {
                            StripButton(stringResource(R.string.serial_action_paste_cancel), viewModel::cancelPaste)
                        } else {
                            StripButton(stringResource(R.string.serial_action_paste)) {
                                pendingPaste = context.clipboardText().orEmpty()
                            }
                        }
                        StripButton(
                            if (settings.logToFile) {
                                stringResource(R.string.serial_action_log_stop)
                            } else {
                                stringResource(R.string.serial_action_log_start)
                            },
                        ) { viewModel.updateSettings { it.copy(logToFile = !it.logToFile) } }
                    }
                    StripButton(stringResource(R.string.serial_action_clear), viewModel::clearScreen)
                    when {
                        connected != null ->
                            StripButton(stringResource(R.string.serial_action_disconnect), viewModel::disconnect)

                        session is SerialSessionState.Ended ->
                            StripButton(stringResource(R.string.serial_action_reconnect), viewModel::reconnect)
                    }
                }
                notice?.let { current ->
                    Text(
                        text = noticeText(current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }

        TerminalPane(
            vtermPtr = viewModel.terminalHandle,
            redrawTrigger = redrawTrigger,
            ctrlActive = ctrlActive,
            altActive = altActive,
            onToggleCtrl = viewModel::toggleCtrl,
            onToggleAlt = viewModel::toggleAlt,
            onChar = viewModel::typeChar,
            onKey = viewModel::typeKey,
            onResize = viewModel::onResize,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }

    pendingPaste?.let { text ->
        PasteDialog(
            text = text,
            delayMillis = settings.pasteLineDelayMillis,
            onConfirm = {
                viewModel.paste(text)
                pendingPaste = null
            },
            onDismiss = { pendingPaste = null },
        )
    }
}

/**
 * Confirms a paste before a single byte is sent.
 *
 * Pasting into a live device console is the most consequential thing this
 * screen can do: every line is executed as a command the moment it arrives.
 * Whatever happens to be on the clipboard - a password, half a config, a URL -
 * would otherwise go straight into the router. The dialog shows how many lines
 * and what they begin with, which is enough to catch the wrong clipboard.
 */
@Composable
private fun PasteDialog(text: String, delayMillis: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // The same split the sender uses, so the count shown is the count sent.
    val lines = SerialText.pasteLines(text).map { it.text }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.serial_paste_title)) },
        text = {
            if (text.isEmpty()) {
                Text(stringResource(R.string.serial_notice_paste_empty))
            } else {
                Column {
                    Text(stringResource(R.string.serial_paste_body, lines.size, delayMillis))
                    Text(
                        text = lines.take(PREVIEW_LINES).joinToString("\n"),
                        style = MonospaceTextStyle,
                        maxLines = PREVIEW_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = text.isNotEmpty()) {
                Text(stringResource(R.string.serial_paste_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.serial_cancel)) }
        },
    )
}

@Composable
private fun StripButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
        Text(label, fontSize = 11.sp)
    }
}

/** Holds the screen on while a session is open. A console that dims mid-boot-log is useless. */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

// --- connect -----------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectTab(viewModel: SerialViewModel) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val devices by viewModel.deviceList.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val supportedFlow by viewModel.supportedFlowControl.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SessionCard(session = session, onReconnect = viewModel::reconnect, onDisconnect = viewModel::disconnect) {
            viewModel.selectTab(SerialViewModel.TAB_CONSOLE)
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.serial_devices_title), Modifier.weight(1f))
            IconButton(onClick = viewModel::refreshDevices) {
                Icon(
                    painter = painterResource(NetToolboxIcons.Refresh),
                    contentDescription = stringResource(R.string.serial_devices_refresh),
                )
            }
        }

        when {
            !viewModel.hostSupported -> Hint(stringResource(R.string.serial_no_usb_host))
            devices.isEmpty() -> Hint(stringResource(R.string.serial_devices_empty))
            else -> devices.forEach { device ->
                DeviceCard(
                    device = device,
                    busy = session is SerialSessionState.Connecting,
                    isActive = (session as? SerialSessionState.Connected)?.device?.deviceName == device.deviceName,
                    onConnect = { port -> viewModel.connect(device, port) },
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))
        SectionTitle(stringResource(R.string.serial_settings_title))
        Hint(stringResource(R.string.serial_settings_live_hint))

        Label(stringResource(R.string.serial_presets))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SerialPreset.entries.forEach { preset ->
                FilterChip(
                    selected = settings.baudRate == preset.baudRate && settings.dataBits == 8 &&
                        settings.parity == SerialParity.NONE && settings.stopBits == SerialStopBits.ONE,
                    onClick = { viewModel.applyPreset(preset) },
                    label = { Text("${preset.baudRate} 8N1") },
                )
            }
        }
        Hint(SerialPreset.entries.joinToString("\n") { "${it.baudRate}: ${it.examples}" })

        Label(stringResource(R.string.serial_baud))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SerialSettings.BAUD_RATES.forEach { rate ->
                FilterChip(
                    selected = settings.baudRate == rate,
                    onClick = { viewModel.updateSettings { it.copy(baudRate = rate) } },
                    label = { Text(rate.toString()) },
                )
            }
        }
        NumberField(
            label = stringResource(R.string.serial_baud_custom),
            value = settings.baudRate,
            range = SerialSettings.MIN_BAUD..SerialSettings.MAX_BAUD,
            onChange = { rate -> viewModel.updateSettings { it.copy(baudRate = rate) } },
        )

        Label(stringResource(R.string.serial_data_bits))
        ChoiceRow(SerialSettings.DATA_BITS, settings.dataBits, { it.toString() }) { bits ->
            viewModel.updateSettings { it.copy(dataBits = bits) }
        }

        Label(stringResource(R.string.serial_parity))
        ChoiceRow(SerialParity.entries, settings.parity, { parityLabel(it) }) { parity ->
            viewModel.updateSettings { it.copy(parity = parity) }
        }

        Label(stringResource(R.string.serial_stop_bits))
        ChoiceRow(SerialStopBits.entries, settings.stopBits, { it.label }) { stop ->
            viewModel.updateSettings { it.copy(stopBits = stop) }
        }

        Label(stringResource(R.string.serial_flow))
        // Before a port is open nothing is known about the adapter, so all
        // modes are offered; once connected, only what the chip can do.
        val offeredFlow = supportedFlow.ifEmpty { SerialFlowControl.entries.toSet() }
        ChoiceRow(SerialFlowControl.entries.filter { it in offeredFlow }, settings.flowControl, { flowLabel(it) }) { flow ->
            viewModel.updateSettings { it.copy(flowControl = flow) }
        }

        Label(stringResource(R.string.serial_enter_sends))
        ChoiceRow(LineEnding.entries, settings.enterSends, { it.name.replace("CRLF", "CR+LF") }) { ending ->
            viewModel.updateSettings { it.copy(enterSends = ending) }
        }
        Hint(stringResource(R.string.serial_enter_sends_hint))

        SwitchRow(stringResource(R.string.serial_dtr), settings.dtr) { on -> viewModel.updateSettings { it.copy(dtr = on) } }
        SwitchRow(stringResource(R.string.serial_rts), settings.rts) { on -> viewModel.updateSettings { it.copy(rts = on) } }
        Hint(stringResource(R.string.serial_dtr_rts_hint))

        SwitchRow(stringResource(R.string.serial_local_echo), settings.localEcho) { on ->
            viewModel.updateSettings { it.copy(localEcho = on) }
        }
        Hint(stringResource(R.string.serial_local_echo_hint))

        NumberField(
            label = stringResource(R.string.serial_paste_delay),
            value = settings.pasteLineDelayMillis,
            range = 0..5000,
            onChange = { ms -> viewModel.updateSettings { it.copy(pasteLineDelayMillis = ms) } },
        )
        Hint(stringResource(R.string.serial_paste_delay_hint))

        SwitchRow(stringResource(R.string.serial_keep_screen_on), settings.keepScreenOn) { on ->
            viewModel.updateSettings { it.copy(keepScreenOn = on) }
        }
        SwitchRow(stringResource(R.string.serial_log_to_file), settings.logToFile) { on ->
            viewModel.updateSettings { it.copy(logToFile = on) }
        }
        Hint(stringResource(R.string.serial_log_hint))
    }
}

@Composable
private fun SessionCard(
    session: SerialSessionState,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenConsole: () -> Unit,
) {
    val (text, severe) = when (session) {
        is SerialSessionState.Connected ->
            stringResource(R.string.serial_state_connected, deviceTitle(session.device), session.settings.summary) to false

        is SerialSessionState.Connecting -> stringResource(R.string.serial_state_connecting) to false
        is SerialSessionState.Failed -> failureText(session.reason, session.detail) to true
        is SerialSessionState.Ended ->
            (session.reason?.let { failureText(it, null) } ?: stringResource(R.string.serial_state_ended)) to (session.reason != null)

        SerialSessionState.Idle -> return
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (severe) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                when (session) {
                    is SerialSessionState.Connected -> {
                        Button(onClick = onOpenConsole) { Text(stringResource(R.string.serial_open_console)) }
                        OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.serial_action_disconnect)) }
                    }

                    is SerialSessionState.Ended ->
                        Button(onClick = onReconnect) { Text(stringResource(R.string.serial_action_reconnect)) }

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: SerialDevice, busy: Boolean, isActive: Boolean, onConnect: (Int) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(deviceTitle(device), style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${chipLabel(device.chip)} · ${device.usbId}",
                style = MonospaceTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!device.hasPermission) {
                Text(
                    text = stringResource(R.string.serial_device_needs_permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Multi-port adapters (FT2232H, FT4232H, CP2105) show one button per
            // port; which physical connector is which is printed on the adapter.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (isActive) {
                    Text(
                        text = stringResource(R.string.serial_device_active),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (device.portCount <= 1) {
                    Button(onClick = { onConnect(0) }, enabled = !busy) {
                        Text(stringResource(R.string.serial_connect))
                    }
                } else {
                    repeat(device.portCount) { index ->
                        OutlinedButton(onClick = { onConnect(index) }, enabled = !busy) {
                            Text(stringResource(R.string.serial_connect_port, index + 1))
                        }
                    }
                }
            }
        }
    }
}

// --- small building blocks ---------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Keeps what was typed and only reports values inside [range]; see the file server's field of the same name. */
@Composable
private fun NumberField(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(8)
            text.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        isError = text.toIntOrNull()?.let { it in range } != true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 56.dp),
    )
}

// --- text mapping ------------------------------------------------------------

@Composable
private fun deviceTitle(device: SerialDevice): String = when {
    device.isCiscoConsole -> stringResource(R.string.serial_device_cisco)
    device.product != null -> listOfNotNull(device.manufacturer, device.product).joinToString(" · ")
    else -> chipLabel(device.chip)
}

@Composable
private fun chipLabel(chip: SerialChip): String = when (chip) {
    SerialChip.FTDI -> "FTDI"
    SerialChip.CP210X -> "Silicon Labs CP210x"
    SerialChip.PL2303 -> "Prolific PL2303"
    SerialChip.CH34X -> "WCH CH34x"
    SerialChip.CDC_ACM -> "CDC/ACM"
    SerialChip.OTHER -> stringResource(R.string.serial_chip_other)
}

@Composable
private fun parityLabel(parity: SerialParity): String = when (parity) {
    SerialParity.NONE -> stringResource(R.string.serial_parity_none)
    SerialParity.ODD -> stringResource(R.string.serial_parity_odd)
    SerialParity.EVEN -> stringResource(R.string.serial_parity_even)
    SerialParity.MARK -> stringResource(R.string.serial_parity_mark)
    SerialParity.SPACE -> stringResource(R.string.serial_parity_space)
}

@Composable
private fun flowLabel(flow: SerialFlowControl): String = when (flow) {
    SerialFlowControl.NONE -> stringResource(R.string.serial_flow_none)
    SerialFlowControl.RTS_CTS -> "RTS/CTS"
    SerialFlowControl.DTR_DSR -> "DTR/DSR"
    SerialFlowControl.XON_XOFF -> "XON/XOFF"
}

@Composable
private fun statusLine(session: SerialSessionState, rx: Long, tx: Long): String = when (session) {
    is SerialSessionState.Connected -> buildString {
        append(deviceTitle(session.device))
        append(" · ")
        append(session.settings.summary)
        append(" · RX ")
        append(rx)
        append(" / TX ")
        append(tx)
        session.logFile?.let { append(" · ").append(it) }
    }

    is SerialSessionState.Connecting -> stringResource(R.string.serial_state_connecting)
    is SerialSessionState.Ended -> session.reason?.let { failureText(it, null) } ?: stringResource(R.string.serial_state_ended)
    is SerialSessionState.Failed -> failureText(session.reason, session.detail)
    SerialSessionState.Idle -> stringResource(R.string.serial_state_idle)
}

@Composable
private fun failureText(reason: SerialFailure, detail: String?): String {
    val base = when (reason) {
        SerialFailure.PERMISSION_DENIED -> stringResource(R.string.serial_failure_permission)
        SerialFailure.DEVICE_DETACHED -> stringResource(R.string.serial_failure_detached)
        SerialFailure.OPEN_FAILED -> stringResource(R.string.serial_failure_open)
        SerialFailure.UNSUPPORTED_PARAMETERS -> stringResource(R.string.serial_failure_parameters)
        SerialFailure.IO_ERROR -> stringResource(R.string.serial_failure_io)
        SerialFailure.TERMINAL_UNAVAILABLE -> stringResource(R.string.serial_failure_terminal)
    }
    return if (detail.isNullOrBlank()) base else "$base ($detail)"
}

@Composable
private fun noticeText(notice: SerialNotice): String = when (notice) {
    SerialNotice.BREAK_SENT -> stringResource(R.string.serial_notice_break_sent)
    SerialNotice.BREAK_UNSUPPORTED -> stringResource(R.string.serial_notice_break_unsupported)
    SerialNotice.FLOW_CONTROL_UNSUPPORTED -> stringResource(R.string.serial_notice_flow_unsupported)
    SerialNotice.PASTE_EMPTY -> stringResource(R.string.serial_notice_paste_empty)
    SerialNotice.PASTE_DONE -> stringResource(R.string.serial_notice_paste_done)
    SerialNotice.LOG_FAILED -> stringResource(R.string.serial_notice_log_failed)
}

private fun Context.clipboardText(): String? {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(this)?.toString()
}

private const val NOTICE_MILLIS = 3_000L
private const val PREVIEW_LINES = 6
