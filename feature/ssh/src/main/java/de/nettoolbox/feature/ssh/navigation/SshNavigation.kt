package de.nettoolbox.feature.ssh.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import de.nettoolbox.feature.ssh.ui.SshScreen
import kotlinx.serialization.Serializable

@Serializable
data object SshRoute

fun NavController.navigateToSsh(navOptions: NavOptions? = null) =
    navigate(route = SshRoute, navOptions = navOptions)

fun NavGraphBuilder.sshScreen() {
    composable<SshRoute> {
        SshScreen()
    }
}
