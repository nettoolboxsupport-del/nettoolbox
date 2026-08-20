@file:JvmName("IcmpNative")

package de.nettoolbox.icmp

/**
 * Raw JNI bridge to icmp_ping.c.
 *
 * `@file:JvmName("IcmpNative")` pins the generated class name explicitly,
 * which pins the JNI symbol names in turn: every native method here resolves
 * to `Java_de_nettoolbox_icmp_IcmpNative_<name>` with no guessing about
 * Kotlin's default file-class suffix required on the C side.
 *
 * None of these functions is safe to call directly from outside this module -
 * a missing .so or an unavailable ping-socket facility surfaces as an
 * [UnsatisfiedLinkError] or a negative/error result, not as a checked
 * failure. [IcmpNativeBridge] is the guarded entry point; use that instead.
 */

/** @return a native file descriptor >= 0, or a negative errno value on failure. */
external fun nativeOpen(): Int

external fun nativeSetTtl(fd: Int, ttl: Int): Boolean

/** @param destinationIp a numeric IPv4 address - hostnames are resolved in Kotlin. */
external fun nativeSendEcho(fd: Int, destinationIp: String, sequence: Int, payloadSize: Int): Boolean

/** @return `[status, fromAddress]`, status being one of the ICMP_STATUS_* constants. */
external fun nativeReceiveEcho(fd: Int, sequence: Int, timeoutMillis: Int): Array<String>

external fun nativeClose(fd: Int)

/** Must be called once per socket before the first traceroute probe. */
external fun nativeEnableReceiveErrors(fd: Int): Boolean

/**
 * Traceroute-flavoured receive: like [nativeReceiveEcho], but also reports a
 * router's TIME_EXCEEDED or DEST_UNREACHABLE via the socket error queue
 * (`IP_RECVERR`), which a plain ping never needs. See the block comment above
 * `read_error_queue` in icmp_ping.c for why this half of the module is the
 * least-verified part of it.
 *
 * @return `[status, fromAddress]`, status being one of the ICMP_STATUS_* constants.
 */
external fun nativeReceiveHop(fd: Int, sequence: Int, timeoutMillis: Int): Array<String>

const val ICMP_STATUS_REPLY = "REPLY"
const val ICMP_STATUS_TIMEOUT = "TIMEOUT"
const val ICMP_STATUS_ERROR = "ERROR"
const val ICMP_STATUS_TIME_EXCEEDED = "TIME_EXCEEDED"
const val ICMP_STATUS_UNREACHABLE = "UNREACHABLE"
