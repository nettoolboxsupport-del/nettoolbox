package de.nettoolbox.feature.fileserver.tftp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The packet layer.
 *
 * Worth testing directly because this code reads bytes that arrive on an
 * unauthenticated UDP port. Everything here is either "does it parse a real
 * client's request" or "does it refuse something malformed instead of reading
 * past the end of the buffer".
 */
class TftpCodecTest {

    /** TFTP separates every field with a NUL byte. */
    private val NUL = "\u0000"

    private fun request(opcode: Int, vararg fields: String): ByteArray {
        val body = fields.joinToString(NUL, postfix = NUL).toByteArray()
        return ByteArray(2 + body.size).also {
            TftpCodec.writeShort(it, 0, opcode)
            body.copyInto(it, 2)
        }
    }

    @Test
    @DisplayName("a plain read request parses")
    fun parsesReadRequest() {
        val bytes = request(TftpOpcode.RRQ, "firmware.bin", "octet")
        val parsed = TftpCodec.parseRequest(bytes, bytes.size)!!

        assertEquals(false, parsed.write)
        assertEquals("firmware.bin", parsed.filename)
        assertEquals(TftpMode.OCTET, parsed.mode)
        assertTrue(parsed.options.isEmpty())
    }

    @Test
    @DisplayName("a write request is told apart by its opcode")
    fun parsesWriteRequest() {
        val bytes = request(TftpOpcode.WRQ, "config.cfg", "octet")
        assertEquals(true, TftpCodec.parseRequest(bytes, bytes.size)!!.write)
    }

    @Test
    @DisplayName("option names are matched without regard to case")
    fun optionNamesAreCaseInsensitive() {
        // RFC 2347 says option names are case-insensitive, and real clients do
        // send "Blksize". Matching case-sensitively would silently drop the
        // negotiation and fall back to 512-byte blocks.
        val bytes = request(
            TftpOpcode.RRQ,
            "image.bin", "OCTET",
            "BlkSize", "1468",
            "TIMEOUT", "3",
            "windowsize", "8",
        )
        val parsed = TftpCodec.parseRequest(bytes, bytes.size)!!

        assertEquals(TftpMode.OCTET, parsed.mode)
        assertEquals("1468", parsed.options["blksize"])
        assertEquals("3", parsed.options["timeout"])
        assertEquals("8", parsed.options["windowsize"])
    }

    @Test
    @DisplayName("netascii is recognised, anything else is not")
    fun recognisesModes() {
        val netascii = request(TftpOpcode.RRQ, "readme.txt", "netascii")
        assertEquals(TftpMode.NETASCII, TftpCodec.parseRequest(netascii, netascii.size)!!.mode)

        // "mail" was removed from the protocol decades ago. It has to parse as
        // unsupported rather than as octet, or a client would receive bytes it
        // asked to have transformed.
        val mail = request(TftpOpcode.RRQ, "x", "mail")
        assertEquals(TftpMode.UNSUPPORTED, TftpCodec.parseRequest(mail, mail.size)!!.mode)
    }

    @Test
    @DisplayName("malformed input is refused rather than half-read")
    fun refusesMalformedPackets() {
        // Too short to hold an opcode and anything else.
        assertNull(TftpCodec.parseRequest(ByteArray(2), 2))

        // A DATA packet arriving where a request was expected.
        val data = TftpCodec.data(1, ByteArray(4), 4)
        assertNull(TftpCodec.parseRequest(data, data.size))

        // Filename present, mode missing.
        val truncated = request(TftpOpcode.RRQ, "only-a-name")
        assertNull(TftpCodec.parseRequest(truncated, truncated.size))

        // An option name with no value. Accepting this would pair the option
        // with whatever followed it in memory.
        val oddFields = request(TftpOpcode.RRQ, "f", "octet", "blksize")
        assertNull(TftpCodec.parseRequest(oddFields, oddFields.size))
    }

    @Test
    @DisplayName("a name without its terminator is dropped, not salvaged")
    fun dropsUnterminatedField() {
        // No trailing NUL after "octet". A half-read filename is exactly the
        // kind of thing that turns into a path check on a partial string.
        val body = "file\u0000octet".toByteArray()
        val bytes = ByteArray(2 + body.size)
        TftpCodec.writeShort(bytes, 0, TftpOpcode.RRQ)
        body.copyInto(bytes, 2)

        assertNull(TftpCodec.parseRequest(bytes, bytes.size))
    }

    @Test
    @DisplayName("DATA carries only the bytes it was given")
    fun dataPacketLength() {
        val payload = ByteArray(1468) { it.toByte() }
        val packet = TftpCodec.data(7, payload, 100)

        assertEquals(104, packet.size)
        assertEquals(TftpOpcode.DATA, TftpCodec.opcodeOf(packet, packet.size))
        assertEquals(7, TftpCodec.blockOf(packet, packet.size))
    }

    @Test
    @DisplayName("block numbers above 32767 stay positive")
    fun blockNumbersAreUnsigned() {
        // Read as a signed short this would come back negative, and every
        // comparison against the expected block would then fail - on files
        // past 16 MB only.
        val packet = TftpCodec.data(65535, ByteArray(1), 1)
        assertEquals(65535, TftpCodec.blockOf(packet, packet.size))

        val ack = TftpCodec.ack(40000)
        assertEquals(40000, TftpCodec.blockOf(ack, ack.size))
    }

    @Test
    @DisplayName("OACK holds NUL-terminated pairs and nothing more")
    fun oackEncoding() {
        val packet = TftpCodec.oack(linkedMapOf("blksize" to "1468", "tsize" to "4096"))

        assertEquals(TftpOpcode.OACK, TftpCodec.opcodeOf(packet, packet.size))
        // 2 opcode + "blksize\0" + "1468\0" + "tsize\0" + "4096\0"
        assertEquals(2 + 8 + 5 + 6 + 5, packet.size)
        assertEquals(0.toByte(), packet[packet.size - 1])
    }

    @Test
    @DisplayName("ERROR is terminated, so a client can read the message")
    fun errorEncoding() {
        val packet = TftpCodec.error(TftpError.FILE_NOT_FOUND, "no such file")

        assertEquals(TftpOpcode.ERROR, TftpCodec.opcodeOf(packet, packet.size))
        assertEquals(TftpError.FILE_NOT_FOUND, TftpCodec.blockOf(packet, packet.size))
        assertEquals(0.toByte(), packet[packet.size - 1])
    }
}
