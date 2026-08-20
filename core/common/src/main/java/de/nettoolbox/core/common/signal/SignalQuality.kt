package de.nettoolbox.core.common.signal

/**
 * Coarse quality bucket for any signal metric. Five levels, because that is what
 * a technician actually acts on: three is too blunt to spot a marginal cell,
 * ten is noise.
 */
enum class SignalQuality {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    BAD,
    UNKNOWN,
}

/**
 * Boundaries between quality levels, in the metric's own unit. A value is
 * [SignalQuality.EXCELLENT] when it is at or above [excellent], and so on
 * downwards; anything below [poor] is [SignalQuality.BAD].
 *
 * Kept as data so the settings screen can override them - field crews disagree
 * about where "good" ends, and the spec calls for configurable thresholds.
 */
data class SignalThresholds(
    val excellent: Float,
    val good: Float,
    val fair: Float,
    val poor: Float,
) {
    init {
        require(excellent > good && good > fair && fair > poor) {
            "Thresholds must be strictly descending: $excellent > $good > $fair > $poor"
        }
    }
}

/**
 * The signal metrics the app displays, with the plausible value range used to
 * scale gauges and charts, and the default thresholds.
 *
 * Ranges are the practical spans seen in the field, not the full 3GPP reporting
 * ranges - a gauge scaled to the theoretical minimum wastes most of its arc.
 */
enum class SignalMetric(
    val unit: String,
    val range: ClosedFloatingPointRange<Float>,
    val defaultThresholds: SignalThresholds,
) {
    /** LTE/NR reference signal received power. */
    RSRP(
        unit = "dBm",
        range = -140f..-40f,
        defaultThresholds = SignalThresholds(-80f, -90f, -100f, -110f),
    ),

    /** LTE/NR reference signal received quality. */
    RSRQ(
        unit = "dB",
        range = -25f..-3f,
        defaultThresholds = SignalThresholds(-10f, -15f, -20f, -22f),
    ),

    /** Signal to interference plus noise ratio. */
    SINR(
        unit = "dB",
        range = -20f..30f,
        defaultThresholds = SignalThresholds(20f, 13f, 0f, -5f),
    ),

    /** Received signal strength indicator on cellular. */
    RSSI(
        unit = "dBm",
        range = -120f..-30f,
        defaultThresholds = SignalThresholds(-65f, -75f, -85f, -95f),
    ),

    /** UMTS received signal code power. */
    RSCP(
        unit = "dBm",
        range = -125f..-25f,
        defaultThresholds = SignalThresholds(-75f, -85f, -95f, -105f),
    ),

    /** UMTS energy per chip over noise. */
    ECNO(
        unit = "dB",
        range = -24f..0f,
        defaultThresholds = SignalThresholds(-6f, -9f, -12f, -15f),
    ),

    /** Wi-Fi received signal strength. */
    WIFI_RSSI(
        unit = "dBm",
        range = -100f..-30f,
        defaultThresholds = SignalThresholds(-55f, -65f, -75f, -85f),
    ),
    ;

    fun classify(
        value: Float?,
        thresholds: SignalThresholds = defaultThresholds,
    ): SignalQuality = when {
        value == null || value.isNaN() -> SignalQuality.UNKNOWN
        value >= thresholds.excellent -> SignalQuality.EXCELLENT
        value >= thresholds.good -> SignalQuality.GOOD
        value >= thresholds.fair -> SignalQuality.FAIR
        value >= thresholds.poor -> SignalQuality.POOR
        else -> SignalQuality.BAD
    }

    /**
     * Position of [value] inside [range], clamped to 0..1. Used to drive gauge
     * sweep and chart scaling.
     */
    fun fraction(value: Float?): Float {
        if (value == null || value.isNaN()) return 0f
        val span = range.endInclusive - range.start
        if (span <= 0f) return 0f
        return ((value - range.start) / span).coerceIn(0f, 1f)
    }
}
