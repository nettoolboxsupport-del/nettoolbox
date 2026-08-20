@file:JvmName("Iperf3Native")

package de.nettoolbox.iperf3

/**
 * Raw JNI bridge to iperf_jni.c, over the vendored libiperf (client mode
 * only so far - server mode and the foreground service are a separate part).
 *
 * `@file:JvmName("Iperf3Native")` pins the generated class name, which pins
 * the JNI symbol names in turn - see IcmpNative.kt in `:native:icmp` for the
 * same reasoning.
 *
 * Nothing here is safe to call directly. [Iperf3Bridge] is the guarded entry
 * point; use that instead.
 */

/** @return an opaque native test handle, or 0 on failure. */
external fun nativeCreateTest(): Long

external fun nativeConfigureClient(
    testPtr: Long,
    host: String,
    port: Int,
    useUdp: Boolean,
    durationSeconds: Int,
    reverse: Boolean,
    parallelStreams: Int,
    rateLimitBitsPerSecond: Long,
): Boolean

/** @return 0 on success, or a positive libiperf error code on failure. */
external fun nativeRunClient(testPtr: Long): Int

external fun nativeGetJsonOutput(testPtr: Long): String

external fun nativeErrorString(errorCode: Int): String

external fun nativeFreeTest(testPtr: Long)

/** @param port must be >= 1024 - an unprivileged process cannot bind below that. */
external fun nativeConfigureServer(testPtr: Long, port: Int): Boolean

/**
 * Serves exactly one test, then returns. Blocks until a client has completed
 * a test against it - the loop belongs in Kotlin so a stop flag can be
 * checked in between.
 *
 * @return 0 on a completed test, negative on error, 2 on auth failure.
 */
external fun nativeRunServerOnce(testPtr: Long): Int
