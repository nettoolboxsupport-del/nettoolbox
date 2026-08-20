package de.nettoolbox.app.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.navOptions
import de.nettoolbox.feature.cellular.navigation.navigateToCellular
import de.nettoolbox.feature.tools.navigation.navigateToTools
import de.nettoolbox.feature.wifi.navigation.navigateToWifi

/**
 * Switching bottom-bar tabs pops back to the start destination but saves and
 * restores each tab's own back stack, so a half-configured tool run is still
 * there after a detour into the Wi-Fi list.
 */
fun NavController.navigateToTopLevel(destination: TopLevelDestination) {
    val topLevelNavOptions = navOptions {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }

    when (destination) {
        TopLevelDestination.DASHBOARD -> navigateToDashboard(topLevelNavOptions)
        TopLevelDestination.CELLULAR -> navigateToCellular(topLevelNavOptions)
        TopLevelDestination.WIFI -> navigateToWifi(topLevelNavOptions)
        TopLevelDestination.TOOLS -> navigateToTools(topLevelNavOptions)
    }
}

fun NavDestination?.isInHierarchyOf(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination.route) } == true
