package de.nettoolbox.iperf3

/**
 * Safe entry point to the native iperf3 module - the only thing outside
 * this module should ever call.
 *
 * Every function catches [UnsatisfiedLinkError] and any exception the native
 * call raises, matching the same requirement `:native:icmp` follows: a
 * broken native layer must degrade to a reported failure, never take the
 * whole app down.
 */
object Iperf3Bridge {

    private val libraryLoaded: Boolean = runCatching {
        System.loadLibrary("nettoolbox_iperf3")
    }.isSuccess

    val isAvailable: Boolean get() = libraryLoaded

    /** @return an opaque test handle, or null if the native library failed to load or init. */
    fun createTest(): Long? {
        if (!libraryLoaded) return null
        return runCatching { nativeCreateTest() }.getOrNull()?.takeIf { it != 0L }
    }

    fun configureClient(
        testPtr: Long,
        host: String,
        port: Int,
        useUdp: Boolean,
        durationSeconds: Int,
        reverse: Boolean,
        parallelStreams: Int,
        rateLimitBitsPerSecond: Long,
    ): Boolean = runCatching {
        nativeConfigureClient(
            testPtr,
            host,
            port,
            useUdp,
            durationSeconds,
            reverse,
            parallelStreams,
            rateLimitBitsPerSecond,
        )
    }.getOrDefault(false)

    /**
     * Blocks for the whole test duration - see the note at the top of
     * iperf_jni.c. Call this off the main thread; it cannot be cancelled
     * once started.
     *
     * @return 0 on success, a positive libiperf error code on failure, or
     *   null if the native call itself threw (a crash in the native layer,
     *   not a normal test failure).
     */
    fun runClient(testPtr: Long): Int? = runCatching { nativeRunClient(testPtr) }.getOrNull()

    fun jsonOutput(testPtr: Long): String =
        runCatching { nativeGetJsonOutput(testPtr) }.getOrDefault("")

    fun errorString(code: Int): String =
        runCatching { nativeErrorString(code) }.getOrDefault("Unknown error ($code)")

    fun freeTest(testPtr: Long) {
        runCatching { nativeFreeTest(testPtr) }
    }

    fun configureServer(testPtr: Long, port: Int): Boolean =
        runCatching { nativeConfigureServer(testPtr, port) }.getOrDefault(false)

    /**
     * Blocks until one client has completed a test. Call off the main thread.
     *
     * @return the native return code, or null if the native call itself threw.
     */
    fun runServerOnce(testPtr: Long): Int? =
        runCatching { nativeRunServerOnce(testPtr) }.getOrNull()
}
