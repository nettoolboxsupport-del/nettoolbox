package de.nettoolbox.app.ui.dashboard

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.app.R
import de.nettoolbox.core.ui.icon.NetToolboxIcons
import de.nettoolbox.feature.cellular.R as CellularR
import de.nettoolbox.feature.iperf.R as IperfR
import de.nettoolbox.feature.map.R as MapR
import de.nettoolbox.feature.ssh.R as SshR
import de.nettoolbox.feature.tools.R as ToolsR
import de.nettoolbox.feature.wifi.R as WifiR

private data class QuickAccessTile(
    @StringRes val labelRes: Int,
    @DrawableRes val icon: Int,
    val onClick: () -> Unit,
)

/**
 * Phase-1 dashboard: quick-access tiles only. The live connection tiles from the
 * spec follow once the cellular and Wi-Fi data sources exist - a dashboard that
 * shows signal values without a data source would be showing fiction.
 */
@Composable
fun DashboardScreen(
    onOpenCellular: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenIperf: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenSsh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val userName by viewModel.userName.collectAsStateWithLifecycle()
    val tiles = listOf(
        QuickAccessTile(CellularR.string.feature_cellular_title, NetToolboxIcons.Cellular, onOpenCellular),
        QuickAccessTile(WifiR.string.feature_wifi_title, NetToolboxIcons.Wifi, onOpenWifi),
        QuickAccessTile(ToolsR.string.feature_tools_title, NetToolboxIcons.Tools, onOpenTools),
        QuickAccessTile(IperfR.string.feature_iperf_title, NetToolboxIcons.Speed, onOpenIperf),
        QuickAccessTile(SshR.string.feature_ssh_title, NetToolboxIcons.Terminal, onOpenSsh),
        QuickAccessTile(MapR.string.feature_map_title, NetToolboxIcons.Map, onOpenMap),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // The greeting replaces the app name once a name is set - a
                // technician does not need to be told which app they opened.
                Text(
                    text = if (userName.isNotEmpty()) {
                        stringResource(R.string.dashboard_greeting, userName)
                    } else {
                        stringResource(R.string.dashboard_title)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.dashboard_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            IconButton(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(NetToolboxIcons.Settings),
                    contentDescription = stringResource(R.string.dashboard_open_settings),
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(tiles) { tile ->
                QuickAccessCard(tile = tile)
            }
        }
    }
}

@Composable
private fun QuickAccessCard(
    tile: QuickAccessTile,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = tile.onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(tile.icon),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(tile.labelRes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
