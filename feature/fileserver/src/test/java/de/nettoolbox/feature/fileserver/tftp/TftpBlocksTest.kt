package de.nettoolbox.feature.fileserver.tftp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TftpBlocksTest {

    @Test
    @DisplayName("wire form is the low sixteen bits")
    fun wireIsLowHalf() {
        assertEquals(1, TftpBlocks.wire(1))
        assertEquals(65535, TftpBlocks.wire(65535))
        // The wrap itself: block 65536 goes on the wire as zero, which is the
        // same value an OACK acknowledgement uses. They are told apart by
        // position in the transfer, not by the number.
        assertEquals(0, TftpBlocks.wire(65536))
        assertEquals(1, TftpBlocks.wire(65537))
        assertEquals(0, TftpBlocks.wire(131072))
    }

    @ParameterizedTest(name = "wire {0} near {1} resolves to {2}")
    @CsvSource(
        // Ordinary case, well inside the first cycle.
        "5, 10, 5",
        // Acknowledgement of exactly the last block sent.
        "10, 10, 10",
        // First wrap: the client acknowledges block 0, meaning 65536.
        "0, 65536, 65536",
        "1, 65537, 65537",
        // A stale acknowledgement from before the wrap, arriving after it.
        "65535, 65537, 65535",
        // Second wrap, to prove the base is recomputed rather than assumed.
        "3, 131075, 131075",
        "65535, 131073, 131071",
    )
    fun resolvesAcrossWraparound(wire: Int, near: Long, expected: Long) {
        assertEquals(expected, TftpBlocks.absolute(wire, near))
    }

    @Test
    @DisplayName("a resolved block is never ahead of what was sent")
    fun neverAheadOfSent() {
        // The invariant that makes the whole scheme safe: an acknowledgement
        // cannot refer to a block the server has not sent yet. If this ever
        // fails, the sender would skip forward and truncate the file.
        for (near in listOf(1L, 100L, 65535L, 65536L, 70000L, 131072L)) {
            for (wire in 0 until 65536 step 997) {
                val resolved = TftpBlocks.absolute(wire, near)
                assert(resolved <= near) { "wire=$wire near=$near resolved=$resolved" }
            }
        }
    }

    @ParameterizedTest(name = "{0} bytes at {1} per block is {2} blocks")
    @CsvSource(
        // An empty file is still one (empty) block, or the client never learns
        // the transfer ended.
        "0, 512, 1",
        "1, 512, 1",
        "511, 512, 1",
        // Exactly one full block needs a trailing empty one.
        "512, 512, 2",
        "513, 512, 2",
        "1024, 512, 3",
        // A realistic firmware image at the Ethernet-sized block.
        "104857600, 1468, 71430",
    )
    fun countsBlocksIncludingTheFinalShortOne(length: Long, blockSize: Int, expected: Long) {
        assertEquals(expected, TftpBlocks.totalBlocks(length, blockSize))
    }
}
