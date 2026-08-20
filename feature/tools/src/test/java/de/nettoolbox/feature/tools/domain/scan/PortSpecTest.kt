package de.nettoolbox.feature.tools.domain.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PortSpecTest {

    @Test
    fun `parses single ports, ranges and mixtures`() {
        assertEquals(listOf(22), PortSpec.parse("22"))
        assertEquals(listOf(80, 81, 82), PortSpec.parse("80-82"))
        assertEquals(listOf(22, 80, 443, 8080), PortSpec.parse("443,22,8080,80"))
    }

    @Test
    fun `ignores whitespace and collapses duplicates`() {
        assertEquals(listOf(22, 80), PortSpec.parse(" 22 , 80 , 22 "))
        assertEquals(listOf(80, 81, 82), PortSpec.parse("80-82,81"))
    }

    @Test
    fun `rejects ports outside 1 to 65535`() {
        assertThrows(IllegalArgumentException::class.java) { PortSpec.parse("0") }
        assertThrows(IllegalArgumentException::class.java) { PortSpec.parse("65536") }
        assertThrows(IllegalArgumentException::class.java) { PortSpec.parse("-5") }
    }

    @Test
    fun `rejects a range that runs backwards`() {
        assertThrows(IllegalArgumentException::class.java) { PortSpec.parse("443-80") }
    }

    @Test
    fun `rejects malformed input instead of guessing`() {
        assertThrows(IllegalArgumentException::class.java) { PortSpec.parse("") }
        assertThrows(IllegalArgumentException::class.java) { PortSpec.parse("22,,80") }
        assertThrows(IllegalArgumentException::class.java) { PortSpec.parse("http") }
        assertNull(PortSpec.parseOrNull("22-"))
    }

    @Test
    fun `refuses a selection larger than the scan limit`() {
        // A typo like 1-65535 must not turn into a full sweep of someone's network.
        assertThrows(IllegalArgumentException::class.java) { PortSpec.parse("1-65535") }
    }

    @Test
    fun `presets are sorted, unique and within range`() {
        listOf(PortSpec.QUICK_PORTS, PortSpec.COMMON_PORTS).forEach { preset ->
            assertEquals(preset.sorted(), preset, "preset must be sorted")
            assertEquals(preset.distinct().size, preset.size, "preset must be free of duplicates")
            assert(preset.all { it in PortSpec.MIN_PORT..PortSpec.MAX_PORT })
        }
    }

    @Test
    fun `knows the services a technician looks for`() {
        assertEquals("ssh", PortSpec.serviceName(22))
        assertEquals("snmp", PortSpec.serviceName(161))
        assertEquals("jetdirect", PortSpec.serviceName(9100))
        assertEquals("iperf3", PortSpec.serviceName(5201))
        assertNull(PortSpec.serviceName(12345))
    }
}
