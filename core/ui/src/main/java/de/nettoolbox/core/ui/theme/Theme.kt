package de.nettoolbox.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Theme selection as offered in the settings.
 *
 * [FIELD] is the outdoor mode from the spec: a pure-black, high-contrast scheme
 * for direct sunlight. It deliberately ignores dynamic color.
 */
enum class NetToolboxThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    FIELD,
}

private val LightColors = lightColorScheme(
    primary = BlueSignal,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = BlueContainer,
    onPrimaryContainer = BlueContainerDark,
    secondary = AmberField,
    secondaryContainer = AmberContainer,
    background = SlateSurface,
    surface = SlateSurface,
    error = ErrorRed,
)

private val DarkColors = darkColorScheme(
    primary = BlueSignalDark,
    primaryContainer = BlueContainerDark,
    onPrimaryContainer = BlueContainer,
    secondary = AmberFieldDark,
    secondaryContainer = AmberContainerDark,
    background = SlateSurfaceDark,
    surface = SlateSurfaceDark,
    error = ErrorRedDark,
)

private val FieldColors = darkColorScheme(
    primary = FieldPrimary,
    onPrimary = FieldBackground,
    secondary = FieldSecondary,
    onSecondary = FieldBackground,
    background = FieldBackground,
    onBackground = FieldOnSurface,
    surface = FieldSurface,
    onSurface = FieldOnSurface,
    surfaceVariant = FieldSurfaceVariant,
    onSurfaceVariant = FieldOnSurface,
    outline = FieldOutline,
    error = ErrorRedDark,
)

@Composable
fun NetToolboxTheme(
    themeMode: NetToolboxThemeMode = NetToolboxThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        NetToolboxThemeMode.SYSTEM -> isSystemInDarkTheme()
        NetToolboxThemeMode.LIGHT -> false
        NetToolboxThemeMode.DARK, NetToolboxThemeMode.FIELD -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        themeMode == NetToolboxThemeMode.FIELD -> FieldColors
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NetToolboxTypography,
        content = content,
    )
}
