package de.nettoolbox.icmp

/**
 * Safe entry point to the native ICMP module - the only thing outside this
 * module should ever call.
 *
 * Every function catches [UnsatisfiedLinkError] (missing or unloadable .so,
 * e.g. an ABI the device does not have a build for) and any exception the
 * native call raises, turning it into a null/false/error result instead of
 * letting it reach the caller. "Kein JNI-Crash darf die App killen" is a hard
 * requirement - a broken native layer must degrade a ping to a failure the UI
 * can show, never take the process down.
 */
object IcmpNativeBridge {

    private val libraryLoaded: Boolean = runCatching {
        System.loadLibrary("nettoolbox_icmp")
    }.isSuccess

    /** @return an opaque socket handle, or null when ping sockets are unavailable. */
    fun open(): Int? {
        if (!libraryLoaded) return null
        return runCatching { nativeOpen() }.getOrNull()?.takeIf { it >= 0 }
    }

    fun setTtl(fd: Int, ttl: Int): Boolean =
        runCatching { nativeSetTtl(fd, ttl) }.getOrDefault(false)

    fun sendEcho(fd: Int, destinationIp: String, sequence: Int, payloadSize: Int): Boolean =
        runCatching { nativeSendEcho(fd, destinationIp, sequence, payloadSize) }.getOrDefault(false)

    /** @return (status, fromAddress); status is one of the ICMP_STATUS_* constants. */
    fun receiveEcho(fd: Int, sequence: Int, timeoutMillis: Int): Pair<String, String> =
        runCatching { nativeReceiveEcho(fd, sequence, timeoutMillis) }
            .getOrNull()
            ?.takeIf { it.size == 2 }
            ?.let { it[0] to it[1] }
            ?: (ICMP_STATUS_ERROR to "")

    fun close(fd: Int) {
        runCatching { nativeClose(fd) }
    }

    fun enableReceiveErrors(fd: Int): Boolean =
        runCatching { nativeEnableReceiveErrors(fd) }.getOrDefault(false)

    /** @return (status, fromAddress); status is one of the ICMP_STATUS_* constants. */
    fun receiveHop(fd: Int, sequence: Int, timeoutMillis: Int): Pair<String, String> =
        runCatching { nativeReceiveHop(fd, sequence, timeoutMillis) }
            .getOrNull()
            ?.takeIf { it.size == 2 }
            ?.let { it[0] to it[1] }
            ?: (ICMP_STATUS_ERROR to "")

    /**
     * One-shot capability probe: can this device open a ping socket at all.
     * Cheap - it never touches the network, only socket() and bind().
     */
    fun isAvailable(): Boolean {
        val fd = open() ?: return false
        close(fd)
        return true
    }
}
