package de.nettoolbox.feature.iperf.domain

import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.iperf3.Iperf3Bridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Runs an iperf3 client test against a server, over the vendored libiperf
 * (`:native:iperf3`, source at native/iperf3/src/main/cpp/iperf3-src - a git
 * submodule of the real upstream esnet/iperf).
 *
 * Deliberately has no way to cancel a run in progress: `iperf_run_client()`
 * is one blocking call for the whole test duration, coroutine cancellation
 * is cooperative and cannot preempt it, and libiperf's public API exposes no
 * way to abort a running test from another thread. The UI keeps test
 * durations short and says so rather than offering a stop control that
 * would not work - see the comment at the top of iperf_jni.c for the full
 * reasoning.
 */
class Iperf3ClientRunner @Inject constructor(
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
    private val summaryParser: Iperf3SummaryParser,
) {

    suspend fun run(request: Iperf3ClientRequest): Iperf3RunResult = withContext(dispatcher) {
        if (!Iperf3Bridge.isAvailable) {
            return@withContext Iperf3RunResult.Failed(
                NetToolboxError(ErrorReason.NATIVE_UNAVAILABLE, "Native iperf3 library did not load"),
            )
        }

        val testPtr = Iperf3Bridge.createTest()
            ?: return@withContext Iperf3RunResult.Failed(
                NetToolboxError(ErrorReason.NATIVE_UNAVAILABLE, "Could not create an iperf3 test"),
            )

        try {
            val configured = Iperf3Bridge.configureClient(
                testPtr = testPtr,
                host = request.host,
                port = request.port,
                useUdp = request.useUdp,
                durationSeconds = request.durationSeconds,
                reverse = request.reverse,
                parallelStreams = request.parallelStreams,
                rateLimitBitsPerSecond = request.rateLimitBitsPerSecond,
            )
            if (!configured) {
                return@withContext Iperf3RunResult.Failed(
                    NetToolboxError(ErrorReason.INVALID_INPUT, "Could not apply the test parameters"),
                )
            }

            val errorCode = Iperf3Bridge.runClient(testPtr)
            if (errorCode == null) {
                return@withContext Iperf3RunResult.Failed(
                    NetToolboxError(ErrorReason.NATIVE_UNAVAILABLE, "The native client crashed"),
                )
            }
            if (errorCode != 0) {
                return@withContext Iperf3RunResult.Failed(
                    NetToolboxError(
                        reason = errorCodeToReason(errorCode),
                        detail = Iperf3Bridge.errorString(errorCode),
                    ),
                )
            }

            val rawJson = Iperf3Bridge.jsonOutput(testPtr)
            Iperf3RunResult.Success(summary = summaryParser.parse(rawJson), rawJson = rawJson)
        } finally {
            Iperf3Bridge.freeTest(testPtr)
        }
    }

    /**
     * Maps a handful of the most common libiperf error codes (see the enum
     * in iperf_api.h) onto the app's error domain. Everything else falls
     * back to UNKNOWN with the library's own message attached via
     * [Iperf3Bridge.errorString] - still informative, just not specially
     * categorised.
     */
    private fun errorCodeToReason(code: Int): ErrorReason = when (code) {
        IECONNECT, ILISTEN -> ErrorReason.CONNECTION_REFUSED
        IENOMSG -> ErrorReason.TIMEOUT
        else -> ErrorReason.UNKNOWN
    }

    private companion object {
        // From the iperf_error enum in iperf_api.h.
        const val IECONNECT = 103
        const val ILISTEN = 102
        const val IENOMSG = 144
    }
}
