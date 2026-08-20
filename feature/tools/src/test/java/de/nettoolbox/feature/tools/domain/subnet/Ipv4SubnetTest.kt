package de.nettoolbox.feature.tools.domain.subnet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ipv4SubnetTest {

    @Test
    fun `calculates a standard 24 network`() {
        val subnet = Ipv4Subnet.parse("192.168.1.10/24")

        assertEquals("192.168.1.0", subnet.network.toString())
        assertEquals("255.255.255.0", subnet.netmask.toString())
        assertEquals("0.0.0.255", subnet.wildcard.toString())
        assertEquals("192.168.1.255", subnet.broadcast.toString())
        assertEquals("192.168.1.1", subnet.firstUsableHost.toString())
        assertEquals("192.168.1.254", subnet.lastUsableHost.toString())
        assertEquals(254L, subnet.usableHosts)
        assertEquals(256L, subnet.totalAddresses)
    }

    @Test
    fun `a 31 is a point-to-point link with two usable addresses`() {
        val subnet = Ipv4Subnet.parse("10.0.0.0/31")

        assertTrue(subnet.isPointToPoint)
        assertEquals(2L, subnet.usableHosts)
        assertEquals("10.0.0.0", subnet.firstUsableHost.toString())
        assertEquals("10.0.0.1", subnet.lastUsableHost.toString())
    }

    @Test
    fun `a 32 is a single host`() {
        val subnet = Ipv4Subnet.parse("10.0.0.7/32")

        assertTrue(subnet.isSingleHost)
        assertEquals(1L, subnet.usableHosts)
        assertEquals("10.0.0.7", subnet.firstUsableHost.toString())
    }

    @Test
    fun `a 0 covers the whole address space`() {
        val subnet = Ipv4Subnet.parse("0.0.0.0/0")

        assertEquals("0.0.0.0", subnet.netmask.toString())
        assertEquals(4_294_967_296L, subnet.totalAddresses)
        assertTrue(Ipv4Address.parse("8.8.8.8") in subnet)
    }

    @Test
    fun `accepts a dotted netmask instead of a prefix length`() {
        val subnet = Ipv4Subnet.parse("10.0.0.1 255.255.255.0")

        assertEquals(24, subnet.prefixLength)
        assertEquals("10.0.0.0", subnet.network.toString())
    }

    @Test
    fun `rejects a netmask with holes`() {
        assertThrows(IllegalArgumentException::class.java) {
            Ipv4Subnet.parse("10.0.0.1 255.255.0.255")
        }
    }

    @Test
    fun `rejects octets with a leading zero`() {
        // 010 is octal in some resolvers; accepting it silently would compute a
        // different network than the user is looking at.
        assertNull(Ipv4Address.parseOrNull("192.168.010.1"))
    }

    @Test
    fun `rejects out-of-range octets and malformed input`() {
        assertNull(Ipv4Address.parseOrNull("192.168.1.256"))
        assertNull(Ipv4Address.parseOrNull("192.168.1"))
        assertNull(Ipv4Address.parseOrNull("192.168.1.1.1"))
        assertNull(Ipv4Address.parseOrNull("192.168.a.1"))
    }

    @Test
    fun `splits a 24 into four 26 networks`() {
        val parts = Ipv4Subnet.parse("192.168.1.0/24").split(26)

        assertEquals(4, parts.size)
        assertEquals(
            listOf("192.168.1.0", "192.168.1.64", "192.168.1.128", "192.168.1.192"),
            parts.map { it.network.toString() },
        )
        assertTrue(parts.all { it.prefixLength == 26 })
    }

    @Test
    fun `refuses a split that would materialise millions of subnets`() {
        assertThrows(IllegalArgumentException::class.java) {
            Ipv4Subnet.parse("10.0.0.0/8").split(30)
        }
    }

    @Test
    fun `joins two adjacent halves into their supernet`() {
        val lower = Ipv4Subnet.parse("10.0.0.0/25")
        val upper = Ipv4Subnet.parse("10.0.0.128/25")

        assertEquals("10.0.0.0/24", lower.supernetWith(upper).toString())
    }

    @Test
    fun `refuses to join networks that are not the two halves of one supernet`() {
        val a = Ipv4Subnet.parse("10.0.0.0/24")
        val b = Ipv4Subnet.parse("10.0.2.0/24")

        assertNull(a.supernetWith(b))
        assertNull(a.supernetWith(a))
    }

    @Test
    fun `containment works for addresses and for nested networks`() {
        val outer = Ipv4Subnet.parse("10.0.0.0/8")

        assertTrue(Ipv4Address.parse("10.1.2.3") in outer)
        assertFalse(Ipv4Address.parse("11.0.0.1") in outer)
        assertTrue(Ipv4Subnet.parse("10.1.0.0/16") in outer)
        assertFalse(Ipv4Subnet.parse("192.168.0.0/16") in outer)
    }

    @Test
    fun `renders binary in dotted octets`() {
        assertEquals(
            "11000000.10101000.00000001.00001010",
            Ipv4Address.parse("192.168.1.10").toBinaryString(),
        )
    }

    @Test
    fun `derives the prefix length only from contiguous masks`() {
        assertEquals(24, Ipv4Address.prefixLengthOf(Ipv4Address.parse("255.255.255.0")))
        assertEquals(0, Ipv4Address.prefixLengthOf(Ipv4Address.parse("0.0.0.0")))
        assertEquals(32, Ipv4Address.prefixLengthOf(Ipv4Address.parse("255.255.255.255")))
        assertNull(Ipv4Address.prefixLengthOf(Ipv4Address.parse("255.0.255.0")))
    }
}
