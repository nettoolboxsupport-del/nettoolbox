package de.nettoolbox.feature.fileserver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.core.permissions.PermissionBundle
import de.nettoolbox.core.permissions.rememberPermissionBundleState
import de.nettoolbox.feature.fileserver.R
import de.nettoolbox.feature.fileserver.domain.BindScope
import de.nettoolbox.feature.fileserver.domain.ReachableAddress
import de.nettoolbox.feature.fileserver.domain.TftpConfig
import de.nettoolbox.feature.fileserver.service.FileServerState
import de.nettoolbox.feature.fileserver.service.ProtocolStatus

/**
 * Everything about what is exposed and to whom.
 *
 * The order is deliberate: the switch and the addresses first, because that is
 * what someone opens the tab for; then the warnings that apply to the current
 * configuration; then the per-protocol detail. Settings a user never touches
 * sit at the bottom of their section rather than in a separate screen, so the
 * thing being configured is always visible next to the setting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServersTab(viewModel: FileServerViewModel) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.serverState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notifications = rememberPermissionBundleState(
        PermissionBundle.SERVICE_NOTIFICATIONS,
        viewModel.permissionCoordinator,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // --- the switch -----------------------------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        state.isStarting -> stringResource(R.string.servers_starting)
                        state.anyRunning -> state.startedAtMillis
                            ?.let { stringResource(R.string.servers_running_since, formatTimeOfDay(it)) }
                            .orEmpty()

                        !config.anyProtocolEnabled ->
                            stringResource(R.string.servers_nothing_enabled)

                        else -> stringResource(R.string.protocol_stopped)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.autoStopAtMillis?.takeIf { state.anyRunning }?.let { at ->
                    Text(
                        text = stringResource(R.string.servers_auto_stop_at, formatTimeOfDay(at)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.isStarting) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
            }

            if (state.anyRunning) {
                Button(onClick = viewModel::stopServer) {
                    Text(stringResource(R.string.servers_stop))
                }
            } else {
                Button(
                    onClick = {
                        // The service is a foreground service, so on Android 13
                        // and up its notification needs permission. Asked here,
                        // at the moment it becomes relevant, rather than at
                        // first launch where it means nothing yet.
                        if (!notifications.allGranted) notifications.request() else viewModel.startServer()
                    },
                    enabled = config.anyProtocolEnabled && !state.isStarting,
                ) {
                    Text(stringResource(R.string.servers_start))
                }
            }
        }

        state.fatalFailure?.let { failure ->
            WarningCard(text = failure.asText(0), severe = true)
        }

        if (config.hasUnauthenticatedWrite) {
            WarningCard(
                text = stringResource(R.string.servers_open_write_warning),
                severe = true,
            )
        }

        if (state.anyRunning && state.addresses.isNotEmpty()) {
            AddressCard(addresses = state.addresses, state = state, context = context)
        }

        // --- global settings ------------------------------------------------
        SectionTitle(
            text = stringResource(R.string.servers_bind_title),
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        // FlowRow, not Row. A Row divides the width among its children, so the
        // last chip is squeezed to a sliver and its label wraps to one letter
        // per line on a narrow screen. FlowRow keeps every chip at its natural
        // width and moves the ones that do not fit onto the next line, which is
        // what makes this correct at any width and in any language - the German
        // labels are markedly longer than the English ones.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = config.bindScope == BindScope.WIFI_ONLY,
                onClick = { viewModel.updateConfig { it.copy(bindScope = BindScope.WIFI_ONLY) } },
                label = { Text(stringResource(R.string.servers_bind_wifi)) },
            )
            FilterChip(
                selected = config.bindScope == BindScope.ALL_INTERFACES,
                onClick = { viewModel.updateConfig { it.copy(bindScope = BindScope.ALL_INTERFACES) } },
                label = { Text(stringResource(R.string.servers_bind_all)) },
            )
            FilterChip(
                selected = config.bindScope == BindScope.LOOPBACK_ONLY,
                onClick = { viewModel.updateConfig { it.copy(bindScope = BindScope.LOOPBACK_ONLY) } },
                label = { Text(stringResource(R.string.servers_bind_loopback)) },
            )
        }
        Hint(
            when (config.bindScope) {
                BindScope.WIFI_ONLY -> stringResource(R.string.servers_bind_wifi_hint)
                BindScope.ALL_INTERFACES -> stringResource(R.string.servers_bind_all_warning)
                BindScope.LOOPBACK_ONLY -> stringResource(R.string.servers_bind_wifi_hint)
            },
        )

        SectionTitle(
            text = stringResource(R.string.servers_auto_stop_title),
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(0, 15, 60, 240).forEach { minutes ->
                FilterChip(
                    selected = config.autoStopMinutes == minutes,
                    onClick = { viewModel.updateConfig { it.copy(autoStopMinutes = minutes) } },
                    label = {
                        Text(
                            if (minutes == 0) {
                                stringResource(R.string.servers_auto_stop_off)
                            } else {
                                stringResource(R.string.servers_auto_stop_minutes, minutes)
                            },
                        )
                    },
                )
            }
        }
        Hint(stringResource(R.string.servers_auto_stop_hint))

        SwitchRow(
            label = stringResource(R.string.servers_keep_awake),
            checked = config.keepAwakeWhileServing,
            onCheckedChange = { value ->
                viewModel.updateConfig { it.copy(keepAwakeWhileServing = value) }
            },
        )
        Hint(stringResource(R.string.servers_keep_awake_hint))

        // The privileged-port fact, stated where it can be read before it bites.
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.servers_port_note_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.servers_port_note_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        TftpSection(viewModel)
        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))
        FtpSection(viewModel)
        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))
        SshSection(viewModel)
    }
}

// --- TFTP -------------------------------------------------------------------

@Composable
private fun TftpSection(viewModel: FileServerViewModel) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.serverState.collectAsStateWithLifecycle()
    val tftp = config.tftp

    ProtocolHeader(
        title = stringResource(R.string.tftp_title),
        summary = stringResource(R.string.tftp_summary),
        enabled = tftp.enabled,
        status = state.tftp,
        onEnabledChange = { value ->
            viewModel.updateConfig { it.copy(tftp = it.tftp.copy(enabled = value)) }
        },
    )

    PortField(
        label = stringResource(R.string.tftp_port),
        port = tftp.port,
        onChange = { value ->
            viewModel.updateConfig { it.copy(tftp = it.tftp.copy(port = value)) }
        },
    )

    SwitchRow(
        label = stringResource(R.string.tftp_allow_upload),
        checked = tftp.allowUpload,
        onCheckedChange = { value ->
            viewModel.updateConfig { it.copy(tftp = it.tftp.copy(allowUpload = value)) }
        },
    )
    if (tftp.allowUpload) {
        WarningCard(text = stringResource(R.string.tftp_allow_upload_warning), severe = true)
        SwitchRow(
            label = stringResource(R.string.tftp_allow_overwrite),
            checked = tftp.allowOverwrite,
            onCheckedChange = { value ->
                viewModel.updateConfig { it.copy(tftp = it.tftp.copy(allowOverwrite = value)) }
            },
        )
    }

    NumberField(
        label = stringResource(R.string.tftp_block_size),
        value = tftp.maxBlockSize,
        range = TftpConfig.MIN_BLOCK_SIZE..TftpConfig.MAX_BLOCK_SIZE,
        onChange = { value ->
            viewModel.updateConfig { it.copy(tftp = it.tftp.copy(maxBlockSize = value)) }
        },
    )
    Hint(stringResource(R.string.tftp_block_size_hint))

    NumberField(
        label = stringResource(R.string.tftp_window_size),
        value = tftp.windowSize,
        range = 1..64,
        onChange = { value ->
            viewModel.updateConfig { it.copy(tftp = it.tftp.copy(windowSize = value)) }
        },
    )
    Hint(stringResource(R.string.tftp_window_size_hint))

    NumberField(
        label = stringResource(R.string.tftp_timeout),
        value = tftp.timeoutSeconds,
        range = 1..255,
        onChange = { value ->
            viewModel.updateConfig { it.copy(tftp = it.tftp.copy(timeoutSeconds = value)) }
        },
    )
}

// --- FTP --------------------------------------------------------------------

@Composable
private fun FtpSection(viewModel: FileServerViewModel) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.serverState.collectAsStateWithLifecycle()
    val ftp = config.ftp

    ProtocolHeader(
        title = stringResource(R.string.ftp_title),
        summary = stringResource(R.string.ftp_summary),
        enabled = ftp.enabled,
        status = state.ftp,
        onEnabledChange = { value ->
            viewModel.updateConfig { it.copy(ftp = it.ftp.copy(enabled = value)) }
        },
    )

    PortField(
        label = stringResource(R.string.ftp_port),
        port = ftp.port,
        onChange = { value -> viewModel.updateConfig { it.copy(ftp = it.ftp.copy(port = value)) } },
    )

    SwitchRow(
        label = stringResource(R.string.ftp_anonymous),
        checked = ftp.allowAnonymous,
        onCheckedChange = { value ->
            viewModel.updateConfig { it.copy(ftp = it.ftp.copy(allowAnonymous = value)) }
        },
    )
    if (ftp.allowAnonymous) {
        SwitchRow(
            label = stringResource(R.string.ftp_anonymous_write),
            checked = ftp.anonymousCanWrite,
            onCheckedChange = { value ->
                viewModel.updateConfig { it.copy(ftp = it.ftp.copy(anonymousCanWrite = value)) }
            },
        )
    }

    SwitchRow(
        label = stringResource(R.string.ftp_tls),
        checked = ftp.tlsEnabled,
        onCheckedChange = { value ->
            viewModel.updateConfig { it.copy(ftp = it.ftp.copy(tlsEnabled = value)) }
        },
    )
    Hint(stringResource(R.string.ftp_tls_hint))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NumberField(
            label = stringResource(R.string.ftp_passive_range),
            value = ftp.passivePortFrom,
            range = 1024..65535,
            onChange = { value ->
                viewModel.updateConfig { it.copy(ftp = it.ftp.copy(passivePortFrom = value)) }
            },
            modifier = Modifier.weight(1f),
        )
        NumberField(
            label = "–",
            value = ftp.passivePortTo,
            range = 1024..65535,
            onChange = { value ->
                viewModel.updateConfig { it.copy(ftp = it.ftp.copy(passivePortTo = value)) }
            },
            modifier = Modifier.weight(1f),
        )
    }
    Hint(stringResource(R.string.ftp_passive_hint))

    NumberField(
        label = stringResource(R.string.ftp_max_logins),
        value = ftp.maxConcurrentLogins,
        range = 1..100,
        onChange = { value ->
            viewModel.updateConfig { it.copy(ftp = it.ftp.copy(maxConcurrentLogins = value)) }
        },
    )
    NumberField(
        label = stringResource(R.string.ftp_idle_timeout),
        value = ftp.idleTimeoutSeconds,
        range = 30..3600,
        onChange = { value ->
            viewModel.updateConfig { it.copy(ftp = it.ftp.copy(idleTimeoutSeconds = value)) }
        },
    )
}

// --- SSH --------------------------------------------------------------------

@Composable
private fun SshSection(viewModel: FileServerViewModel) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.serverState.collectAsStateWithLifecycle()
    val fingerprint by viewModel.hostKeyFingerprint.collectAsStateWithLifecycle()
    val ssh = config.ssh
    val context = LocalContext.current
    var confirmRegenerate by remember { mutableStateOf(false) }

    ProtocolHeader(
        title = stringResource(R.string.ssh_title),
        summary = stringResource(R.string.ssh_summary),
        enabled = ssh.enabled,
        status = state.ssh,
        onEnabledChange = { value ->
            viewModel.updateConfig { it.copy(ssh = it.ssh.copy(enabled = value)) }
        },
    )

    PortField(
        label = stringResource(R.string.ssh_port),
        port = ssh.port,
        onChange = { value -> viewModel.updateConfig { it.copy(ssh = it.ssh.copy(port = value)) } },
    )

    SwitchRow(
        label = stringResource(R.string.ssh_enable_sftp),
        checked = ssh.enableSftp,
        onCheckedChange = { value ->
            viewModel.updateConfig { it.copy(ssh = it.ssh.copy(enableSftp = value)) }
        },
    )
    SwitchRow(
        label = stringResource(R.string.ssh_enable_scp),
        checked = ssh.enableScp,
        onCheckedChange = { value ->
            viewModel.updateConfig { it.copy(ssh = it.ssh.copy(enableScp = value)) }
        },
    )
    Hint(stringResource(R.string.ssh_enable_scp_hint))

    SwitchRow(
        label = stringResource(R.string.ssh_password_auth),
        checked = ssh.allowPasswordAuth,
        onCheckedChange = { value ->
            viewModel.updateConfig { it.copy(ssh = it.ssh.copy(allowPasswordAuth = value)) }
        },
    )
    Hint(stringResource(R.string.ssh_no_shell_note))

    SectionTitle(
        text = stringResource(R.string.ssh_host_key_title),
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
    Text(
        text = fingerprint,
        style = de.nettoolbox.core.ui.theme.MonospaceTextStyle,
    )
    Hint(stringResource(R.string.ssh_host_key_hint))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { context.copyToClipboard(viewModel.hostKeyLine()) }) {
            Text(stringResource(R.string.ssh_host_key_copy))
        }
        TextButton(onClick = { confirmRegenerate = true }) {
            Text(stringResource(R.string.ssh_host_key_regenerate))
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text(stringResource(R.string.ssh_host_key_regenerate_title)) },
            text = { Text(stringResource(R.string.ssh_host_key_regenerate_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.regenerateHostKey()
                    confirmRegenerate = false
                }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

// --- shared pieces ----------------------------------------------------------

@Composable
private fun ProtocolHeader(
    title: String,
    summary: String,
    enabled: Boolean,
    status: ProtocolStatus,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }

    if (status.running) {
        Text(
            text = stringResource(R.string.protocol_running, status.port),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    status.failure?.let { failure ->
        WarningCard(text = failure.asText(status.port), severe = true)
    }
}

@Composable
private fun AddressCard(
    addresses: List<ReachableAddress>,
    state: FileServerState,
    context: Context,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.servers_reachable_title),
                style = MaterialTheme.typography.titleSmall,
            )
            addresses.forEach { address ->
                // One line per address per running protocol, spelled out as a
                // URL. Copying it is the whole point, so it is one tap away
                // rather than something to be transcribed by hand.
                val urls = buildList {
                    if (state.tftp.running) add("tftp://${address.urlLiteral}:${state.tftp.port}")
                    if (state.ftp.running) add("ftp://${address.urlLiteral}:${state.ftp.port}")
                    if (state.ssh.running) add("sftp://${address.urlLiteral}:${state.ssh.port}")
                }
                urls.forEach { url ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    ) {
                        Text(
                            text = url,
                            style = de.nettoolbox.core.ui.theme.MonospaceTextStyle,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { context.copyToClipboard(url) }) {
                            Text(stringResource(R.string.servers_copy_address))
                        }
                    }
                }
                Text(
                    text = address.interfaceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.servers_reachable_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
internal fun WarningCard(text: String, severe: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (severe) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (severe) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
internal fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PortField(label: String, port: Int, onChange: (Int) -> Unit) {
    NumberField(label = label, value = port, range = 1024..65535, onChange = onChange)
}

/**
 * A number field that keeps what the user typed, not what parses.
 *
 * The text is local state and only a value inside [range] is pushed outward.
 * Writing straight through would make the field snap back the moment someone
 * deletes a digit to type a different one - the classic port field that cannot
 * be edited.
 */
@Composable
private fun NumberField(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(6)
            text.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        // Written the long way round because IntRange.contains does not accept
        // a nullable Int - and a field the user has emptied is exactly the case
        // that has to read as an error rather than as zero.
        isError = text.toIntOrNull()?.let { it in range } != true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    )
}

internal fun Context.copyToClipboard(text: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("NetToolbox", text))
}
