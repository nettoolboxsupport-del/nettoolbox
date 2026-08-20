package de.nettoolbox.feature.wifi.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import de.nettoolbox.feature.wifi.WifiScreen
import kotlinx.serialization.Serializable

@Serializable
data object WifiRoute

fun NavController.navigateToWifi(navOptions: NavOptions? = null) =
    navigate(route = WifiRoute, navOptions = navOptions)

fun NavGraphBuilder.wifiScreen() {
    composable<WifiRoute> {
        WifiScreen()
    }
}
