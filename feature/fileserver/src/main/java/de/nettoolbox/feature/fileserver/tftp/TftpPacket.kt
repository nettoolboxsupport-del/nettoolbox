package de.nettoolbox.feature.fileserver.tftp

import java.nio.charset.StandardCharsets

/**
 * Wire format of TFTP, RFC 1350 with the option extension of RFC 2347.
 *
 * Written out rather than pulled from a library because the whole protocol is
 * five packet types and there is no dependency worth taking for it - and
 * because the interesting part, the options, is exactly what the small
 * libraries leave out.
 */
internal object TftpOpcode {
    const val RRQ = 1
    const val WRQ = 2
    const val DATA = 3
    const val ACK = 4
    const val ERROR = 5

    /** RFC 2347. Acknowledges the subset of requested options the server accepts. */
    const val OACK = 6
}

/**
 * Error codes of RFC 1350 section 5.
 *
 * The code matters more than the message: a client's own diagnostics key off
 * it, and several devices print nothing but the number.
 */
internal object TftpError {
    const val NOT_DEFINED = 0
    const val FILE_NOT_FOUND = 1
    const val ACCESS_VIOLATION = 2
    const val DISK_FULL = 3
    const val ILLEGAL_OPERATION = 4
    const val UNKNOWN_TRANSFER_ID = 5
    const val FILE_ALREADY_EXISTS = 6
    const val NO_SUCH_USER = 7

    /** RFC 2347: an option was requested that the server refuses outright. */
    const val OPTION_REFUSED = 8
}

internal enum class TftpMode { OCTET, NETASCII, UNSUPPORTED }

/** A parsed RRQ or WRQ. */
internal data class TftpRequest(
    val write: Boolean,
    val filename: String,
    val mode: TftpMode,
    val options: Map<String, String>,
)

internal object TftpCodec {

    /**
     * Reads a request packet.
     *
     * Returns null for anything that is not a well-formed RRQ or WRQ. Malformed
     * input on an unauthenticated UDP port is not an exceptional case, it is
     * the normal background noise of a network, so it is a return value rather
     * than an exception.
     */
    fun parseRequest(data: ByteArray, length: Int): TftpRequest? {
        if (length < MINIMUM_REQUEST_LENGTH) return null
        val opcode = readShort(data, 0)
        if (opcode != TftpOpcode.RRQ && opcode != TftpOpcode.WRQ) return null

        val fields = splitNulTerminated(data, 2, length)
        // Filename and mode are mandatory; options come in pairs after them, so
        // an odd count past the first two means a truncated or forged packet.
        if (fields.size < 2) return null
        if ((fields.size - 2) % 2 != 0) return null

        val options = buildMap {
            var index = 2
            while (index + 1 < fields.size) {
                // Option names are case-insensitive per RFC 2347. Clients do
                // send "Blksize", and matching case-sensitively would silently
                // drop the negotiation.
                put(fields[index].lowercase(), fields[index + 1])
                index += 2
            }
        }

        return TftpRequest(
            write = opcode == TftpOpcode.WRQ,
            filename = fields[0],
            mode = when (fields[1].lowercase()) {
                "octet" -> TftpMode.OCTET
                "netascii" -> TftpMode.NETASCII
                else -> TftpMode.UNSUPPORTED
            },
            options = options,
        )
    }

    fun data(block: Int, payload: ByteArray, payloadLength: Int): ByteArray {
        val packet = ByteArray(4 + payloadLength)
        writeShort(packet, 0, TftpOpcode.DATA)
        writeShort(packet, 2, block)
        payload.copyInto(packet, 4, 0, payloadLength)
        return packet
    }

    fun ack(block: Int): ByteArray = ByteArray(4).also {
        writeShort(it, 0, TftpOpcode.ACK)
        writeShort(it, 2, block)
    }

    fun error(code: Int, message: String): ByteArray {
        val text = message.toByteArray(StandardCharsets.US_ASCII)
        val packet = ByteArray(4 + text.size + 1)
        writeShort(packet, 0, TftpOpcode.ERROR)
        writeShort(packet, 2, code)
        text.copyInto(packet, 4)
        packet[packet.size - 1] = 0
        return packet
    }

    /** Option acknowledgement: the accepted options only, in any order. */
    fun oack(options: Map<String, String>): ByteArray {
        val body = options.entries.flatMap { (key, value) ->
            listOf(key.toByteArray(StandardCharsets.US_ASCII), value.toByteArray(StandardCharsets.US_ASCII))
        }
        val size = 2 + body.sumOf { it.size + 1 }
        val packet = ByteArray(size)
        writeShort(packet, 0, TftpOpcode.OACK)
        var offset = 2
        for (field in body) {
            field.copyInto(packet, offset)
            offset += field.size
            packet[offset] = 0
            offset++
        }
        return packet
    }

    fun opcodeOf(data: ByteArray, length: Int): Int =
        if (length < 2) -1 else readShort(data, 0)

    fun blockOf(data: ByteArray, length: Int): Int =
        if (length < 4) -1 else readShort(data, 2)

    /** Big-endian, unsigned - block numbers pass 32767 on any file worth sending. */
    fun readShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    fun writeShort(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun splitNulTerminated(data: ByteArray, from: Int, until: Int): List<String> {
        val fields = mutableListOf<String>()
        var start = from
        var index = from
        while (index < until) {
            if (data[index] == 0.toByte()) {
                fields += String(data, start, index - start, StandardCharsets.UTF_8)
                start = index + 1
            }
            index++
        }
        // A trailing field without its NUL terminator means the packet was cut
        // short. It is dropped rather than salvaged: a half-read filename is
        // the kind of thing that turns into a path check on a partial string.
        return fields
    }

    private const val MINIMUM_REQUEST_LENGTH = 4
}
