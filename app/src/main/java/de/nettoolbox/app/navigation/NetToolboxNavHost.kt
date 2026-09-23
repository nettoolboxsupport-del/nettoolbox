package de.nettoolbox.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import de.nettoolbox.feature.cellular.navigation.cellularScreen
import de.nettoolbox.feature.fileserver.navigation.fileServerScreen
import de.nettoolbox.feature.fileserver.navigation.navigateToFileServer
import de.nettoolbox.feature.iperf.navigation.iperfScreen
import de.nettoolbox.feature.iperf.navigation.navigateToIperf
import de.nettoolbox.feature.map.navigation.mapScreen
import de.nettoolbox.feature.map.navigation.navigateToMap
import de.nettoolbox.feature.serial.navigation.navigateToSerial
import de.nettoolbox.feature.serial.navigation.serialScreen
import de.nettoolbox.feature.ssh.navigation.navigateToSsh
import de.nettoolbox.feature.ssh.navigation.sshScreen
import de.nettoolbox.feature.tools.navigation.toolsScreen
import de.nettoolbox.feature.wifi.navigation.wifiScreen

@Composable
fun NetToolboxNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = DashboardRoute,
        modifier = modifier,
    ) {
        dashboardScreen(
            onOpenCellular = { navController.navigateToTopLevel(TopLevelDestination.CELLULAR) },
            onOpenWifi = { navController.navigateToTopLevel(TopLevelDestination.WIFI) },
            onOpenTools = { navController.navigateToTopLevel(TopLevelDestination.TOOLS) },
            onOpenIperf = { navController.navigateToIperf() },
            onOpenMap = { navController.navigateToMap() },
            onOpenSsh = { navController.navigateToSsh() },
            onOpenFileServer = { navController.navigateToFileServer() },
            onOpenSerial = { navController.navigateToSerial() },
            onOpenSettings = { navController.navigate(SettingsRoute) },
        )
        settingsScreen(onOpenAbout = { navController.navigate(AboutRoute) })
        aboutScreen()
        cellularScreen(
            navController = navController,
            // Wired here because the map is a separate feature module and
            // :feature:cellular must not depend on it.
            onShowSessionOnMap = { sessionId -> navController.navigateToMap(sessionId) },
        )
        wifiScreen()
        toolsScreen(navController)

        // Reached from the dashboard tiles and, from phase 3/4 on, from within
        // the cellular and Wi-Fi screens.
        iperfScreen()
        mapScreen()
        sshScreen()
        fileServerScreen()
        serialScreen()
    }
}
