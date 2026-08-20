package de.nettoolbox.core.common.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class SignalQualityTest {

    @ParameterizedTest
    @CsvSource(
        "-70, EXCELLENT",
        "-80, EXCELLENT",
        "-85, GOOD",
        "-90, GOOD",
        "-95, FAIR",
        "-100, FAIR",
        "-105, POOR",
        "-110, POOR",
        "-120, BAD",
    )
    fun `RSRP is classified on the inclusive lower bound of each level`(
        value: Float,
        expected: SignalQuality,
    ) {
        assertEquals(expected, SignalMetric.RSRP.classify(value))
    }

    @Test
    fun `a missing value is UNKNOWN rather than BAD`() {
        assertEquals(SignalQuality.UNKNOWN, SignalMetric.RSRP.classify(null))
        assertEquals(SignalQuality.UNKNOWN, SignalMetric.RSRP.classify(Float.NaN))
    }

    @Test
    fun `custom thresholds override the defaults`() {
        val strict = SignalThresholds(excellent = -60f, good = -70f, fair = -80f, poor = -90f)

        assertEquals(SignalQuality.FAIR, SignalMetric.RSRP.classify(-80f, strict))
        assertEquals(SignalQuality.EXCELLENT, SignalMetric.RSRP.classify(-80f))
    }

    @Test
    fun `thresholds must be strictly descending`() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalThresholds(excellent = -90f, good = -80f, fair = -100f, poor = -110f)
        }
    }

    @Test
    fun `fraction clamps outside the plausible range`() {
        assertEquals(0f, SignalMetric.RSRP.fraction(-200f))
        assertEquals(1f, SignalMetric.RSRP.fraction(0f))
        assertEquals(0.5f, SignalMetric.RSRP.fraction(-90f), 0.001f)
    }

    @Test
    fun `fraction of a missing value is zero`() {
        assertEquals(0f, SignalMetric.SINR.fraction(null))
    }
}
