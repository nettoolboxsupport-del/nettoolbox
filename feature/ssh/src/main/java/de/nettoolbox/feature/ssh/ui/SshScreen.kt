package de.nettoolbox.feature.ssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.core.ui.component.EmptyState
import de.nettoolbox.core.ui.component.ErrorState
import de.nettoolbox.core.ui.component.LoadingState
import de.nettoolbox.core.ui.icon.NetToolboxIcons
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.feature.ssh.R
import de.nettoolbox.feature.ssh.data.KnownHost
import de.nettoolbox.feature.ssh.data.SshAuthType
import de.nettoolbox.feature.ssh.data.SshProfile
import de.nettoolbox.feature.ssh.domain.SshConnectionState
import de.nettoolbox.vterm.VtermKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshScreen(
    modifier: Modifier = Modifier,
    viewModel: SshViewModel = hiltViewModel(),
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()

    // Host key prompt dialog
    val awaitingDecision = (connectionState as? SshConnectionState.AwaitingHostKeyDecision)?.verdict
    if (awaitingDecision != null) {
        HostKeyDialog(
            verdict = awaitingDecision,
            onAccept = { verdict -> viewModel.onAcceptHostKey(verdict) },
            onReject = { viewModel.onRejectHostKey() },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // Deliberately absent on the terminal tab. A title and subtitle
            // cost roughly three lines of terminal output, and they tell the
            // user nothing they do not already know while they are typing into
            // a shell. Every other tab keeps them.
            if (selectedTab != TERMINAL_TAB) {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.feature_ssh_title))
                            Text(
                                text = stringResource(R.string.feature_ssh_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { viewModel.selectTab(0) },
                    text = { Text(stringResource(R.string.ssh_tab_connect)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { viewModel.selectTab(1) },
                    text = { Text(stringResource(R.string.ssh_tab_terminal)) },
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { viewModel.selectTab(2) },
                    text = { Text(stringResource(R.string.ssh_tab_hosts)) },
                )
            }

            when (selectedTab) {
                0 -> ConnectTabContent(viewModel = viewModel, connectionState = connectionState)
                1 -> TerminalTabContent(viewModel = viewModel, connectionState = connectionState)
                2 -> HostsAndProfilesTabContent(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun ConnectTabContent(
    viewModel: SshViewModel,
    connectionState: SshConnectionState,
) {
    val host by viewModel.host.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val authType by viewModel.authType.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val privateKeyPem by viewModel.privateKeyPem.collectAsStateWithLifecycle()
    val passphrase by viewModel.passphrase.collectAsStateWithLifecycle()
    val saveProfile by viewModel.saveProfile.collectAsStateWithLifecycle()
    val profileName by viewModel.profileName.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()

    val isConnecting = connectionState is SshConnectionState.Connecting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Quick connect cards from saved profiles if any
        if (profiles.isNotEmpty()) {
            Text(
                text = stringResource(R.string.ssh_profiles_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                profiles.take(3).forEach { profile ->
                    Card(
                        onClick = { viewModel.loadProfile(profile) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(text = profile.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    text = "${profile.username}@${profile.host}:${profile.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FilledTonalButton(onClick = { viewModel.loadProfile(profile) }) {
                                Text(stringResource(R.string.ssh_action_load_profile))
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Connection form
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { viewModel.host.value = it },
                label = { Text(stringResource(R.string.ssh_host_label)) },
                placeholder = { Text(stringResource(R.string.ssh_host_placeholder)) },
                singleLine = true,
                modifier = Modifier.weight(3f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { viewModel.port.value = it },
                label = { Text(stringResource(R.string.ssh_port_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        OutlinedTextField(
            value = username,
            onValueChange = { viewModel.username.value = it },
            label = { Text(stringResource(R.string.ssh_user_label)) },
            placeholder = { Text(stringResource(R.string.ssh_user_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Auth type selector
        Text(
            text = stringResource(R.string.ssh_auth_type_label),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = authType == SshAuthType.PASSWORD,
                onClick = { viewModel.authType.value = SshAuthType.PASSWORD },
                label = { Text(stringResource(R.string.ssh_auth_password)) },
            )
            FilterChip(
                selected = authType == SshAuthType.PRIVATE_KEY,
                onClick = { viewModel.authType.value = SshAuthType.PRIVATE_KEY },
                label = { Text(stringResource(R.string.ssh_auth_key)) },
            )
        }

        if (authType == SshAuthType.PASSWORD) {
            OutlinedTextField(
                value = password,
                onValueChange = { viewModel.password.value = it },
                label = { Text(stringResource(R.string.ssh_password_label)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = privateKeyPem,
                onValueChange = { viewModel.privateKeyPem.value = it },
                label = { Text(stringResource(R.string.ssh_key_pem_label)) },
                placeholder = { Text(stringResource(R.string.ssh_key_pem_placeholder)) },
                minLines = 3,
                maxLines = 6,
                textStyle = MonospaceTextStyle.copy(fontSize = 11.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { viewModel.passphrase.value = it },
                label = { Text(stringResource(R.string.ssh_passphrase_label)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Save profile option
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = saveProfile,
                onCheckedChange = { viewModel.saveProfile.value = it },
            )
            Text(
                text = stringResource(R.string.ssh_save_profile_label),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (saveProfile) {
            OutlinedTextField(
                value = profileName,
                onValueChange = { viewModel.profileName.value = it },
                label = { Text(stringResource(R.string.ssh_profile_name_label)) },
                placeholder = { Text(stringResource(R.string.ssh_profile_name_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (connectionState is SshConnectionState.Failed) {
            ErrorState(
                error = connectionState.error,
                onRetry = { viewModel.connect() },
            )
        }

        // Connect button
        Button(
            onClick = { viewModel.connect() },
            enabled = !isConnecting,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.ssh_status_connecting, host, port.toIntOrNull() ?: 22))
            } else {
                Text(stringResource(R.string.ssh_action_connect))
            }
        }
    }
}

@Composable
private fun TerminalTabContent(
    viewModel: SshViewModel,
    connectionState: SshConnectionState,
) {
    val ctrlActive by viewModel.ctrlActive.collectAsStateWithLifecycle()
    val altActive by viewModel.altActive.collectAsStateWithLifecycle()
    val redrawTrigger by viewModel.redrawTrigger.collectAsStateWithLifecycle()

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var rawInputText by remember { mutableStateOf(TextFieldValue("")) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // imePadding is what keeps the cursor line visible. Without it the soft
    // keyboard is drawn OVER the terminal, hiding the bottom rows - which are
    // exactly the ones being typed on. With it the column shrinks, the canvas
    // remeasures, fewer rows are reported to the server, and the shell's last
    // line stays just above the keyboard.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // Status strip. One line, small type, minimal padding: on a phone in
        // portrait every 8dp here is a line of output the user does not see.
        // It stays because a terminal that does not say who it is connected to
        // is a hazard when several sessions look alike.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val statusText = when (connectionState) {
                    is SshConnectionState.Connected ->
                        "${connectionState.target.username}@${connectionState.target.host}"
                    is SshConnectionState.Connecting ->
                        stringResource(R.string.ssh_status_authenticating)
                    is SshConnectionState.Disconnected ->
                        stringResource(R.string.ssh_status_disconnected)
                    else -> stringResource(R.string.ssh_status_idle)
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (connectionState is SshConnectionState.Connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.weight(1f),
                )

                TextButton(
                    onClick = { viewModel.clearScreen() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.ssh_action_clear), fontSize = 11.sp)
                }
                if (connectionState is SshConnectionState.Connected) {
                    TextButton(
                        onClick = { viewModel.disconnect() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.ssh_action_disconnect), fontSize = 11.sp)
                    }
                }
            }
        }

        // Terminal screen area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            TerminalCanvas(
                vtermPtr = viewModel.terminalPtr,
                redrawTrigger = redrawTrigger,
                onResize = { rows, cols -> viewModel.onResize(rows, cols) },
                onTap = {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                },
            )

            // Transparent IME and hardware key input receiver overlay
            BasicTextField(
                value = rawInputText,
                onValueChange = { newVal ->
                    val text = newVal.text
                    if (text.isNotEmpty()) {
                        for (c in text) {
                            if (c == '\n' || c == '\r') {
                                viewModel.sendKey(VtermKey.ENTER)
                            } else {
                                viewModel.sendChar(c)
                            }
                        }
                        rawInputText = TextFieldValue("")
                    }
                },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0.01f)
                    .focusRequester(focusRequester)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.Enter -> {
                                    viewModel.sendKey(VtermKey.ENTER)
                                    true
                                }
                                Key.Backspace -> {
                                    viewModel.sendKey(VtermKey.BACKSPACE)
                                    true
                                }
                                Key.Tab -> {
                                    viewModel.sendKey(VtermKey.TAB)
                                    true
                                }
                                Key.Escape -> {
                                    viewModel.sendKey(VtermKey.ESCAPE)
                                    true
                                }
                                Key.DirectionUp -> {
                                    viewModel.sendKey(VtermKey.UP)
                                    true
                                }
                                Key.DirectionDown -> {
                                    viewModel.sendKey(VtermKey.DOWN)
                                    true
                                }
                                Key.DirectionLeft -> {
                                    viewModel.sendKey(VtermKey.LEFT)
                                    true
                                }
                                Key.DirectionRight -> {
                                    viewModel.sendKey(VtermKey.RIGHT)
                                    true
                                }
                                else -> false
                            }
                        } else {
                            false
                        }
                    },
                textStyle = TextStyle(color = androidx.compose.ui.graphics.Color.Transparent),
                cursorBrush = SolidColor(androidx.compose.ui.graphics.Color.Transparent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(
                    onSend = { viewModel.sendKey(VtermKey.ENTER) },
                    onDone = { viewModel.sendKey(VtermKey.ENTER) },
                    onGo = { viewModel.sendKey(VtermKey.ENTER) },
                ),
            )
        }

        // Auxiliary Modifier bar
        ModifierBar(
            ctrlActive = ctrlActive,
            altActive = altActive,
            onToggleCtrl = { viewModel.toggleCtrl() },
            onToggleAlt = { viewModel.toggleAlt() },
            onSendKey = { key -> viewModel.sendKey(key) },
            onSendChar = { char -> viewModel.sendChar(char) },
        )
    }
}

@Composable
private fun HostsAndProfilesTabContent(
    viewModel: SshViewModel,
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val knownHosts by viewModel.knownHosts.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Saved profiles
        Text(
            text = stringResource(R.string.ssh_profiles_title),
            style = MaterialTheme.typography.titleMedium,
        )
        if (profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.ssh_profiles_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(profiles) { profile ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "${profile.username}@${profile.host}:${profile.port}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row {
                                FilledTonalButton(onClick = { viewModel.loadProfile(profile) }) {
                                    Text(stringResource(R.string.ssh_action_load_profile))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(onClick = { viewModel.deleteProfile(profile.id) }) {
                                    Icon(
                                        painter = painterResource(NetToolboxIcons.Stop),
                                        contentDescription = stringResource(R.string.ssh_action_delete_profile),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Trusted host keys
        Text(
            text = stringResource(R.string.ssh_known_hosts_title),
            style = MaterialTheme.typography.titleMedium,
        )
        if (knownHosts.isEmpty()) {
            Text(
                text = stringResource(R.string.ssh_known_hosts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(knownHosts) { host ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = host.hostId,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "${host.keyType} • ${host.fingerprintSha256}",
                                    style = MonospaceTextStyle.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.ssh_known_host_added,
                                        dateFormat.format(Date(host.addedAtMillis)),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            IconButton(onClick = { viewModel.forgetHostKey(host.hostId, host.keyType) }) {
                                Icon(
                                    painter = painterResource(NetToolboxIcons.Stop),
                                    contentDescription = stringResource(R.string.ssh_action_forget_host),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The terminal tab hides the app bar to give its height to the character grid. */
private const val TERMINAL_TAB = 1
