package de.nettoolbox.feature.cellular.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import de.nettoolbox.feature.cellular.CellularScreen
import de.nettoolbox.feature.cellular.ui.sessions.SessionsScreen
import kotlinx.serialization.Serializable

/**
 * Type-safe route. Each feature owns its route type and its `NavGraphBuilder`
 * extension, so `:app` never has to know the feature's internal screens.
 */
@Serializable
data object CellularRoute

@Serializable
data object SessionsRoute

fun NavController.navigateToCellular(navOptions: NavOptions? = null) =
    navigate(route = CellularRoute, navOptions = navOptions)

/**
 * @param onShowSessionOnMap supplied by `:app`, because the map lives in another
 *   feature module and this one must not depend on it
 */
fun NavGraphBuilder.cellularScreen(
    navController: NavController,
    onShowSessionOnMap: (Long) -> Unit,
) {
    composable<CellularRoute> {
        CellularScreen(onOpenSessions = { navController.navigate(SessionsRoute) })
    }
    composable<SessionsRoute> {
        SessionsScreen(onShowOnMap = onShowSessionOnMap)
    }
}
