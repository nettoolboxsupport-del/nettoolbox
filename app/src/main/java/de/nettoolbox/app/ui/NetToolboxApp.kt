package de.nettoolbox.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import de.nettoolbox.app.navigation.NetToolboxNavHost
import de.nettoolbox.app.navigation.TopLevelDestination
import de.nettoolbox.app.navigation.isInHierarchyOf
import de.nettoolbox.app.navigation.navigateToTopLevel

@Composable
fun NetToolboxApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination

    val topLevelDestinations = remember { TopLevelDestination.entries }
    // Full-screen destinations such as the map hide the bar instead of shrinking
    // the viewport.
    val showBottomBar = topLevelDestinations.any { currentDestination.isInHierarchyOf(it) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevelDestinations.forEach { destination ->
                        val selected = currentDestination.isInHierarchyOf(destination)
                        val label = stringResource(destination.labelRes)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(destination) },
                            icon = {
                                Icon(
                                    painter = painterResource(destination.icon),
                                    // The label below already names the destination,
                                    // so the icon stays decorative for TalkBack.
                                    contentDescription = null,
                                )
                            },
                            label = { Text(text = label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NetToolboxNavHost(
            navController = navController,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}
