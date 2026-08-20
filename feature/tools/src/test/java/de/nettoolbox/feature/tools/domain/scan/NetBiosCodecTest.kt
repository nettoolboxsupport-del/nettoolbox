package de.nettoolbox.feature.tools.domain.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NetBiosCodecTest {

    @Test
    fun `encodes each byte as two nibbles offset by A`() {
        val name = ByteArray(16).also { it[0] = '*'.code.toByte() }

        // 0x2A -> 'A'+2, 'A'+10 -> "CK"; every null byte becomes "AA".
        assertEquals("CK" + "AA".repeat(15), String(NetBiosCodec.encodeName(name)))
    }

    @Test
    fun `builds a node status query of the expected length`() {
        val query = NetBiosCodec.buildNodeStatusQuery(0x1234)

        // 12 header + 1 length + 32 encoded + 1 terminator + 2 type + 2 class
        assertEquals(50, query.size)
        assertEquals(0x12, query[0].toInt() and 0xFF)
        assertEquals(0x34, query[1].toInt() and 0xFF)
        assertEquals(0x20, query[12].toInt() and 0xFF)
        assertEquals(0x00, query[45].toInt() and 0xFF)
        assertEquals(0x21, query[47].toInt() and 0xFF)
    }

    @Test
    fun `reads the workstation name and skips the workgroup`() {
        val response = nodeStatusResponse(
            "WORKGROUP" to GROUP_FLAGS,
            "PC-01" to UNIQUE_FLAGS,
        )

        // The group entry is the workgroup and identical on every host, so it
        // must not be mistaken for the machine name.
        assertEquals("PC-01", NetBiosCodec.parseNodeStatusResponse(response))
    }

    @Test
    fun `returns null when the response holds no unique name`() {
        val response = nodeStatusResponse("WORKGROUP" to GROUP_FLAGS)

        assertNull(NetBiosCodec.parseNodeStatusResponse(response))
    }

    @Test
    fun `returns null for a truncated response instead of throwing`() {
        assertNull(NetBiosCodec.parseNodeStatusResponse(ByteArray(20)))
        assertNull(NetBiosCodec.parseNodeStatusResponse(ByteArray(60)))
    }

    private fun nodeStatusResponse(vararg names: Pair<String, Int>): ByteArray {
        val header = ByteArray(56)
        val body = ArrayList<Byte>()
        body.add(names.size.toByte())

        names.forEach { (name, flags) ->
            val padded = name.padEnd(15).take(15)
            padded.forEach { body.add(it.code.toByte()) }
            body.add(0x00) // suffix: workstation service
            body.add(((flags shr 8) and 0xFF).toByte())
            body.add((flags and 0xFF).toByte())
        }

        return header + body.toByteArray()
    }

    private companion object {
        const val GROUP_FLAGS = 0x8000
        const val UNIQUE_FLAGS = 0x0400
    }
}
