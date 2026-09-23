package de.nettoolbox.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import de.nettoolbox.feature.serial.navigation.navigateToSerial

/**
 * @param serialConsoleRequest increases each time a USB serial adapter was
 *   plugged in and the system handed the event to this app. Every new value
 *   opens the serial console once; zero means no request.
 */
@Composable
fun NetToolboxApp(
    modifier: Modifier = Modifier,
    serialConsoleRequest: Long = 0L,
) {
    val navController = rememberNavController()

    // An effect rather than a direct call: it runs after the NavHost below has
    // been composed and has its graph, which navigate() requires.
    LaunchedEffect(serialConsoleRequest) {
        if (serialConsoleRequest > 0) navController.navigateToSerial()
    }
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
