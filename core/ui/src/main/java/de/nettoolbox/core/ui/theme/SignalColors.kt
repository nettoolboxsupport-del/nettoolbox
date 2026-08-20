package de.nettoolbox.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import de.nettoolbox.core.common.signal.SignalQuality

// Two sets, because a green that passes contrast on white is unreadable on the
// black field theme and vice versa. Which set applies is decided from the actual
// surface luminance, so it also works under dynamic color.

private val QualityLight = mapOf(
    SignalQuality.EXCELLENT to Color(0xFF1B7F3B),
    SignalQuality.GOOD to Color(0xFF5A8A16),
    SignalQuality.FAIR to Color(0xFF9A6B00),
    SignalQuality.POOR to Color(0xFFB55418),
    SignalQuality.BAD to Color(0xFFB3261E),
)

private val QualityDark = mapOf(
    SignalQuality.EXCELLENT to Color(0xFF6FD98D),
    SignalQuality.GOOD to Color(0xFFAFD96B),
    SignalQuality.FAIR to Color(0xFFFFC94D),
    SignalQuality.POOR to Color(0xFFFFA26B),
    SignalQuality.BAD to Color(0xFFFF8A80),
)

/**
 * Colour for a quality level.
 *
 * Never the only carrier of the information - every place that uses this also
 * prints the level as text, as required by the accessibility rules in the spec.
 */
@Composable
@ReadOnlyComposable
fun SignalQuality.color(): Color {
    val scheme = MaterialTheme.colorScheme
    if (this == SignalQuality.UNKNOWN) return scheme.outline
    val dark = scheme.surface.luminance() < 0.5f
    return (if (dark) QualityDark else QualityLight).getValue(this)
}
