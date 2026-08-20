package de.nettoolbox.core.ui.icon

import androidx.annotation.DrawableRes
import de.nettoolbox.core.ui.R

/**
 * The app's icon set.
 *
 * These are hand-drawn vector drawables rather than `material-icons-extended`:
 * that artifact carries several thousand icons of which the app uses a handful,
 * and it dominated the APK size of the phase-0 build. Owning the set also means
 * the domain icons still to come (RAT types, bands, signal quality) live in the
 * same place and share one visual language.
 */
object NetToolboxIcons {

    @DrawableRes val Dashboard: Int = R.drawable.ic_dashboard

    @DrawableRes val Cellular: Int = R.drawable.ic_cellular

    @DrawableRes val Wifi: Int = R.drawable.ic_wifi

    @DrawableRes val Tools: Int = R.drawable.ic_tools

    @DrawableRes val Speed: Int = R.drawable.ic_speed

    @DrawableRes val Map: Int = R.drawable.ic_map

    @DrawableRes val Share: Int = R.drawable.ic_share

    @DrawableRes val Copy: Int = R.drawable.ic_copy

    @DrawableRes val Export: Int = R.drawable.ic_export

    @DrawableRes val Refresh: Int = R.drawable.ic_refresh

    @DrawableRes val History: Int = R.drawable.ic_history

    @DrawableRes val Settings: Int = R.drawable.ic_settings

    @DrawableRes val Warning: Int = R.drawable.ic_warning

    @DrawableRes val Lock: Int = R.drawable.ic_lock

    @DrawableRes val Search: Int = R.drawable.ic_search

    @DrawableRes val Stop: Int = R.drawable.ic_stop

    @DrawableRes val Play: Int = R.drawable.ic_play

    @DrawableRes val Terminal: Int = R.drawable.ic_terminal
}
