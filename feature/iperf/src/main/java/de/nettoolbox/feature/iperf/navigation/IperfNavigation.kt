package de.nettoolbox.feature.iperf.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import de.nettoolbox.feature.iperf.ui.Iperf3Screen
import kotlinx.serialization.Serializable

@Serializable
data object IperfRoute

fun NavController.navigateToIperf(navOptions: NavOptions? = null) =
    navigate(route = IperfRoute, navOptions = navOptions)

fun NavGraphBuilder.iperfScreen() {
    composable<IperfRoute> {
        Iperf3Screen()
    }
}
