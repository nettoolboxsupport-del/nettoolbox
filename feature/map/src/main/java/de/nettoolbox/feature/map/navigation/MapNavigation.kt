package de.nettoolbox.feature.map.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import de.nettoolbox.feature.map.ui.MapScreen
import kotlinx.serialization.Serializable

/** Marks "no session selected"; a nullable Long would need a custom NavType. */
const val NO_SESSION_ID: Long = -1L

/**
 * The map is a full-screen destination reached from cellular and Wi-Fi, not a
 * bottom-bar entry - hence its own route type instead of a nested tab.
 */
@Serializable
data class MapRoute(val sessionId: Long = NO_SESSION_ID)

fun NavController.navigateToMap(
    sessionId: Long = NO_SESSION_ID,
    navOptions: NavOptions? = null,
) = navigate(route = MapRoute(sessionId), navOptions = navOptions)

fun NavGraphBuilder.mapScreen() {
    composable<MapRoute> {
        MapScreen()
    }
}
