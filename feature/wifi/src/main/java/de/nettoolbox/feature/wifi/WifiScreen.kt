package de.nettoolbox.feature.wifi

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.core.common.signal.SignalMetric
import de.nettoolbox.core.permissions.PermissionBundle
import de.nettoolbox.core.permissions.PermissionGate
import de.nettoolbox.core.permissions.openDeveloperOptions
import de.nettoolbox.core.ui.component.BucketStrategy
import de.nettoolbox.core.ui.component.ChartLegend
import de.nettoolbox.core.ui.component.ChartSeries
import de.nettoolbox.core.ui.component.EmptyState
import de.nettoolbox.core.ui.component.LineChart
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.core.ui.theme.color
import de.nettoolbox.core.ui.theme.labelRes
import de.nettoolbox.feature.wifi.data.WifiConnection
import de.nettoolbox.feature.wifi.domain.WifiBand
import de.nettoolbox.feature.wifi.domain.WifiNetwork
import de.nettoolbox.feature.wifi.ui.ChannelDiagram
import de.nettoolbox.feature.wifi.ui.WifiSortOrder
import de.nettoolbox.feature.wifi.ui.WifiUiState
import de.nettoolbox.feature.wifi.ui.WifiViewMode
import de.nettoolbox.feature.wifi.ui.WifiViewModel

@Composable
fun WifiScreen(
    modifier: Modifier = Modifier,
    viewModel: WifiViewModel = hiltViewModel(),
) {
    // Without location permission the platform hands back an empty list rather
    // than an error, so the screen has to gate rather than show "no networks".
    PermissionGate(
        bundle = PermissionBundle.WIFI_SCAN,
        coordinator = viewModel.permissionCoordinator,
        modifier = modifier,
    ) {
        WifiContent(viewModel = viewModel, modifier = modifier)
    }
}

@Composable
private fun WifiContent(
    viewModel: WifiViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.start() }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
        ) {
            WifiViewMode.entries.forEach { mode ->
                FilterChip(
                    selected = uiState.mode == mode,
                    onClick = { viewModel.onModeChange(mode) },
                    label = { Text(stringResource(mode.labelRes())) },
                )
            }
        }

        ScanControls(
            uiState = uiState,
            onScan = viewModel::requestScan,
            onOpenDeveloperOptions = { context.openDeveloperOptions() },
        )

        when (uiState.mode) {
            WifiViewMode.NETWORKS -> NetworkList(
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )

            WifiViewMode.CHANNELS -> ChannelView(
                uiState = uiState,
                onBandChange = viewModel::onDiagramBandChange,
                modifier = Modifier.weight(1f),
            )

            WifiViewMode.HISTORY -> HistoryView(
                uiState = uiState,
                modifier = Modifier.weight(1f),
            )

            WifiViewMode.CONNECTION -> ConnectionView(
                connection = uiState.connection,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ScanControls(
    uiState: WifiUiState,
    onScan: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
) {
    Button(
        onClick = onScan,
        enabled = uiState.canScanNow,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text(
            if (uiState.canScanNow) {
                stringResource(R.string.wifi_scan_now)
            } else {
                stringResource(R.string.wifi_scan_in, uiState.throttleSecondsRemaining)
            },
        )
    }

    if (!uiState.canScanNow) {
        Text(
            text = stringResource(R.string.wifi_throttle_explanation),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onOpenDeveloperOptions) {
            Text(stringResource(R.string.wifi_open_developer_options))
        }
    }
}

@Composable
private fun NetworkList(
    uiState: WifiUiState,
    viewModel: WifiViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = uiState.query,
            onValueChange = viewModel::onQueryChange,
            label = { Text(stringResource(R.string.wifi_search_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
        ) {
            FilterChip(
                selected = uiState.bandFilter == null,
                onClick = { viewModel.onBandFilterChange(null) },
                label = { Text(stringResource(R.string.wifi_band_all)) },
            )
            SCANNABLE_BANDS.forEach { band ->
                FilterChip(
                    selected = uiState.bandFilter == band,
                    onClick = { viewModel.onBandFilterChange(band) },
                    label = { Text(band.label) },
                )
            }
            FilterChip(
                selected = uiState.onlyWatched,
                onClick = { viewModel.onOnlyWatchedChange(!uiState.onlyWatched) },
                label = { Text(stringResource(R.string.wifi_only_watched)) },
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
        ) {
            WifiSortOrder.entries.forEach { order ->
                FilterChip(
                    selected = uiState.sortOrder == order,
                    onClick = { viewModel.onSortOrderChange(order) },
                    label = { Text(stringResource(order.labelRes())) },
                )
            }
        }

        uiState.recommended2_4Channel?.let { channel ->
            Text(
                text = stringResource(R.string.wifi_recommended_channel, channel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        val networks = uiState.visibleNetworks
        if (networks.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.wifi_empty_title),
                description = stringResource(R.string.wifi_empty_description),
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                items(networks, key = { it.bssid }) { network ->
                    NetworkRow(
                        network = network,
                        isWatched = network.bssid in uiState.watchedBssids,
                        onToggleWatch = { viewModel.onToggleWatch(network.bssid) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelView(
    uiState: WifiUiState,
    onBandChange: (WifiBand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            SCANNABLE_BANDS.forEach { band ->
                FilterChip(
                    selected = uiState.diagramBand == band,
                    onClick = { onBandChange(band) },
                    label = { Text(band.label) },
                )
            }
        }

        val inBand = uiState.networks.count { it.band == uiState.diagramBand }
        Text(
            text = stringResource(R.string.wifi_networks_in_band, inBand),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        ChannelDiagram(
            networks = uiState.networks,
            band = uiState.diagramBand,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (uiState.diagramBand == WifiBand.BAND_2_4_GHZ) {
            uiState.recommended2_4Channel?.let { channel ->
                Text(
                    text = stringResource(R.string.wifi_recommended_channel, channel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HistoryView(
    uiState: WifiUiState,
    modifier: Modifier = Modifier,
) {
    val charted = uiState.chartedBssids.filter { (uiState.history[it]?.size ?: 0) >= 2 }

    if (charted.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.wifi_history_empty_title),
            description = stringResource(R.string.wifi_history_empty_description),
            modifier = modifier,
        )
        return
    }

    val series = charted.mapIndexed { index, bssid ->
        ChartSeries(
            label = uiState.ssidOf(bssid),
            color = SERIES_COLORS[index % SERIES_COLORS.size],
            points = uiState.history[bssid].orEmpty().map { it.rssiDbm.toFloat() },
        )
    }

    Column(modifier = modifier.padding(top = 12.dp)) {
        LineChart(
            series = series,
            valueRange = -100f..-30f,
            // The worst reading in a window is what a technician acts on when
            // aiming an antenna; averaging would smooth the dropouts away.
            bucketStrategy = BucketStrategy.MIN,
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
        ChartLegend(series = series, modifier = Modifier.padding(top = 8.dp))
        Text(
            text = stringResource(R.string.wifi_history_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ConnectionView(
    connection: WifiConnection?,
    modifier: Modifier = Modifier,
) {
    if (connection == null) {
        EmptyState(
            title = stringResource(R.string.wifi_connection_none_title),
            description = stringResource(R.string.wifi_connection_none_description),
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
        DetailRow(stringResource(R.string.wifi_detail_ssid), connection.ssid)
        DetailRow(stringResource(R.string.wifi_detail_bssid), connection.bssid)
        DetailRow(
            stringResource(R.string.wifi_detail_signal),
            connection.rssiDbm?.let { "$it dBm" },
        )
        DetailRow(
            stringResource(R.string.wifi_detail_channel),
            connection.channel?.let { "$it (${connection.band.label})" },
        )
        DetailRow(
            stringResource(R.string.wifi_detail_frequency),
            connection.frequencyMhz?.let { "$it MHz" },
        )
        DetailRow(
            stringResource(R.string.wifi_detail_link_speed),
            listOfNotNull(
                connection.txLinkSpeedMbps?.let { "TX $it Mbit/s" },
                connection.rxLinkSpeedMbps?.let { "RX $it Mbit/s" },
            ).joinToString(" · ").takeIf { it.isNotEmpty() },
        )
        DetailRow(stringResource(R.string.wifi_detail_interface), connection.interfaceName)
        DetailRow(stringResource(R.string.wifi_detail_ipv4), connection.ipv4Address)
        DetailRow(
            stringResource(R.string.wifi_detail_ipv6),
            connection.ipv6Addresses.joinToString("\n").takeIf { it.isNotEmpty() },
        )
        DetailRow(stringResource(R.string.wifi_detail_gateway), connection.gateway)
        DetailRow(
            stringResource(R.string.wifi_detail_dns),
            connection.dnsServers.joinToString(", ").takeIf { it.isNotEmpty() },
        )
        DetailRow(stringResource(R.string.wifi_detail_mtu), connection.mtu?.toString())

        if (connection.isCaptivePortal) {
            Text(
                text = stringResource(R.string.wifi_captive_portal),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (connection.isMetered) {
            Text(
                text = stringResource(R.string.wifi_metered),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value ?: stringResource(R.string.wifi_detail_unavailable),
            style = MonospaceTextStyle,
            modifier = Modifier.weight(1.4f),
        )
    }
    HorizontalDivider()
}

@Composable
private fun NetworkRow(
    network: WifiNetwork,
    isWatched: Boolean,
    onToggleWatch: () -> Unit,
) {
    val quality = SignalMetric.WIFI_RSSI.classify(network.rssiDbm.toFloat())

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = network.displayName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${network.rssiDbm} dBm",
                style = MaterialTheme.typography.labelLarge,
                color = quality.color(),
            )
        }

        // Quality is never carried by colour alone.
        Text(
            text = stringResource(quality.labelRes()),
            style = MaterialTheme.typography.labelSmall,
            color = quality.color(),
        )

        Text(text = network.bssid, style = MonospaceTextStyle)

        Text(
            text = buildString {
                append(network.band.label)
                network.channel?.let { append(" · Ch $it") }
                append(" · ${network.frequencyMhz} MHz")
                network.channelWidthMhz?.let { append(" · $it MHz") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = network.security.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (network.security.isWeak) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = network.standard.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onToggleWatch) {
                Text(
                    stringResource(
                        if (isWatched) R.string.wifi_unwatch else R.string.wifi_watch,
                    ),
                )
            }
        }
    }
    HorizontalDivider()
}

private fun WifiViewMode.labelRes(): Int = when (this) {
    WifiViewMode.NETWORKS -> R.string.wifi_mode_networks
    WifiViewMode.CHANNELS -> R.string.wifi_mode_channels
    WifiViewMode.HISTORY -> R.string.wifi_mode_history
    WifiViewMode.CONNECTION -> R.string.wifi_mode_connection
}

private fun WifiSortOrder.labelRes(): Int = when (this) {
    WifiSortOrder.SIGNAL -> R.string.wifi_sort_signal
    WifiSortOrder.SSID -> R.string.wifi_sort_ssid
    WifiSortOrder.CHANNEL -> R.string.wifi_sort_channel
}

private val SCANNABLE_BANDS =
    listOf(WifiBand.BAND_2_4_GHZ, WifiBand.BAND_5_GHZ, WifiBand.BAND_6_GHZ)

private val SERIES_COLORS = listOf(
    Color(0xFF0B6FA4),
    Color(0xFFB26B00),
    Color(0xFF1B7F3B),
    Color(0xFF8E44AD),
    Color(0xFFC0392B),
)
