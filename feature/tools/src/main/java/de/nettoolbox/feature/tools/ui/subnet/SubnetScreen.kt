package de.nettoolbox.feature.tools.ui.subnet

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.core.ui.component.ResultActionBar
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.feature.tools.R
import de.nettoolbox.feature.tools.domain.subnet.Ipv4Subnet
import de.nettoolbox.feature.tools.domain.subnet.Ipv6Subnet

@Composable
fun SubnetScreen(
    modifier: Modifier = Modifier,
    viewModel: SubnetViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val rows = when (val result = uiState.result) {
        is SubnetResult.Ipv4 -> ipv4Rows(result.subnet)
        is SubnetResult.Ipv6 -> ipv6Rows(result.subnet)
        else -> emptyList()
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = uiState.input,
            onValueChange = viewModel::onInputChange,
            label = { Text(stringResource(R.string.subnet_input_label)) },
            placeholder = { Text("192.168.1.10/24") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        when (val result = uiState.result) {
            SubnetResult.Empty -> Text(
                text = stringResource(R.string.subnet_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )

            is SubnetResult.Invalid -> Text(
                text = stringResource(R.string.subnet_invalid),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )

            else -> Unit
        }

        if (rows.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(top = 12.dp),
            ) {
                items(rows) { row -> ResultRow(row) }

                if (uiState.result is SubnetResult.Ipv4) {
                    item { SplitControls(uiState, viewModel) }
                }

                items(uiState.splitResult) { subnet ->
                    Text(
                        text = subnet.toString(),
                        style = MonospaceTextStyle,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }

            ResultActionBar(plainText = rows.joinToString("\n") { "${it.label}: ${it.value}" })
        }
    }
}

@Composable
private fun ResultRow(row: SubnetRow) {
    val valueStyle = if (row.monospace) MonospaceTextStyle else MaterialTheme.typography.bodyMedium

    if (row.fullWidth) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = row.value,
                style = valueStyle,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 2.dp),
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = row.value,
                style = valueStyle,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1.4f),
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun SplitControls(uiState: SubnetUiState, viewModel: SubnetViewModel) {
    val current = (uiState.result as? SubnetResult.Ipv4)?.subnet ?: return
    val options = (current.prefixLength + 1..minOf(current.prefixLength + 4, 32)).toList()

    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = stringResource(R.string.subnet_split_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            options.forEach { prefix ->
                AssistChip(
                    onClick = { viewModel.onSplit(prefix) },
                    label = { Text("/$prefix") },
                )
            }
        }
        uiState.splitError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ipv4Rows(subnet: Ipv4Subnet): List<SubnetRow> = listOf(
    SubnetRow(stringResource(R.string.subnet_row_address), subnet.address.toString()),
    SubnetRow(stringResource(R.string.subnet_row_network), "${subnet.network}/${subnet.prefixLength}"),
    SubnetRow(stringResource(R.string.subnet_row_netmask), subnet.netmask.toString()),
    SubnetRow(stringResource(R.string.subnet_row_wildcard), subnet.wildcard.toString()),
    SubnetRow(stringResource(R.string.subnet_row_broadcast), subnet.broadcast.toString()),
    SubnetRow(
        stringResource(R.string.subnet_row_host_range),
        "${subnet.firstUsableHost} - ${subnet.lastUsableHost}",
    ),
    SubnetRow(stringResource(R.string.subnet_row_usable_hosts), subnet.usableHosts.toString()),
    SubnetRow(stringResource(R.string.subnet_row_total), subnet.totalAddresses.toString()),
    SubnetRow(
        label = stringResource(R.string.subnet_row_binary),
        value = subnet.address.toBinaryString(),
        fullWidth = true,
    ),
    SubnetRow(
        label = stringResource(R.string.subnet_row_mask_binary),
        value = subnet.netmask.toBinaryString(),
        fullWidth = true,
    ),
)

@Composable
private fun ipv6Rows(subnet: Ipv6Subnet): List<SubnetRow> = listOf(
    SubnetRow(stringResource(R.string.subnet_row_network), subnet.toString()),
    SubnetRow(stringResource(R.string.subnet_row_first), subnet.formatNetwork()),
    SubnetRow(stringResource(R.string.subnet_row_last), subnet.formatLastAddress()),
    SubnetRow(stringResource(R.string.subnet_row_total), subnet.totalAddresses.toString()),
)
