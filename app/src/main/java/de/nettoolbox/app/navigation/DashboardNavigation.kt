package de.nettoolbox.app.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import de.nettoolbox.app.ui.about.AboutScreen
import de.nettoolbox.app.ui.dashboard.DashboardScreen
import de.nettoolbox.app.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
data object DashboardRoute

@Serializable
data object SettingsRoute

@Serializable
data object AboutRoute

fun NavController.navigateToDashboard(navOptions: NavOptions? = null) =
    navigate(route = DashboardRoute, navOptions = navOptions)

fun NavGraphBuilder.dashboardScreen(
    onOpenCellular: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenIperf: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenSsh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    composable<DashboardRoute> {
        DashboardScreen(
            onOpenCellular = onOpenCellular,
            onOpenWifi = onOpenWifi,
            onOpenTools = onOpenTools,
            onOpenIperf = onOpenIperf,
            onOpenMap = onOpenMap,
            onOpenSsh = onOpenSsh,
            onOpenSettings = onOpenSettings,
        )
    }
}

/**
 * Settings are a full-screen destination rather than a bottom-bar tab: they are
 * opened rarely and would cost a quarter of the navigation bar permanently.
 */
fun NavGraphBuilder.settingsScreen(onOpenAbout: () -> Unit) {
    composable<SettingsRoute> {
        SettingsScreen(onOpenAbout = onOpenAbout)
    }
}

/**
 * Licences and attribution.
 *
 * A separate destination rather than a section inside settings: two of the data
 * sources are used under licences that require the attribution to be findable,
 * and burying it under a scroll position is not that.
 */
fun NavGraphBuilder.aboutScreen() {
    composable<AboutRoute> {
        AboutScreen()
    }
}
