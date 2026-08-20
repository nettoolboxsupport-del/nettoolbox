package de.nettoolbox.feature.tools.domain.dns

import de.nettoolbox.feature.tools.domain.subnet.Ipv6Subnet
import java.math.BigInteger

/**
 * Encoder and decoder for the DNS wire format (RFC 1035).
 *
 * Written by hand rather than pulled from a library: the same encoded message is
 * used over UDP, TCP, DoT and DoH, so one codec covers all four transports, and
 * a hand-written one can be unit-tested byte for byte.
 *
 * Pure Kotlin, no Android APIs, no sockets - the transports own the I/O.
 */
object DnsMessageCodec {

    const val CLASS_IN = 1
    const val HEADER_SIZE = 12
    const val TYPE_OPT = 41

    /**
     * EDNS0 payload size. 1232 bytes is the value the DNS Flag Day 2020
     * recommendation settled on: large enough for DNSSEC answers, small enough to
     * avoid IP fragmentation on paths with a 1280-byte MTU.
     */
    const val EDNS_PAYLOAD_SIZE = 1232

    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_NAME_LENGTH = 255
    private const val MAX_POINTER_JUMPS = 64

    /**
     * @param dnssecOk sets the DO bit, which is what makes a validating resolver
     *   return the AD flag in a form worth showing
     */
    fun encodeQuery(
        id: Int,
        question: DnsQuestion,
        recursionDesired: Boolean = true,
        dnssecOk: Boolean = true,
    ): ByteArray {
        val out = ArrayList<Byte>(64)

        out.addU16(id and 0xFFFF)
        out.addU16(if (recursionDesired) 0x0100 else 0x0000)
        out.addU16(1) // QDCOUNT
        out.addU16(0) // ANCOUNT
        out.addU16(0) // NSCOUNT
        out.addU16(1) // ARCOUNT - the OPT record below

        out.addName(question.name)
        out.addU16(question.type.code)
        out.addU16(CLASS_IN)

        // OPT pseudo-record: root name, type 41, class carries the payload size,
        // TTL carries the extended rcode and the DO bit.
        out.add(0)
        out.addU16(TYPE_OPT)
        out.addU16(EDNS_PAYLOAD_SIZE)
        out.addU16(0) // extended rcode + version
        out.addU16(if (dnssecOk) 0x8000 else 0x0000)
        out.addU16(0) // RDLENGTH

        return out.toByteArray()
    }

    fun decode(message: ByteArray): DnsResponse {
        require(message.size >= HEADER_SIZE) { "DNS message shorter than its header" }

        val reader = MessageReader(message)
        val id = reader.u16()
        val flags = reader.u16()
        val questionCount = reader.u16()
        val answerCount = reader.u16()
        val authorityCount = reader.u16()
        val additionalCount = reader.u16()

        val question = if (questionCount > 0) {
            val name = reader.name()
            val type = reader.u16()
            reader.u16() // class
            // Repeat any further questions off the wire; in practice there is one.
            repeat(questionCount - 1) {
                reader.name()
                reader.u16()
                reader.u16()
            }
            DnsQuestion(name, DnsRecordType.fromCode(type) ?: DnsRecordType.A)
        } else {
            null
        }

        return DnsResponse(
            id = id,
            responseCode = DnsResponseCode.fromCode(flags and 0x000F),
            authoritative = flags and 0x0400 != 0,
            truncated = flags and 0x0200 != 0,
            recursionAvailable = flags and 0x0080 != 0,
            authenticatedData = flags and 0x0020 != 0,
            question = question,
            answers = reader.records(answerCount),
            authority = reader.records(authorityCount),
            additional = reader.records(additionalCount),
        )
    }

    // ---- encoding helpers ---------------------------------------------------

    private fun MutableList<Byte>.addU16(value: Int) {
        add(((value shr 8) and 0xFF).toByte())
        add((value and 0xFF).toByte())
    }

    private fun MutableList<Byte>.addName(name: String) {
        val trimmed = name.trim().removeSuffix(".")
        if (trimmed.isEmpty()) {
            add(0)
            return
        }
        require(trimmed.length <= MAX_NAME_LENGTH) { "Name longer than $MAX_NAME_LENGTH bytes" }

        trimmed.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.isNotEmpty()) { "Empty label in name: $name" }
            require(bytes.size <= MAX_LABEL_LENGTH) { "Label longer than $MAX_LABEL_LENGTH bytes" }
            add(bytes.size.toByte())
            bytes.forEach { add(it) }
        }
        add(0)
    }

    // ---- decoding -----------------------------------------------------------

    private class MessageReader(private val data: ByteArray) {
        var position: Int = 0

        fun u8(): Int {
            require(position < data.size) { "DNS message truncated" }
            return data[position++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        fun bytes(count: Int): ByteArray {
            require(count >= 0 && position + count <= data.size) { "DNS message truncated" }
            val slice = data.copyOfRange(position, position + count)
            position += count
            return slice
        }

        /**
         * Reads a name, following compression pointers. Jumps are counted so a
         * message that points at itself cannot hang the parser.
         */
        fun name(): String = nameAt(null)

        private fun nameAt(startPosition: Int?): String {
            val labels = mutableListOf<String>()
            var jumps = 0
            var returnPosition = -1
            startPosition?.let { position = it }

            while (true) {
                val length = u8()
                if (length == 0) break

                if (length and 0xC0 == 0xC0) {
                    val pointer = ((length and 0x3F) shl 8) or u8()
                    require(++jumps <= MAX_POINTER_JUMPS) { "Compression pointer loop" }
                    if (returnPosition < 0) returnPosition = position
                    require(pointer < data.size) { "Compression pointer out of bounds" }
                    position = pointer
                } else {
                    labels += String(bytes(length), Charsets.US_ASCII)
                }
            }

            if (returnPosition >= 0) position = returnPosition
            return if (labels.isEmpty()) "." else labels.joinToString(".")
        }

        fun records(count: Int): List<DnsRecord> = (0 until count).map { readRecord() }

        private fun readRecord(): DnsRecord {
            val name = name()
            val typeCode = u16()
            u16() // class, or payload size on an OPT record
            val ttl = u32()
            val rdLength = u16()
            val rdStart = position
            val raw = data.copyOfRange(rdStart, (rdStart + rdLength).coerceAtMost(data.size))

            val type = DnsRecordType.fromCode(typeCode)
            val decoded = decodeRdata(type, rdStart, raw)

            // Always continue behind the record, whatever the decoder did with
            // the offset while following pointers inside the rdata.
            position = rdStart + rdLength
            return DnsRecord(
                name = name,
                type = type,
                typeCode = typeCode,
                ttlSeconds = ttl,
                data = decoded,
                rawData = raw.toHex(),
            )
        }

        private fun decodeRdata(
            type: DnsRecordType?,
            rdStart: Int,
            raw: ByteArray,
        ): String = when (type) {
            DnsRecordType.A -> if (raw.size == 4) {
                raw.joinToString(".") { (it.toInt() and 0xFF).toString() }
            } else {
                raw.toHex()
            }

            DnsRecordType.AAAA -> if (raw.size == 16) {
                Ipv6Subnet.format(BigInteger(1, raw))
            } else {
                raw.toHex()
            }

            DnsRecordType.NS, DnsRecordType.CNAME, DnsRecordType.PTR -> nameAt(rdStart)

            DnsRecordType.MX -> {
                position = rdStart
                val preference = u16()
                "$preference ${nameAt(null)}"
            }

            DnsRecordType.TXT -> decodeTxt(raw)

            DnsRecordType.SRV -> {
                position = rdStart
                val priority = u16()
                val weight = u16()
                val port = u16()
                "$priority $weight $port ${nameAt(null)}"
            }

            DnsRecordType.SOA -> {
                position = rdStart
                val primary = nameAt(null)
                val mailbox = nameAt(null)
                val serial = u32()
                val refresh = u32()
                val retry = u32()
                val expire = u32()
                val minimum = u32()
                "$primary $mailbox $serial $refresh $retry $expire $minimum"
            }

            DnsRecordType.CAA -> decodeCaa(raw)

            // Unknown types, including the OPT pseudo-record, are shown as hex.
            // An OPT record has no rdata, so this yields an empty string.
            null -> raw.toHex()
        }

        private fun decodeTxt(raw: ByteArray): String {
            val parts = mutableListOf<String>()
            var offset = 0
            while (offset < raw.size) {
                val length = raw[offset].toInt() and 0xFF
                offset++
                if (offset + length > raw.size) break
                parts += String(raw, offset, length, Charsets.UTF_8)
                offset += length
            }
            return parts.joinToString(" ") { "\"$it\"" }
        }

        private fun decodeCaa(raw: ByteArray): String {
            if (raw.size < 2) return raw.toHex()
            val flags = raw[0].toInt() and 0xFF
            val tagLength = raw[1].toInt() and 0xFF
            if (2 + tagLength > raw.size) return raw.toHex()
            val tag = String(raw, 2, tagLength, Charsets.US_ASCII)
            val value = String(raw, 2 + tagLength, raw.size - 2 - tagLength, Charsets.US_ASCII)
            return "$flags $tag \"$value\""
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
