package de.nettoolbox.feature.serial.domain

/**
 * A USB device the serial drivers recognised.
 *
 * @param deviceName the kernel path, e.g. /dev/bus/usb/001/004. Stable for as
 *   long as the device stays plugged in, and the only identity two identical
 *   adapters have that tells them apart.
 * @param chip the driver family, which is what decides the capabilities - not
 *   the brand printed on the cable.
 * @param product the name the device reports. Null until permission has been
 *   granted: Android 10 and later refuse to reveal it before that.
 */
data class SerialDevice(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val chip: SerialChip,
    val manufacturer: String?,
    val product: String?,
    val portCount: Int,
    val hasPermission: Boolean,
) {
    /** VID:PID in the form lsusb prints, for matching against a vendor's documentation. */
    val usbId: String get() = "%04x:%04x".format(vendorId, productId)

    /** True for the USB console port built into Cisco equipment. */
    val isCiscoConsole: Boolean get() = vendorId == CISCO_VENDOR_ID && productId == CISCO_CONSOLE_PRODUCT_ID

    companion object {
        /**
         * Cisco Systems, and its "Console" product, as listed in the linux-usb.org
         * USB ID repository (05a6:0009). Named separately because it is the one
         * cable-free console a network engineer meets, and seeing "Cisco USB
         * console" rather than "CDC/ACM" confirms the right port was plugged in.
         */
        const val CISCO_VENDOR_ID = 0x05A6
        const val CISCO_CONSOLE_PRODUCT_ID = 0x0009
    }
}

enum class SerialChip { FTDI, CP210X, PL2303, CH34X, CDC_ACM, OTHER }

sealed interface SerialSessionState {
    data object Idle : SerialSessionState

    data class Connecting(val device: SerialDevice) : SerialSessionState

    /**
     * @param logFile path of the session capture relative to the share, or null
     *   when logging is off. Shown in the status line, because a user who
     *   switched logging on needs to know where the file went, and one who
     *   forgot it was on needs to be reminded.
     */
    data class Connected(
        val device: SerialDevice,
        val settings: SerialSettings,
        val logFile: String?,
    ) : SerialSessionState

    data class Failed(val reason: SerialFailure, val detail: String? = null) : SerialSessionState

    /** The session ended, with the reason. Kept apart from [Failed] so the screen can offer to reconnect. */
    data class Ended(val reason: SerialFailure?, val device: SerialDevice) : SerialSessionState
}

enum class SerialFailure {
    /** The user declined the system's USB permission dialog. */
    PERMISSION_DENIED,

    /** The device is no longer attached - usually the cable was pulled. */
    DEVICE_DETACHED,

    /** openDevice() returned null: another app holds the device, or it is not a serial adapter after all. */
    OPEN_FAILED,

    /** The adapter rejected the requested line settings, typically an unsupported baud rate. */
    UNSUPPORTED_PARAMETERS,

    /** An I/O error on an open port. */
    IO_ERROR,

    /** The native terminal library could not be loaded; there is no screen to show output on. */
    TERMINAL_UNAVAILABLE,
}

/** A one-off message for the console, shown once and then cleared. */
enum class SerialNotice {
    BREAK_SENT,
    BREAK_UNSUPPORTED,
    FLOW_CONTROL_UNSUPPORTED,
    PASTE_EMPTY,
    PASTE_DONE,
    LOG_FAILED,
}
