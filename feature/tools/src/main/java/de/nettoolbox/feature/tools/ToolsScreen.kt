package de.nettoolbox.feature.tools

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private data class ToolEntry(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val onClick: (() -> Unit)?,
)

/**
 * Entry point of the tools tab.
 *
 * Tools that are not built yet are listed but not clickable, with the phase they
 * belong to. Hiding them would be tidier; showing them tells a technician in the
 * field what the app can and cannot do before they need it.
 */
@Composable
fun ToolsScreen(
    onOpenPing: () -> Unit,
    onOpenSubnet: () -> Unit,
    onOpenWol: () -> Unit,
    onOpenDns: () -> Unit,
    onOpenPortScan: () -> Unit,
    onOpenHttpInspector: () -> Unit,
    onOpenIpScan: () -> Unit,
    onOpenTraceroute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = listOf(
        ToolEntry(R.string.tool_ip_scan, R.string.tool_ip_scan_description, onOpenIpScan),
        ToolEntry(R.string.tool_ping, R.string.tool_ping_description, onOpenPing),
        ToolEntry(R.string.tool_traceroute, R.string.tool_traceroute_description, onOpenTraceroute),
        ToolEntry(R.string.tool_dns, R.string.tool_dns_description, onOpenDns),
        ToolEntry(R.string.tool_port_scan, R.string.tool_port_scan_description, onOpenPortScan),
        ToolEntry(R.string.tool_http_tls, R.string.tool_http_tls_description, onOpenHttpInspector),
        ToolEntry(R.string.tool_subnet, R.string.tool_subnet_description, onOpenSubnet),
        ToolEntry(R.string.tool_wol, R.string.tool_wol_description, onOpenWol),
    )

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
    ) {
        items(entries) { entry -> ToolCard(entry) }
    }
}

@Composable
private fun ToolCard(entry: ToolEntry) {
    val onClick = entry.onClick
    val enabled = onClick != null
    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(entry.titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = stringResource(entry.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) { content() }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) { content() }
    }
}
