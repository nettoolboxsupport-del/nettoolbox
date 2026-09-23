package de.nettoolbox.feature.serial.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SerialTextTest {

    private fun lines(text: String) = SerialText.pasteLines(text).map { it.text to it.pressEnter }

    @Test
    @DisplayName("a config ending in a line break runs every line")
    fun trailingBreakRunsEverything() {
        assertEquals(
            listOf("conf t" to true, "hostname sw1" to true, "end" to true),
            lines("conf t\nhostname sw1\nend\n"),
        )
    }

    @Test
    @DisplayName("without a trailing break the last line waits on the prompt")
    fun lastLineWithoutBreakIsNotExecuted() {
        // The case that matters on a live device: one copied command should
        // land on the prompt to be checked, not run the instant it is pasted.
        assertEquals(listOf("reload" to false), lines("reload"))
        assertEquals(
            listOf("interface Gi1/0/1" to true, "shutdown" to false),
            lines("interface Gi1/0/1\nshutdown"),
        )
    }

    @Test
    @DisplayName("Windows and classic Mac line endings split the same way")
    fun lineEndingsAreNormalised() {
        val expected = listOf("a" to true, "b" to true)
        assertEquals(expected, lines("a\r\nb\r\n"))
        assertEquals(expected, lines("a\rb\r"))
        assertEquals(expected, lines("a\nb\n"))
    }

    @Test
    @DisplayName("blank lines inside a paste are kept")
    fun blankLinesSurvive() {
        // A bare Enter is meaningful at a console: it ends a banner or
        // confirms a prompt. Dropping it would change what the device sees.
        assertEquals(listOf("a" to true, "" to true, "b" to true), lines("a\n\nb\n"))
    }

    @Test
    @DisplayName("an empty clipboard sends nothing")
    fun emptyTextSendsNothing() {
        assertTrue(SerialText.pasteLines("").isEmpty())
    }

    @Test
    @DisplayName("Enter as CR leaves the bytes untouched")
    fun crIsPassedThrough() {
        val bytes = byteArrayOf(0x61, 0x0D)
        assertSame(bytes, SerialText.rewriteEnter(bytes, LineEnding.CR))
    }

    @Test
    @DisplayName("Enter as CR+LF or LF replaces every CR")
    fun crIsRewritten() {
        val bytes = byteArrayOf(0x61, 0x0D, 0x62, 0x0D)
        assertArrayEquals(
            byteArrayOf(0x61, 0x0D, 0x0A, 0x62, 0x0D, 0x0A),
            SerialText.rewriteEnter(bytes, LineEnding.CRLF),
        )
        assertArrayEquals(
            byteArrayOf(0x61, 0x0A, 0x62, 0x0A),
            SerialText.rewriteEnter(bytes, LineEnding.LF),
        )
    }

    @Test
    @DisplayName("the settings summary reads the way consoles are documented")
    fun summaryFormat() {
        assertEquals("9600 8N1", SerialSettings().summary)
        assertEquals(
            "115200 7E2",
            SerialSettings(
                baudRate = 115200,
                dataBits = 7,
                parity = SerialParity.EVEN,
                stopBits = SerialStopBits.TWO,
            ).summary,
        )
    }
}
