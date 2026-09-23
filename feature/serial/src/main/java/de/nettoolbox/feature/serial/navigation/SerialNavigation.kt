package de.nettoolbox.feature.serial.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import de.nettoolbox.feature.serial.ui.SerialScreen
import kotlinx.serialization.Serializable

@Serializable
data object SerialRoute

fun NavController.navigateToSerial(navOptions: NavOptions? = null) =
    navigate(route = SerialRoute, navOptions = navOptions)

fun NavGraphBuilder.serialScreen() {
    composable<SerialRoute> {
        SerialScreen()
    }
}
