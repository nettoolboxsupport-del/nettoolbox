package de.nettoolbox.feature.tools.domain.ping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * The three output dialects the app has to survive on real devices.
 */
class PingOutputParserTest {

    @Test
    fun `parses the iputils reply format`() {
        val line = "64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=12.3 ms"

        val reply = assertInstanceOf(PingLine.Reply::class.java, PingOutputParser.parseLine(line))
        assertEquals(1, reply.sequence)
        assertEquals("1.1.1.1", reply.from)
        assertEquals(57, reply.ttl)
        assertEquals(12.3, reply.rttMillis)
    }

    @Test
    fun `parses the busybox reply format which omits the icmp_ prefix`() {
        val line = "64 bytes from 192.168.1.1: seq=0 ttl=64 time=1.234 ms"

        val reply = assertInstanceOf(PingLine.Reply::class.java, PingOutputParser.parseLine(line))
        assertEquals(0, reply.sequence)
        assertEquals(1.234, reply.rttMillis)
    }

    @Test
    fun `prefers the address in parentheses over the resolved name`() {
        val line = "64 bytes from one.one.one.one (1.1.1.1): icmp_seq=3 ttl=57 time=11.0 ms"

        val reply = assertInstanceOf(PingLine.Reply::class.java, PingOutputParser.parseLine(line))
        assertEquals("1.1.1.1", reply.from)
        assertEquals(3, reply.sequence)
    }

    @Test
    fun `parses a sub-millisecond reply written with a less-than sign`() {
        val line = "64 bytes from 10.0.0.1: icmp_seq=1 ttl=64 time<1 ms"

        val reply = assertInstanceOf(PingLine.Reply::class.java, PingOutputParser.parseLine(line))
        assertEquals(1.0, reply.rttMillis)
    }

    @Test
    fun `recognises an expired TTL, which traceroute depends on`() {
        val line = "From 10.0.0.1 icmp_seq=1 Time to live exceeded"

        val hop = assertInstanceOf(PingLine.TtlExceeded::class.java, PingOutputParser.parseLine(line))
        assertEquals("10.0.0.1", hop.from)
        assertEquals(1, hop.sequence)
    }

    @Test
    fun `recognises an unreachable destination`() {
        val line = "From 192.168.1.1 icmp_seq=2 Destination Host Unreachable"

        val loss = assertInstanceOf(PingLine.Unreachable::class.java, PingOutputParser.parseLine(line))
        assertEquals("192.168.1.1", loss.from)
    }

    @Test
    fun `banner and summary lines are ignored rather than misread`() {
        val ignored = listOf(
            "PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.",
            "--- 1.1.1.1 ping statistics ---",
            "4 packets transmitted, 4 received, 0% packet loss, time 3005ms",
            "rtt min/avg/max/mdev = 11.0/12.1/13.4/0.9 ms",
            "",
        )

        ignored.forEach { line ->
            assertEquals(PingLine.Ignored, PingOutputParser.parseLine(line), "should ignore: $line")
        }
    }
}
