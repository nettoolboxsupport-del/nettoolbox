package de.nettoolbox.feature.ssh

import de.nettoolbox.feature.ssh.data.UNKNOWN_KEY_TYPE
import de.nettoolbox.feature.ssh.data.sshKeyTypeOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * These bytes arrive from the network before anything has been verified, so
 * every malformed shape has to produce "unknown" rather than an exception or
 * a read past the end of the array.
 */
class SshKeyTypeParsingTest {

    private fun blob(type: String, trailing: ByteArray = ByteArray(0)): ByteArray {
        val name = type.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(4 + name.size + trailing.size)
        out[0] = (name.size ushr 24).toByte()
        out[1] = (name.size ushr 16).toByte()
        out[2] = (name.size ushr 8).toByte()
        out[3] = name.size.toByte()
        name.copyInto(out, 4)
        trailing.copyInto(out, 4 + name.size)
        return out
    }

    @Test
    fun `reads an ed25519 type name`() {
        assertEquals("ssh-ed25519", sshKeyTypeOf(blob("ssh-ed25519", ByteArray(32))))
    }

    @Test
    fun `reads an rsa type name`() {
        assertEquals("ssh-rsa", sshKeyTypeOf(blob("ssh-rsa", ByteArray(256))))
    }

    @Test
    fun `reads an ecdsa type name`() {
        assertEquals(
            "ecdsa-sha2-nistp256",
            sshKeyTypeOf(blob("ecdsa-sha2-nistp256", ByteArray(64))),
        )
    }

    @Test
    fun `an empty blob is unknown`() {
        assertEquals(UNKNOWN_KEY_TYPE, sshKeyTypeOf(ByteArray(0)))
    }

    @Test
    fun `a blob shorter than the length prefix is unknown`() {
        assertEquals(UNKNOWN_KEY_TYPE, sshKeyTypeOf(byteArrayOf(0, 0, 1)))
    }

    @Test
    fun `a length longer than the blob is unknown rather than an overrun`() {
        // Claims 255 bytes of name but carries four. Reading it would walk off
        // the end of the array.
        val hostile = byteArrayOf(0, 0, 0, 255.toByte(), 'a'.code.toByte())
        assertEquals(UNKNOWN_KEY_TYPE, sshKeyTypeOf(hostile))
    }

    @Test
    fun `a zero length is unknown`() {
        assertEquals(UNKNOWN_KEY_TYPE, sshKeyTypeOf(byteArrayOf(0, 0, 0, 0, 1, 2)))
    }

    @Test
    fun `a negative length from the high bit is unknown`() {
        // 0xFFFFFFFF read as a signed int is -1. Without the `length <= 0`
        // guard this would reach String(bytes, offset, -1) and throw.
        val hostile = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 1, 2, 3,
        )
        assertEquals(UNKNOWN_KEY_TYPE, sshKeyTypeOf(hostile))
    }

    @Test
    fun `an absurdly long name is rejected even when the blob is that long`() {
        val longName = "x".repeat(200)
        assertEquals(UNKNOWN_KEY_TYPE, sshKeyTypeOf(blob(longName)))
    }
}
