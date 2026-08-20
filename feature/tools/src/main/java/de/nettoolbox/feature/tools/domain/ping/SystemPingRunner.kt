package de.nettoolbox.feature.tools.domain.ping

import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.common.result.toNetToolboxError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ping via the system binary.
 *
 * This is the fallback from section 3.3 of the spec and, until the native ICMP
 * module of phase 5 exists, the only real ICMP source: unprivileged raw sockets
 * are not available to an app.
 */
class SystemPingRunner @Inject constructor(
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    fun run(request: PingRequest): Flow<PingEvent> = flow {
        val command = buildCommand(request)

        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            emit(PingEvent.Failed(throwable.toPingError()))
            return@flow
        }

        // Destroying from a completion handler rather than only in `finally`:
        // readLine() blocks until the next packet arrives, so without this a
        // cancelled endless ping would keep a process alive for up to one
        // interval.
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { process.destroy() }

        val rtts = mutableListOf<Double>()
        var expectedSequence = 0
        var highestSequence = -1

        try {
            val reader = process.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break

                when (val parsed = PingOutputParser.parseLine(line)) {
                    is PingLine.Reply -> {
                        // `ping` prints nothing for a packet that times out, so a
                        // gap in the sequence numbers is the only in-flight signal
                        // that something was lost.
                        while (expectedSequence < parsed.sequence) {
                            emit(PingEvent.Loss(expectedSequence, LossReason.TIMEOUT))
                            expectedSequence++
                        }
                        emit(
                            PingEvent.Reply(
                                sequence = parsed.sequence,
                                from = parsed.from,
                                ttl = parsed.ttl,
                                rttMillis = parsed.rttMillis,
                            ),
                        )
                        rtts += parsed.rttMillis
                        expectedSequence = parsed.sequence + 1
                        highestSequence = maxOf(highestSequence, parsed.sequence)
                    }

                    is PingLine.Unreachable -> {
                        val sequence = parsed.sequence ?: expectedSequence
                        emit(PingEvent.Loss(sequence, LossReason.HOST_UNREACHABLE, parsed.from))
                        expectedSequence = sequence + 1
                        highestSequence = maxOf(highestSequence, sequence)
                    }

                    is PingLine.TtlExceeded -> {
                        val sequence = parsed.sequence ?: expectedSequence
                        emit(PingEvent.Loss(sequence, LossReason.TTL_EXCEEDED, parsed.from))
                        expectedSequence = sequence + 1
                        highestSequence = maxOf(highestSequence, sequence)
                    }

                    PingLine.Ignored -> Unit
                }
            }

            val exitCode = process.waitFor()
            // Exit code 1 means "no reply", which is a result, not a failure.
            // Anything above that is a usage or resolution error.
            if (rtts.isEmpty() && exitCode > 1) {
                emit(PingEvent.Failed(NetToolboxError(ErrorReason.HOST_UNREACHABLE, request.target)))
                return@flow
            }

            val sent = request.count ?: (highestSequence + 1).coerceAtLeast(rtts.size)
            emit(PingEvent.Completed(PingStatistics.of(sent, rtts)))
        } finally {
            handle?.dispose()
            process.destroy()
        }
    }.flowOn(dispatcher)

    private fun buildCommand(request: PingRequest): List<String> = buildList {
        add(if (isIpv6Literal(request.target)) BINARY_V6 else BINARY_V4)

        request.count?.let {
            add("-c")
            add(it.toString())
        }

        // Intervals below 200 ms need root on iputils; asking for less would make
        // the whole invocation fail instead of just running slower.
        val intervalSeconds = (request.intervalMillis.coerceAtLeast(200L)) / 1000.0
        add("-i")
        add(formatSeconds(intervalSeconds))

        add("-s")
        add(request.payloadSizeBytes.toString())

        add("-W")
        add(request.timeoutSeconds.toString())

        request.ttl?.let {
            add("-t")
            add(it.toString())
        }

        if (request.dontFragment) {
            add("-M")
            add("do")
        }

        add(request.target)
    }

    /** Locale-independent: `ping` parses the C locale, not the user's. */
    private fun formatSeconds(value: Double): String =
        String.format(java.util.Locale.ROOT, "%.1f", value)

    private fun isIpv6Literal(target: String): Boolean = target.contains(':')

    private fun Throwable.toPingError(): NetToolboxError {
        val mapped = toNetToolboxError()
        return if (this is java.io.IOException) {
            // The binary is missing or blocked - the detector should have caught
            // this, so report it as such instead of as a generic IO error.
            mapped.copy(reason = ErrorReason.NATIVE_UNAVAILABLE)
        } else {
            mapped
        }
    }

    private companion object {
        const val BINARY_V4 = "/system/bin/ping"
        const val BINARY_V6 = "/system/bin/ping6"
    }
}
