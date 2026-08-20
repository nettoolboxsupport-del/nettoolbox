package de.nettoolbox.feature.tools.domain.scan

/**
 * NetBIOS node status query (UDP 137).
 *
 * The one reliable way left to get a Windows machine's own name without root:
 * ARP is blocked by SELinux from Android 10 on, and reverse DNS only works when
 * the local resolver knows the host.
 *
 * The encoding is the awkward part and is therefore pure and unit-tested: each
 * name byte is split into two nibbles, each offset by 'A'.
 */
object NetBiosCodec {

    const val PORT = 137
    private const val NAME_LENGTH = 16

    /**
     * NBSTAT query for the wildcard name "*", which asks a node to list its own
     * names rather than resolve one.
     */
    fun buildNodeStatusQuery(transactionId: Int): ByteArray {
        val out = ArrayList<Byte>(50)

        out.addU16(transactionId and 0xFFFF)
        out.addU16(0x0000) // flags: standard query, no recursion
        out.addU16(0x0001) // QDCOUNT
        out.addU16(0x0000) // ANCOUNT
        out.addU16(0x0000) // NSCOUNT
        out.addU16(0x0000) // ARCOUNT

        val name = ByteArray(NAME_LENGTH).also { it[0] = '*'.code.toByte() }
        val encoded = encodeName(name)
        out.add(encoded.size.toByte())
        encoded.forEach { out.add(it) }
        out.add(0) // end of name

        out.addU16(0x0021) // NBSTAT
        out.addU16(0x0001) // IN

        return out.toByteArray()
    }

    /** Splits every byte into two nibbles and offsets each by 'A'. */
    fun encodeName(name: ByteArray): ByteArray {
        require(name.size == NAME_LENGTH) { "A NetBIOS name is $NAME_LENGTH bytes" }
        val encoded = ByteArray(NAME_LENGTH * 2)
        name.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            encoded[index * 2] = ('A'.code + (value shr 4)).toByte()
            encoded[index * 2 + 1] = ('A'.code + (value and 0x0F)).toByte()
        }
        return encoded
    }

    /**
     * Reads the machine name out of a node status response.
     *
     * The answer lists several names; the useful one is the first unique
     * (non-group) entry with suffix 0x00, which is the workstation name. Group
     * entries are the workgroup or domain and would be the same for every host.
     */
    fun parseNodeStatusResponse(response: ByteArray): String? {
        // Header (12) + encoded name (34) + type (2) + class (2) + ttl (4) +
        // rdlength (2) = 56, then the name count.
        val countOffset = 56
        if (response.size <= countOffset) return null

        val nameCount = response[countOffset].toInt() and 0xFF
        var offset = countOffset + 1

        repeat(nameCount) {
            if (offset + 18 > response.size) return null
            val rawName = String(response, offset, 15, Charsets.US_ASCII).trim()
            val suffix = response[offset + 15].toInt() and 0xFF
            val flags = ((response[offset + 16].toInt() and 0xFF) shl 8) or
                (response[offset + 17].toInt() and 0xFF)
            val isGroup = flags and 0x8000 != 0

            if (suffix == 0x00 && !isGroup && rawName.isNotEmpty()) {
                return rawName
            }
            offset += 18
        }
        return null
    }

    private fun MutableList<Byte>.addU16(value: Int) {
        add(((value shr 8) and 0xFF).toByte())
        add((value and 0xFF).toByte())
    }
}
