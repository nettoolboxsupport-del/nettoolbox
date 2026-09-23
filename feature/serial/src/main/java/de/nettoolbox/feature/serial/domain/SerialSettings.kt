package de.nettoolbox.feature.serial.domain

import kotlinx.serialization.Serializable

enum class SerialParity(val symbol: Char) { NONE('N'), ODD('O'), EVEN('E'), MARK('M'), SPACE('S') }

enum class SerialStopBits(val label: String) { ONE("1"), ONE_AND_HALF("1.5"), TWO("2") }

/**
 * Flow control.
 *
 * Offered only in the form the connected adapter supports: the chips differ,
 * and a setting the hardware silently ignores is worse than one that is not
 * shown. The console asks the port and filters this list.
 */
enum class SerialFlowControl { NONE, RTS_CTS, DTR_DSR, XON_XOFF }

/**
 * What the Enter key puts on the wire.
 *
 * CR is right for virtually all network gear, and it is what a terminal sends.
 * The other two exist for the embedded boards and bootloaders that insist on
 * something else - and "my commands do nothing" is the entire symptom when this
 * is wrong, so it has to be changeable without a new build.
 */
enum class LineEnding(val bytes: ByteArray) {
    CR(byteArrayOf(0x0D)),
    LF(byteArrayOf(0x0A)),
    CRLF(byteArrayOf(0x0D, 0x0A)),
}

/**
 * Everything about how the line is driven. Persisted, so the next console
 * session on the same kind of device starts right.
 *
 * @param dtr / rts asserted by default. A handful of devices only talk once DTR
 *   is up, and nothing in a console session is harmed by it; the toggle exists
 *   for the rare board that resets on DTR, such as an Arduino.
 * @param localEcho off by default: network gear echoes what it receives, and
 *   echoing locally as well prints every character twice.
 * @param pasteLineDelayMillis the pause between lines when pasting. A console
 *   at 9600 baud delivers characters faster than a router parses configuration
 *   commands, and without a pause the device drops input - silently, in the
 *   middle of a pasted config. 100 ms per line is the conventional figure.
 * @param logToFile off by default. A console capture routinely contains
 *   configuration with password hashes and SNMP communities, so writing it to
 *   shared storage is something the user switches on deliberately.
 */
@Serializable
data class SerialSettings(
    val baudRate: Int = 9600,
    val dataBits: Int = 8,
    val parity: SerialParity = SerialParity.NONE,
    val stopBits: SerialStopBits = SerialStopBits.ONE,
    val flowControl: SerialFlowControl = SerialFlowControl.NONE,
    val dtr: Boolean = true,
    val rts: Boolean = true,
    val enterSends: LineEnding = LineEnding.CR,
    val localEcho: Boolean = false,
    val pasteLineDelayMillis: Int = 100,
    val keepScreenOn: Boolean = true,
    val logToFile: Boolean = false,
) {
    /** The conventional short form, e.g. "9600 8N1". */
    val summary: String get() = "$baudRate $dataBits${parity.symbol}${stopBits.label}"

    companion object {
        val BAUD_RATES = listOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)
        val DATA_BITS = listOf(5, 6, 7, 8)
        const val MIN_BAUD = 50
        const val MAX_BAUD = 12_000_000
    }
}

/**
 * Starting points for the device families a network engineer meets most.
 *
 * Only line speed and framing, nothing else, and only families whose console
 * defaults are well established. A preset is a shortcut to the common case, not
 * a promise: some platforms let an administrator change the console speed.
 */
enum class SerialPreset(val baudRate: Int, val examples: String) {
    CONSOLE_9600(9600, "Cisco, Juniper, Fortinet, Palo Alto"),
    CONSOLE_115200(115200, "MikroTik, Ubiquiti, Linux"),
}
