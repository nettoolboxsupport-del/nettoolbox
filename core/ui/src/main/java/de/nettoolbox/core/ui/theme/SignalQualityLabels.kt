package de.nettoolbox.core.ui.theme

import androidx.annotation.StringRes
import de.nettoolbox.core.common.signal.SignalQuality
import de.nettoolbox.core.ui.R

/**
 * Text label for a quality level. Paired with [color] everywhere, so quality is
 * never encoded by colour alone.
 */
@StringRes
fun SignalQuality.labelRes(): Int = when (this) {
    SignalQuality.EXCELLENT -> R.string.signal_quality_excellent
    SignalQuality.GOOD -> R.string.signal_quality_good
    SignalQuality.FAIR -> R.string.signal_quality_fair
    SignalQuality.POOR -> R.string.signal_quality_poor
    SignalQuality.BAD -> R.string.signal_quality_bad
    SignalQuality.UNKNOWN -> R.string.signal_quality_unknown
}
