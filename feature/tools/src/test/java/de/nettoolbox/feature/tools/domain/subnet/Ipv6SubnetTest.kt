package de.nettoolbox.feature.tools.domain.subnet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger

class Ipv6SubnetTest {

    @Test
    fun `calculates a 64 prefix`() {
        val subnet = Ipv6Subnet.parse("2001:db8::1/64")

        assertEquals("2001:db8::", subnet.formatNetwork())
        assertEquals("2001:db8::ffff:ffff:ffff:ffff", subnet.formatLastAddress())
        assertEquals(BigInteger("18446744073709551616"), subnet.totalAddresses)
    }

    @Test
    fun `compresses the longest run of zero groups`() {
        assertEquals("::1", Ipv6Subnet.parse("::1/128").formatNetwork())
        assertEquals("2001:db8::", Ipv6Subnet.parse("2001:db8::/32").formatNetwork())
    }

    @Test
    fun `a bare address is treated as a single host`() {
        val subnet = Ipv6Subnet.parse("2001:db8::5")

        assertEquals(128, subnet.prefixLength)
        assertEquals(BigInteger.ONE, subnet.totalAddresses)
    }

    @Test
    fun `containment respects the prefix`() {
        val subnet = Ipv6Subnet.parse("2001:db8::/32")

        assertTrue(Ipv6Subnet.parse("2001:db8:1234::1").address in subnet)
        assertFalse(Ipv6Subnet.parse("2001:db9::1").address in subnet)
    }

    @Test
    fun `a host name is rejected instead of resolved`() {
        // The parser must never touch the network: a typo has to be an error,
        // not a DNS lookup.
        assertNull(Ipv6Subnet.parseOrNull("example.com"))
        assertNull(Ipv6Subnet.parseOrNull("not an address"))
    }

    @Test
    fun `an IPv4 address is not accepted as IPv6`() {
        assertNull(Ipv6Subnet.parseOrNull("192.168.1.1"))
    }

    @Test
    fun `an out-of-range prefix is rejected`() {
        assertNull(Ipv6Subnet.parseOrNull("2001:db8::1/129"))
    }
}
