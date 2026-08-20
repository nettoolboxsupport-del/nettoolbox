package de.nettoolbox.feature.tools.domain.wol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MagicPacketTest {

    private val mac = byteArrayOf(
        0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
        0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
    )

    @Test
    fun `accepts the three common MAC notations`() {
        assertArrayEquals(mac, MagicPacket.parseMac("AA:BB:CC:DD:EE:FF"))
        assertArrayEquals(mac, MagicPacket.parseMac("aa-bb-cc-dd-ee-ff"))
        assertArrayEquals(mac, MagicPacket.parseMac("aabbccddeeff"))
    }

    @Test
    fun `rejects anything that is not six bytes of hex`() {
        assertNull(MagicPacket.parseMac("AA:BB:CC:DD:EE"))
        assertNull(MagicPacket.parseMac("AA:BB:CC:DD:EE:FF:00"))
        assertNull(MagicPacket.parseMac("GG:BB:CC:DD:EE:FF"))
        assertNull(MagicPacket.parseMac(""))
    }

    @Test
    fun `the packet is 102 bytes`() {
        assertEquals(102, MagicPacket.build(mac).size)
    }

    @Test
    fun `the packet starts with six 0xFF bytes`() {
        val packet = MagicPacket.build(mac)

        repeat(6) { index ->
            assertEquals(0xFF.toByte(), packet[index], "byte $index must be 0xFF")
        }
    }

    @Test
    fun `the MAC is repeated exactly sixteen times after the header`() {
        val packet = MagicPacket.build(mac)

        repeat(16) { repetition ->
            val offset = 6 + repetition * 6
            assertArrayEquals(
                mac,
                packet.copyOfRange(offset, offset + 6),
                "repetition $repetition is wrong",
            )
        }
    }

    @Test
    fun `formats a MAC in upper case with colons`() {
        assertEquals("AA:BB:CC:DD:EE:FF", MagicPacket.formatMac(mac))
    }
}
