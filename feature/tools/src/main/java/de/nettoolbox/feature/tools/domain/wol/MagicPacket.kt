package de.nettoolbox.feature.tools.domain.wol

/**
 * Wake-on-LAN magic packet: six 0xFF bytes followed by the target MAC repeated
 * sixteen times.
 *
 * Pure and separate from the sending code so the packet layout - the part that
 * is silently wrong when a device does not wake - can be unit-tested.
 */
object MagicPacket {

    const val SIZE_BYTES = 102
    const val MAC_LENGTH = 6

    /** Port 9 (discard) is the common default; some NICs listen on 7 (echo). */
    const val DEFAULT_PORT = 9

    /**
     * Accepts `AA:BB:CC:DD:EE:FF`, `aa-bb-cc-dd-ee-ff` and `aabbccddeeff`.
     */
    fun parseMac(text: String): ByteArray? {
        val cleaned = text.trim().replace(":", "").replace("-", "").replace(".", "")
        if (cleaned.length != MAC_LENGTH * 2) return null
        if (!cleaned.all { it.isDigit() || it in "abcdefABCDEF" }) return null

        return ByteArray(MAC_LENGTH) { index ->
            cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun formatMac(mac: ByteArray): String =
        mac.joinToString(":") { byte -> "%02X".format(byte) }

    fun build(mac: ByteArray): ByteArray {
        require(mac.size == MAC_LENGTH) { "A MAC address is $MAC_LENGTH bytes, got ${mac.size}" }

        val packet = ByteArray(SIZE_BYTES)
        for (index in 0 until MAC_LENGTH) {
            packet[index] = 0xFF.toByte()
        }
        for (repetition in 0 until 16) {
            val offset = MAC_LENGTH + repetition * MAC_LENGTH
            mac.copyInto(packet, offset)
        }
        return packet
    }
}
