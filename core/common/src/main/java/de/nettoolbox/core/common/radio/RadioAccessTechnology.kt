package de.nettoolbox.core.common.radio

/**
 * Radio access technology of a cell.
 *
 * NR is split into NSA and SA because they behave differently in the field and
 * Android reports them through different channels: NSA is only visible via
 * `TelephonyDisplayInfo`, while the cell info list still shows the LTE anchor.
 * Collapsing both into one "5G" value would make a drive-test log unusable.
 */
enum class RadioAccessTechnology {
    GSM,
    UMTS,
    LTE,
    NR_NSA,
    NR_SA,
    UNKNOWN,
    ;

    val isFiveG: Boolean get() = this == NR_NSA || this == NR_SA

    companion object {
        fun fromNameOrUnknown(name: String?): RadioAccessTechnology =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}
