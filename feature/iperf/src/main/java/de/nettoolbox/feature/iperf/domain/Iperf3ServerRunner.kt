package de.nettoolbox.feature.iperf.domain

import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.iperf3.Iperf3Bridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

sealed interface Iperf3ServerEvent {
    data class Listening(val port: Int) : Iperf3ServerEvent
    /** One client finished a test against us. */
    data class TestCompleted(val summary: Iperf3Summary, val rawJson: String) : Iperf3ServerEvent
    data class Failed(val error: NetToolboxError) : Iperf3ServerEvent
    data object Stopped : Iperf3ServerEvent
}

/**
 * iperf3 server, serving one test at a time in a loop.
 *
 * The loop lives here rather than inside libiperf: `iperf_run_server()`
 * returns after each completed test, so Kotlin can check a stop flag between
 * runs - which is the only stop point that exists. libiperf has no public
 * call to abort a server that is currently waiting for a client.
 *
 * That waiting state is the awkward part. `iperf_run_server()` sits in a
 * `select()` on its listening socket and will not return until something
 * connects. [stop] therefore does two things: it sets the flag, and it opens
 * and immediately closes a TCP connection to the server's own port. That
 * connection wakes the `select()`, the run ends (with an error, since no
 * valid iperf3 cookie follows), and the loop then sees the flag and exits.
 *
 * The self-connect is a workaround, not a documented mechanism. It uses only
 * ordinary socket behaviour and touches none of libiperf's internals.
 *
 * It must run off the main thread - see [stop]. The service calls stop() from
 * onStartCommand, and a socket connect there throws
 * NetworkOnMainThreadException, which the surrounding runCatching swallowed
 * without trace. The wakeup then never happened and the server waited
 * forever.
 */
@Singleton
class Iperf3ServerRunner @Inject constructor(
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
    private val summaryParser: Iperf3SummaryParser,
) {

    private val stopRequested = AtomicBoolean(false)

    /** Own scope so [stop] can do socket work without a caller's thread. */
    private val wakeScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var runningPort: Int? = null

    val isRunning: Boolean get() = runningPort != null

    fun run(port: Int): Flow<Iperf3ServerEvent> = callbackFlow {
        if (!Iperf3Bridge.isAvailable) {
            send(
                Iperf3ServerEvent.Failed(
                    NetToolboxError(ErrorReason.NATIVE_UNAVAILABLE, "Native iperf3 library did not load"),
                ),
            )
            close()
            return@callbackFlow
        }
        if (port < MIN_UNPRIVILEGED_PORT || port > MAX_PORT) {
            send(
                Iperf3ServerEvent.Failed(
                    NetToolboxError(
                        ErrorReason.INVALID_INPUT,
                        "Ports below $MIN_UNPRIVILEGED_PORT cannot be bound by an app",
                    ),
                ),
            )
            close()
            return@callbackFlow
        }

        stopRequested.set(false)
        runningPort = port

        val testPtr = Iperf3Bridge.createTest()
        if (testPtr == null) {
            runningPort = null
            send(
                Iperf3ServerEvent.Failed(
                    NetToolboxError(ErrorReason.NATIVE_UNAVAILABLE, "Could not create an iperf3 test"),
                ),
            )
            close()
            return@callbackFlow
        }

        try {
            if (!Iperf3Bridge.configureServer(testPtr, port)) {
                send(
                    Iperf3ServerEvent.Failed(
                        NetToolboxError(ErrorReason.INVALID_INPUT, "Could not configure the server"),
                    ),
                )
                return@callbackFlow
            }

            send(Iperf3ServerEvent.Listening(port))

            while (!stopRequested.get()) {
                val rc = Iperf3Bridge.runServerOnce(testPtr)

                if (stopRequested.get()) break

                when {
                    rc == null -> {
                        send(
                            Iperf3ServerEvent.Failed(
                                NetToolboxError(ErrorReason.NATIVE_UNAVAILABLE, "The native server crashed"),
                            ),
                        )
                        break
                    }

                    rc == 0 -> {
                        val rawJson = Iperf3Bridge.jsonOutput(testPtr)
                        send(
                            Iperf3ServerEvent.TestCompleted(
                                summary = summaryParser.parse(rawJson),
                                rawJson = rawJson,
                            ),
                        )
                    }

                    else -> {
                        // A failed run is not fatal for the server as a whole -
                        // a client can disconnect mid-test for all sorts of
                        // reasons. Report it and keep listening.
                        send(
                            Iperf3ServerEvent.Failed(
                                NetToolboxError(
                                    reason = ErrorReason.UNKNOWN,
                                    detail = Iperf3Bridge.errorString(rc),
                                ),
                            ),
                        )
                    }
                }
            }
        } finally {
            runningPort = null
            Iperf3Bridge.freeTest(testPtr)
            trySend(Iperf3ServerEvent.Stopped)
            close()
        }

        awaitClose { }
    }.flowOn(dispatcher)

    /**
     * Requests a stop and nudges the server out of its `select()` wait by
     * connecting to it once. Safe to call when nothing is running.
     *
     * The connection deliberately happens on [dispatcher], never on the
     * caller's thread: this is called from the service's `onStartCommand`,
     * which runs on the main thread, and Android throws
     * NetworkOnMainThreadException for any socket work there. Wrapped in
     * runCatching, that exception is invisible - the wakeup simply never
     * happens and the server hangs waiting forever. That was a real bug, not
     * a hypothetical one.
     *
     * The socket is closed immediately after connecting. iperf3 accepts it,
     * tries to read a 37-byte cookie, gets EOF instead, and returns an error
     * from iperf_run_server() - which is exactly the return the loop needs.
     */
    fun stop() {
        stopRequested.set(true)
        val port = runningPort ?: return

        wakeScope.launch {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), WAKE_TIMEOUT_MILLIS)
                }
            }
        }
    }

    private companion object {
        const val MIN_UNPRIVILEGED_PORT = 1024
        const val MAX_PORT = 65535
        const val WAKE_TIMEOUT_MILLIS = 1_000
    }
}
