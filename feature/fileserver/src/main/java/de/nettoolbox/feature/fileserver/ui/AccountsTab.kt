package de.nettoolbox.feature.fileserver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.feature.fileserver.R
import de.nettoolbox.feature.fileserver.domain.ServerAccount

/**
 * Who may connect, and with what rights.
 *
 * Both credential types live here rather than under SSH, because a user thinks
 * in terms of "who can get in" rather than in terms of which protocol carries
 * the credential.
 */
@Composable
internal fun AccountsTab(viewModel: FileServerViewModel) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var showAddKey by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<ServerAccount?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionTitle(text = stringResource(R.string.accounts_title))
        Hint(stringResource(R.string.accounts_hint))

        if (config.accounts.isEmpty()) {
            Text(
                text = stringResource(R.string.accounts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        config.accounts.forEach { account ->
            AccountCard(
                account = account,
                onToggleEnabled = { value ->
                    viewModel.updateAccount(account.copy(enabled = value))
                },
                onToggleWrite = { value ->
                    viewModel.updateAccount(account.copy(canWrite = value))
                },
                onRemove = { confirmRemove = account },
            )
        }

        OutlinedButton(
            onClick = { showAdd = true },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.accounts_add))
        }

        Text(
            text = stringResource(R.string.accounts_password_storage_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        SectionTitle(text = stringResource(R.string.accounts_keys_title))
        Hint(stringResource(R.string.accounts_keys_hint))

        if (config.ssh.authorizedKeys.isEmpty()) {
            Text(
                text = stringResource(R.string.accounts_keys_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        config.ssh.authorizedKeys.forEach { key ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp),
                ) {
                    Text(
                        // The comment at the end of an OpenSSH line is what
                        // identifies the key to a human ("robin@laptop"), so it
                        // is shown rather than the base64 blob nobody reads.
                        text = key.substringAfterLast(' ').ifBlank { key.take(24) },
                        style = MonospaceTextStyle,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.removeAuthorizedKey(key) }) {
                        Text(stringResource(R.string.accounts_keys_remove))
                    }
                }
            }
        }

        OutlinedButton(
            onClick = { showAddKey = true },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.accounts_keys_add))
        }
    }

    if (showAdd) {
        AddAccountDialog(
            suggestPassword = viewModel::suggestPassword,
            onConfirm = { username, password, home, canWrite ->
                viewModel.addAccount(username, password, home, canWrite)
                showAdd = false
            },
            onDismiss = { showAdd = false },
        )
    }

    if (showAddKey) {
        TextPromptDialog(
            title = stringResource(R.string.accounts_keys_add),
            label = stringResource(R.string.accounts_keys_paste),
            initial = "",
            onConfirm = { line ->
                viewModel.addAuthorizedKey(line)
                showAddKey = false
            },
            onDismiss = { showAddKey = false },
        )
    }

    confirmRemove?.let { account ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.accounts_delete)) },
            text = { Text(account.username) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeAccount(account.id)
                    confirmRemove = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun AccountCard(
    account: ServerAccount,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleWrite: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = account.username, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (account.canWrite) {
                            stringResource(R.string.accounts_read_write)
                        } else {
                            stringResource(R.string.accounts_read_only)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.accounts_enabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Switch(checked = account.enabled, onCheckedChange = onToggleEnabled)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    // Masked until asked. The password has to be readable - it
                    // gets typed into a switch console minutes later - but not
                    // to whoever happens to glance at the screen first.
                    text = if (revealed) account.password else PASSWORD_MASK,
                    style = MonospaceTextStyle,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { revealed = !revealed }) {
                    Text(stringResource(R.string.accounts_show_password))
                }
            }

            if (account.homeSubdirectory.isNotEmpty()) {
                Text(
                    text = "/${account.homeSubdirectory}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Checkbox(checked = account.canWrite, onCheckedChange = onToggleWrite)
                Text(
                    text = stringResource(R.string.accounts_can_write),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.accounts_delete))
                }
            }
        }
    }
}

@Composable
private fun AddAccountDialog(
    suggestPassword: () -> String,
    onConfirm: (String, String, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    // Pre-filled with a generated password. A field that starts empty is a
    // field that ends up holding "test".
    var password by remember { mutableStateOf(suggestPassword()) }
    var home by remember { mutableStateOf("") }
    var canWrite by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accounts_add)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.filterNot { c -> c.isWhitespace() } },
                    label = { Text(stringResource(R.string.accounts_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.accounts_password)) },
                    singleLine = true,
                    visualTransformation = if (revealed) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { revealed = !revealed }) {
                            Text(stringResource(R.string.accounts_show_password))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                TextButton(onClick = { password = suggestPassword() }) {
                    Text(stringResource(R.string.accounts_generate))
                }
                OutlinedTextField(
                    value = home,
                    onValueChange = { home = it },
                    label = { Text(stringResource(R.string.accounts_home)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.accounts_home_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Checkbox(checked = canWrite, onCheckedChange = { canWrite = it })
                    Text(
                        text = stringResource(R.string.accounts_can_write),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(username, password, home, canWrite) },
                enabled = username.isNotBlank() && password.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private const val PASSWORD_MASK = "••••••••"
