package de.nettoolbox.app.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import de.nettoolbox.app.R
import de.nettoolbox.core.ui.icon.NetToolboxIcons
import de.nettoolbox.feature.cellular.navigation.CellularRoute
import de.nettoolbox.feature.tools.navigation.ToolsRoute
import de.nettoolbox.feature.wifi.navigation.WifiRoute
import kotlin.reflect.KClass
import de.nettoolbox.feature.cellular.R as CellularR
import de.nettoolbox.feature.tools.R as ToolsR
import de.nettoolbox.feature.wifi.R as WifiR

/**
 * The four bottom-bar destinations. Labels come from the owning feature module so
 * a screen title and its navigation label can never drift apart.
 */
enum class TopLevelDestination(
    val route: KClass<*>,
    @param:DrawableRes val icon: Int,
    @param:StringRes val labelRes: Int,
) {
    DASHBOARD(
        route = DashboardRoute::class,
        icon = NetToolboxIcons.Dashboard,
        labelRes = R.string.destination_dashboard,
    ),
    CELLULAR(
        route = CellularRoute::class,
        icon = NetToolboxIcons.Cellular,
        labelRes = CellularR.string.feature_cellular_title,
    ),
    WIFI(
        route = WifiRoute::class,
        icon = NetToolboxIcons.Wifi,
        labelRes = WifiR.string.feature_wifi_title,
    ),
    TOOLS(
        route = ToolsRoute::class,
        icon = NetToolboxIcons.Tools,
        labelRes = ToolsR.string.feature_tools_title,
    ),
}
