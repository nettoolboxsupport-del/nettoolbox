package de.nettoolbox.feature.tools.navigation

import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import de.nettoolbox.core.database.entity.ToolType
import de.nettoolbox.feature.tools.R
import de.nettoolbox.feature.tools.ToolsScreen
import de.nettoolbox.feature.tools.ui.common.ToolScaffold
import de.nettoolbox.feature.tools.ui.dns.DnsScreen
import de.nettoolbox.feature.tools.ui.history.ToolHistoryScreen
import de.nettoolbox.feature.tools.ui.http.HttpInspectorScreen
import de.nettoolbox.feature.tools.ui.ipscan.IpScanScreen
import de.nettoolbox.feature.tools.ui.ping.PingScreen
import de.nettoolbox.feature.tools.ui.portscan.PortScanScreen
import de.nettoolbox.feature.tools.ui.subnet.SubnetScreen
import de.nettoolbox.feature.tools.ui.traceroute.TracerouteScreen
import de.nettoolbox.feature.tools.ui.wol.WolScreen
import kotlinx.serialization.Serializable

/**
 * Marks "no run to restore". A default value rather than a nullable Long: the
 * type-safe navigation arguments support Long directly, and a nullable primitive
 * would need a custom NavType for no benefit.
 */
const val NO_RUN_ID: Long = -1L

@Serializable
data object ToolsRoute

@Serializable
data class PingRoute(val runId: Long = NO_RUN_ID)

@Serializable
data class DnsRoute(val runId: Long = NO_RUN_ID)

@Serializable
data class PortScanRoute(val runId: Long = NO_RUN_ID)

@Serializable
data object HttpInspectorRoute

@Serializable
data object IpScanRoute

@Serializable
data class TracerouteRoute(val runId: Long = NO_RUN_ID)

@Serializable
data object SubnetRoute

@Serializable
data object WolRoute

/** [toolTypeName] is the enum's name; a String keeps the route free of a custom NavType. */
@Serializable
data class ToolHistoryRoute(val toolTypeName: String)

fun NavController.navigateToTools(navOptions: NavOptions? = null) =
    navigate(route = ToolsRoute, navOptions = navOptions)

/**
 * The tools tab and its sub-screens.
 *
 * Every tool is wrapped in the same [ToolScaffold], so the run history is reached
 * the same way everywhere instead of being a feature some tools happen to have.
 */
fun NavGraphBuilder.toolsScreen(navController: NavController) {
    composable<ToolsRoute> {
        ToolsScreen(
            onOpenPing = { navController.navigate(PingRoute()) },
            onOpenSubnet = { navController.navigate(SubnetRoute) },
            onOpenWol = { navController.navigate(WolRoute) },
            onOpenDns = { navController.navigate(DnsRoute()) },
            onOpenPortScan = { navController.navigate(PortScanRoute()) },
            onOpenHttpInspector = { navController.navigate(HttpInspectorRoute) },
            onOpenIpScan = { navController.navigate(IpScanRoute) },
            onOpenTraceroute = { navController.navigate(TracerouteRoute()) },
        )
    }

    composable<IpScanRoute> {
        ToolScaffold(
            title = stringResource(R.string.tool_ip_scan),
            onShowHistory = { navController.navigateToHistory(ToolType.IP_SCAN) },
        ) { modifier -> IpScanScreen(modifier = modifier) }
    }

    composable<TracerouteRoute> {
        ToolScaffold(
            title = stringResource(R.string.tool_traceroute),
            onShowHistory = { navController.navigateToHistory(ToolType.TRACEROUTE) },
        ) { modifier -> TracerouteScreen(modifier = modifier) }
    }

    composable<HttpInspectorRoute> {
        ToolScaffold(
            title = stringResource(R.string.tool_http_tls),
            onShowHistory = { navController.navigateToHistory(ToolType.HTTP_TLS) },
        ) { modifier -> HttpInspectorScreen(modifier = modifier) }
    }

    composable<PingRoute> {
        ToolScaffold(
            title = stringResource(R.string.tool_ping),
            onShowHistory = { navController.navigateToHistory(ToolType.PING) },
        ) { modifier -> PingScreen(modifier = modifier) }
    }

    composable<DnsRoute> {
        ToolScaffold(
            title = stringResource(R.string.tool_dns),
            onShowHistory = { navController.navigateToHistory(ToolType.DNS) },
        ) { modifier -> DnsScreen(modifier = modifier) }
    }

    composable<PortScanRoute> {
        ToolScaffold(
            title = stringResource(R.string.tool_port_scan),
            onShowHistory = { navController.navigateToHistory(ToolType.PORT_SCAN) },
        ) { modifier -> PortScanScreen(modifier = modifier) }
    }

    composable<SubnetRoute> {
        // No history: the calculator recomputes on every keystroke, so recording
        // runs would bury the tools that actually touch the network.
        ToolScaffold(title = stringResource(R.string.tool_subnet)) { modifier ->
            SubnetScreen(modifier = modifier)
        }
    }

    composable<WolRoute> {
        // Previously woken devices already appear as chips on the screen itself.
        ToolScaffold(title = stringResource(R.string.tool_wol)) { modifier ->
            WolScreen(modifier = modifier)
        }
    }

    composable<ToolHistoryRoute> {
        ToolScaffold(title = stringResource(R.string.history_title)) { modifier ->
            ToolHistoryScreen(
                modifier = modifier,
                onRepeat = { run -> navController.repeatRun(run.toolType, run.id) },
            )
        }
    }
}

/**
 * Leaves the history and opens the tool with the stored parameters.
 *
 * Pop first, then navigate: the tool re-opens with its run id as an argument,
 * and the history list does not stay behind on the back stack.
 */
private fun NavController.repeatRun(toolType: ToolType, runId: Long) {
    popBackStack()
    when (toolType) {
        ToolType.PING -> navigate(PingRoute(runId))
        ToolType.DNS -> navigate(DnsRoute(runId))
        ToolType.PORT_SCAN -> navigate(PortScanRoute(runId))
        ToolType.TRACEROUTE -> navigate(TracerouteRoute(runId))
        // Every other tool either has no parameters worth restoring or offers
        // them on the screen itself.
        else -> Unit
    }
}

private fun NavController.navigateToHistory(toolType: ToolType) =
    navigate(ToolHistoryRoute(toolType.name))
