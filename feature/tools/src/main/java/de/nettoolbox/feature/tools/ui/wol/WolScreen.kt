package de.nettoolbox.feature.tools.ui.wol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.core.ui.error.errorHint
import de.nettoolbox.core.ui.error.errorTitle
import de.nettoolbox.feature.tools.R

@Composable
fun WolScreen(
    modifier: Modifier = Modifier,
    viewModel: WolViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val savedDevices by viewModel.savedDevices.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = uiState.macAddress,
            onValueChange = viewModel::onMacChange,
            label = { Text(stringResource(R.string.wol_mac_label)) },
            placeholder = { Text("AA:BB:CC:DD:EE:FF") },
            isError = uiState.macAddress.isNotEmpty() && !uiState.isMacValid,
            supportingText = {
                if (uiState.macAddress.isNotEmpty() && !uiState.isMacValid) {
                    Text(stringResource(R.string.wol_mac_invalid))
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = uiState.broadcastAddress,
            onValueChange = viewModel::onBroadcastChange,
            label = { Text(stringResource(R.string.wol_broadcast_label)) },
            supportingText = { Text(stringResource(R.string.wol_broadcast_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = uiState.port,
            onValueChange = viewModel::onPortChange,
            label = { Text(stringResource(R.string.wol_port_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = viewModel::send,
            enabled = uiState.canSend,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.wol_send))
        }

        if (uiState.packetSent) {
            Text(
                text = stringResource(R.string.wol_sent),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        uiState.error?.let { error ->
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

        if (savedDevices.isNotEmpty()) {
            Text(
                text = stringResource(R.string.wol_recent),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                savedDevices.take(4).forEach { entry ->
                    AssistChip(
                        onClick = { viewModel.onSelectSaved(entry) },
                        label = { Text(entry.target) },
                    )
                }
            }
        }
    }
}
