package de.nettoolbox.feature.fileserver.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import de.nettoolbox.feature.fileserver.ui.FileServerScreen
import kotlinx.serialization.Serializable

@Serializable
data object FileServerRoute

fun NavController.navigateToFileServer(navOptions: NavOptions? = null) =
    navigate(route = FileServerRoute, navOptions = navOptions)

fun NavGraphBuilder.fileServerScreen() {
    composable<FileServerRoute> {
        FileServerScreen()
    }
}
