package de.nettoolbox.feature.cellular.domain

/**
 * Band lookup from channel numbers.
 *
 * **Scope and provenance:** these tables cover the bands deployed in Europe plus
 * the common global ones. They are a curated subset, not the complete 3GPP
 * allocation, and they are transcribed by hand - verify against 3GPP TS 36.101
 * (E-UTRA) and TS 38.104 (NR) before relying on a band label in a report.
 * [lteBandOf] returns null rather than guessing when a channel is outside the
 * table, and the UI shows the raw ARFCN in that case.
 *
 * Pure, so every boundary is unit-tested: an off-by-one at a band edge produces a
 * plausible-looking wrong band, which is the worst kind of error in a drive test.
 */
object ArfcnBands {

    // ---- LTE / E-UTRA -------------------------------------------------------

    /** Downlink EARFCN ranges, inclusive. */
    private val LTE_BANDS: List<Triple<Int, IntRange, String>> = listOf(
        Triple(1, 0..599, "2100"),
        Triple(2, 600..1199, "1900"),
        Triple(3, 1200..1949, "1800"),
        Triple(4, 1950..2399, "1700/2100"),
        Triple(5, 2400..2649, "850"),
        Triple(7, 2750..3449, "2600"),
        Triple(8, 3450..3799, "900"),
        Triple(11, 4750..4949, "1500"),
        Triple(12, 5010..5179, "700"),
        Triple(13, 5180..5279, "700"),
        Triple(14, 5280..5379, "700"),
        Triple(17, 5730..5849, "700"),
        Triple(18, 5850..5999, "850"),
        Triple(19, 6000..6149, "850"),
        Triple(20, 6150..6449, "800"),
        Triple(21, 6450..6599, "1500"),
        Triple(25, 8040..8689, "1900"),
        Triple(26, 8690..9039, "850"),
        Triple(28, 9210..9659, "700"),
        Triple(32, 9770..9869, "1500 (SDL)"),
        Triple(38, 37750..38249, "2600 (TDD)"),
        Triple(39, 38250..38649, "1900 (TDD)"),
        Triple(40, 38650..39649, "2300 (TDD)"),
        Triple(41, 39650..41589, "2500 (TDD)"),
        Triple(42, 41590..43589, "3500 (TDD)"),
        Triple(43, 43590..45589, "3700 (TDD)"),
    )

    data class BandInfo(val number: Int, val label: String) {
        override fun toString(): String = "B$number ($label)"
    }

    fun lteBandOf(earfcn: Int?): BandInfo? {
        if (earfcn == null || earfcn < 0) return null
        return LTE_BANDS.firstOrNull { earfcn in it.second }
            ?.let { BandInfo(it.first, it.third) }
    }

    // ---- NR -----------------------------------------------------------------

    /**
     * NR-ARFCN to frequency in MHz, per TS 38.104 section 5.4.2.1.
     *
     * The three ranges use different raster steps, which is why this is computed
     * rather than tabulated: a single table of NR-ARFCN ranges would be enormous
     * and would still need the formula to be correct at the seams.
     */
    fun nrFrequencyMhz(nrarfcn: Int?): Double? {
        if (nrarfcn == null || nrarfcn < 0) return null
        return when {
            nrarfcn <= 599_999 -> nrarfcn * 0.005
            nrarfcn <= 2_016_666 -> 3000.0 + (nrarfcn - 600_000) * 0.015
            nrarfcn <= 3_279_165 -> 24_250.08 + (nrarfcn - 2_016_667) * 0.06
            else -> null
        }
    }

    /** Downlink frequency ranges in MHz, most specific first. */
    private val NR_BANDS: List<Triple<Int, ClosedFloatingPointRange<Double>, String>> = listOf(
        Triple(28, 758.0..803.0, "700"),
        Triple(20, 791.0..821.0, "800"),
        Triple(8, 925.0..960.0, "900"),
        Triple(3, 1805.0..1880.0, "1800"),
        Triple(1, 2110.0..2170.0, "2100"),
        Triple(40, 2300.0..2400.0, "2300 (TDD)"),
        Triple(38, 2570.0..2620.0, "2600 (TDD)"),
        Triple(7, 2620.0..2690.0, "2600"),
        Triple(41, 2496.0..2690.0, "2500 (TDD)"),
        // n78 is the subset of n77 that Europe deploys, so it is checked first.
        Triple(78, 3300.0..3800.0, "3500 (TDD)"),
        Triple(77, 3300.0..4200.0, "3700 (TDD)"),
        Triple(79, 4400.0..5000.0, "4700 (TDD)"),
        Triple(258, 24_250.0..27_500.0, "26 GHz (FR2)"),
        Triple(257, 26_500.0..29_500.0, "28 GHz (FR2)"),
        Triple(261, 27_500.0..28_350.0, "28 GHz (FR2)"),
        Triple(260, 37_000.0..40_000.0, "39 GHz (FR2)"),
    )

    fun nrBandOf(nrarfcn: Int?): BandInfo? {
        val frequency = nrFrequencyMhz(nrarfcn) ?: return null
        return NR_BANDS.firstOrNull { frequency in it.second }
            ?.let { BandInfo(it.first, it.third) }
    }

    // ---- UMTS ---------------------------------------------------------------

    /** Downlink UARFCN ranges. */
    private val UMTS_BANDS: List<Triple<Int, IntRange, String>> = listOf(
        Triple(1, 10562..10838, "2100"),
        Triple(2, 9662..9938, "1900"),
        Triple(4, 1537..1738, "1700/2100"),
        Triple(5, 4357..4458, "850"),
        Triple(8, 2937..3088, "900"),
    )

    fun umtsBandOf(uarfcn: Int?): BandInfo? {
        if (uarfcn == null || uarfcn < 0) return null
        return UMTS_BANDS.firstOrNull { uarfcn in it.second }
            ?.let { BandInfo(it.first, it.third) }
    }

    // ---- GSM ----------------------------------------------------------------

    fun gsmBandOf(arfcn: Int?): BandInfo? = when (arfcn) {
        null -> null
        in 0..124 -> BandInfo(900, "P-GSM 900")
        in 975..1023 -> BandInfo(900, "E-GSM 900")
        in 512..885 -> BandInfo(1800, "DCS 1800")
        in 128..251 -> BandInfo(850, "GSM 850")
        else -> null
    }

    /**
     * Rough distance from the timing advance.
     *
     * One LTE TA step is about 78 metres of round-trip path. This is an upper
     * bound on the distance and nothing more - reflections and non-line-of-sight
     * make the real distance shorter, never longer, so it is labelled as an
     * estimate everywhere it is shown.
     */
    fun timingAdvanceToMeters(timingAdvance: Int?): Int? {
        if (timingAdvance == null || timingAdvance < 0) return null
        return timingAdvance * LTE_TA_STEP_METERS
    }

    private const val LTE_TA_STEP_METERS = 78
}
