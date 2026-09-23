package de.nettoolbox.feature.fileserver.tftp

/**
 * Block-number arithmetic for TFTP.
 *
 * Its own object because it is the subtlest code in this package and because
 * nothing in it needs a socket. TFTP block numbers are sixteen bits and wrap;
 * at the protocol's default block size that happens after 32 MB, which is
 * smaller than every firmware image this feature exists to move. Getting the
 * wraparound wrong produces a transfer that works in every test with a small
 * file and truncates the one that matters.
 */
internal object TftpBlocks {

    const val MODULUS = 65536L

    /** The sixteen-bit form of an absolute block number. */
    fun wire(absolute: Long): Int = (absolute % MODULUS).toInt()

    /**
     * Maps a sixteen-bit block number back onto the absolute counter.
     *
     * Resolved as the largest absolute value not greater than [near] that has
     * this low half. That is the correct reading because an acknowledgement can
     * only refer to a block already sent, never to one in the future - so when
     * the naive candidate lands beyond [near], the right answer is one
     * wraparound earlier.
     */
    fun absolute(wire: Int, near: Long): Long {
        if (wire < 0) return -1
        val base = near - (near % MODULUS)
        val candidate = base + wire
        return if (candidate > near) candidate - MODULUS else candidate
    }

    /**
     * How many DATA blocks a file of this length takes.
     *
     * Always one more than the whole blocks it contains, including when the
     * length divides exactly. A transfer ends on a block shorter than the
     * negotiated block size, so a file that is an exact multiple needs a final
     * empty block - without it the client waits for one that never comes. An
     * empty file is one empty block, which the same formula gives.
     */
    fun totalBlocks(length: Long, blockSize: Int): Long = length / blockSize + 1
}
