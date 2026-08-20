package de.nettoolbox.feature.tools.domain.dns

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnsMessageCodecTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII).map { it.toInt() and 0xFF }

    /** Header + question for www.example.com, the prefix of every response below. */
    private fun responsePrefix(flags: Int, answerCount: Int): List<Int> = buildList {
        addAll(listOf(0x12, 0x34))
        addAll(listOf((flags shr 8) and 0xFF, flags and 0xFF))
        addAll(listOf(0x00, 0x01)) // QDCOUNT
        addAll(listOf(0x00, answerCount)) // ANCOUNT
        addAll(listOf(0x00, 0x00)) // NSCOUNT
        addAll(listOf(0x00, 0x00)) // ARCOUNT

        add(3); addAll(ascii("www"))
        add(7); addAll(ascii("example"))
        add(3); addAll(ascii("com"))
        add(0)
        addAll(listOf(0x00, 0x01)) // QTYPE A
        addAll(listOf(0x00, 0x01)) // QCLASS IN
    }

    private fun decode(vararg tail: Int, flags: Int = 0x8180, answers: Int = 1): DnsResponse {
        val message = (responsePrefix(flags, answers) + tail.toList()).map { it.toByte() }.toByteArray()
        return DnsMessageCodec.decode(message)
    }

    // ---- encoding -----------------------------------------------------------

    @Test
    fun `encodes the header of a recursive query`() {
        val query = DnsMessageCodec.encodeQuery(0x1234, DnsQuestion("example.com", DnsRecordType.A))

        assertArrayEquals(
            bytes(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01),
            query.copyOfRange(0, 12),
            "id, RD flag, one question and one additional record for OPT",
        )
    }

    @Test
    fun `encodes the name as length-prefixed labels`() {
        val query = DnsMessageCodec.encodeQuery(1, DnsQuestion("example.com", DnsRecordType.A))

        val expected = (listOf(7) + ascii("example") + listOf(3) + ascii("com") + listOf(0))
            .map { it.toByte() }.toByteArray()

        assertArrayEquals(expected, query.copyOfRange(12, 12 + expected.size))
    }

    @Test
    fun `a trailing dot in the name is accepted and not encoded twice`() {
        val withDot = DnsMessageCodec.encodeQuery(1, DnsQuestion("example.com.", DnsRecordType.A))
        val without = DnsMessageCodec.encodeQuery(1, DnsQuestion("example.com", DnsRecordType.A))

        assertArrayEquals(without, withDot)
    }

    @Test
    fun `rejects a name with an empty label`() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsMessageCodec.encodeQuery(1, DnsQuestion("example..com", DnsRecordType.A))
        }
    }

    // ---- decoding -----------------------------------------------------------

    @Test
    fun `decodes an A record whose owner name is a compression pointer`() {
        val response = decode(
            0xC0, 0x0C, // pointer to the question name at offset 12
            0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x01, 0x2C, // TTL 300
            0x00, 0x04,
            93, 184, 216, 34,
        )

        assertEquals(0x1234, response.id)
        assertEquals(DnsResponseCode.NOERROR, response.responseCode)
        assertTrue(response.recursionAvailable)
        assertFalse(response.truncated)
        assertEquals("www.example.com", response.question?.name)

        val record = response.answers.single()
        assertEquals("www.example.com", record.name)
        assertEquals(DnsRecordType.A, record.type)
        assertEquals(300L, record.ttlSeconds)
        assertEquals("93.184.216.34", record.data)
    }

    @Test
    fun `decodes an AAAA record in compressed notation`() {
        val response = decode(
            0xC0, 0x0C,
            0x00, 0x1C, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x3C,
            0x00, 0x10,
            0x20, 0x01, 0x0D, 0xB8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01,
        )

        assertEquals("2001:db8::1", response.answers.single().data)
    }

    @Test
    fun `decodes an MX record with preference and a compressed target`() {
        val response = decode(
            0xC0, 0x0C,
            0x00, 0x0F, 0x00, 0x01,
            0x00, 0x00, 0x01, 0x2C,
            0x00, 0x09,
            0x00, 0x0A, // preference 10
            0x04, 0x6D, 0x61, 0x69, 0x6C, // "mail"
            0xC0, 0x0C,
        )

        assertEquals("10 mail.www.example.com", response.answers.single().data)
    }

    @Test
    fun `decodes a TXT record as quoted character strings`() {
        val response = decode(
            0xC0, 0x0C,
            0x00, 0x10, 0x00, 0x01,
            0x00, 0x00, 0x01, 0x2C,
            0x00, 0x06,
            0x05, 0x68, 0x65, 0x6C, 0x6C, 0x6F, // "hello"
        )

        assertEquals("\"hello\"", response.answers.single().data)
    }

    @Test
    fun `decodes an SRV record`() {
        val response = decode(
            0xC0, 0x0C,
            0x00, 0x21, 0x00, 0x01,
            0x00, 0x00, 0x01, 0x2C,
            0x00, 0x0C, // 6 bytes of numbers + "sip" label + 2-byte pointer
            0x00, 0x0A, // priority
            0x00, 0x14, // weight
            0x01, 0xBB, // port 443
            0x03, 0x73, 0x69, 0x70, // "sip"
            0xC0, 0x0C,
        )

        assertEquals("10 20 443 sip.www.example.com", response.answers.single().data)
    }

    @Test
    fun `reports NXDOMAIN instead of treating it as a transport failure`() {
        val response = decode(flags = 0x8183, answers = 0)

        assertEquals(DnsResponseCode.NXDOMAIN, response.responseCode)
        assertTrue(response.answers.isEmpty())
    }

    @Test
    fun `reads the AD flag that a validating resolver sets`() {
        val response = decode(flags = 0x81A0, answers = 0)

        assertTrue(response.authenticatedData)
    }

    @Test
    fun `keeps unknown record types as hex rather than dropping them`() {
        val response = decode(
            0xC0, 0x0C,
            0x00, 0x63, 0x00, 0x01, // type 99, not in the enum
            0x00, 0x00, 0x01, 0x2C,
            0x00, 0x02,
            0xDE, 0xAD,
        )

        val record = response.answers.single()
        assertEquals(null, record.type)
        assertEquals(99, record.typeCode)
        assertEquals("dead", record.rawData)
    }

    @Test
    fun `refuses a message shorter than its header`() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsMessageCodec.decode(bytes(0x12, 0x34, 0x81, 0x80))
        }
    }

    @Test
    fun `refuses a compression pointer that points at itself`() {
        // Offset 12 is the answer name here, so the pointer loops forever unless
        // the jump counter stops it.
        val message = (
            listOf(
                0x12, 0x34, 0x81, 0x80,
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00,
                0xC0, 0x0C,
            )
            ).map { it.toByte() }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            DnsMessageCodec.decode(message)
        }
    }
}
