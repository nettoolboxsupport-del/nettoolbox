package de.nettoolbox.feature.serial.domain

/**
 * The byte-level text handling of the console, kept free of Android and USB so
 * it can be tested on the JVM.
 */
object SerialText {

    private const val CR: Byte = 0x0D

    /** Replaces every CR with [ending]. Returns the input itself when there is nothing to replace. */
    fun rewriteEnter(bytes: ByteArray, ending: LineEnding): ByteArray {
        if (ending == LineEnding.CR || bytes.none { it == CR }) return bytes
        val out = ArrayList<Byte>(bytes.size + 4)
        for (b in bytes) {
            if (b == CR) ending.bytes.forEach(out::add) else out.add(b)
        }
        return out.toByteArray()
    }

    /** One line of a paste, and whether Enter follows it. */
    data class PasteLine(val text: String, val pressEnter: Boolean)

    /**
     * Splits clipboard text into the lines to send.
     *
     * Windows, classic Mac and Unix line endings are all accepted, since a
     * config copied out of a mail or a ticket can carry any of them.
     *
     * The last line is sent with Enter only if the text ended with a line
     * break. That is how a terminal pastes, and it matters here: a single
     * copied command without a trailing newline lands on the prompt and waits
     * to be checked, instead of executing on a live device the moment the
     * paste is confirmed.
     */
    fun pasteLines(text: String): List<PasteLine> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isEmpty()) return emptyList()
        val parts = normalized.split('\n')
        val endsWithBreak = normalized.endsWith('\n')
        // split() leaves an empty element after a trailing break; it is the
        // "nothing after the last Enter" and not a line of its own.
        val lines = if (endsWithBreak) parts.dropLast(1) else parts
        return lines.mapIndexed { index, line ->
            PasteLine(text = line, pressEnter = index < lines.lastIndex || endsWithBreak)
        }
    }
}
