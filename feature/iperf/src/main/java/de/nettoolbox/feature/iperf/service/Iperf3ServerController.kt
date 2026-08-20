package de.nettoolbox.feature.iperf.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.nettoolbox.feature.iperf.domain.Iperf3Summary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class CompletedServerTest(
    val finishedAtMillis: Long,
    val summary: Iperf3Summary,
    /**
     * Kept alongside the parsed summary so a test whose fields could not be
     * read still shows something real. Without it a failed parse renders an
     * empty card, which looks exactly like "nothing happened".
     */
    val rawJson: String,
) {
    val hasNumbers: Boolean
        get() = summary.sentBitsPerSecond != null || summary.receivedBitsPerSecond != null

    /** True when libiperf handed back no JSON at all - a different fault from a failed parse. */
    val jsonWasEmpty: Boolean get() = rawJson.isBlank()
}

data class Iperf3ServerState(
    val isRunning: Boolean = false,
    /**
     * A stop was requested but libiperf has not returned yet.
     *
     * This state exists because it is genuinely observable: `iperf_run_server()`
     * cannot be interrupted, so a stop pressed while a client is mid-test only
     * takes effect once that test finishes - which the client's chosen duration
     * decides, not us. Showing "stopped" right away would be a claim about the
     * radio that is not true yet.
     */
    val isStopping: Boolean = false,
    val port: Int? = null,
    val completedTests: List<CompletedServerTest> = emptyList(),
    val lastErrorDetail: String? = null,
) {
    /** True while the server is up in any form - blocks starting a second one. */
    val isBusy: Boolean get() = isRunning || isStopping
}

/**
 * Shared state between the iperf3 server service and the UI.
 *
 * Same reasoning as the drive-test controller: a listening server has to
 * survive rotation and the screen going off, so it lives in a service and
 * the UI only observes it.
 */
@Singleton
class Iperf3ServerController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val _state = MutableStateFlow(Iperf3ServerState())
    val state: StateFlow<Iperf3ServerState> = _state.asStateFlow()

    fun start(port: Int) {
        val intent = Intent(context, Iperf3ServerService::class.java).apply {
            action = Iperf3ServerService.ACTION_START
            putExtra(Iperf3ServerService.EXTRA_PORT, port)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop() {
        val intent = Intent(context, Iperf3ServerService::class.java).apply {
            action = Iperf3ServerService.ACTION_STOP
        }
        context.startService(intent)
    }

    internal fun update(transform: (Iperf3ServerState) -> Iperf3ServerState) = _state.update(transform)

    /**
     * Clears the running state - but deliberately not [Iperf3ServerState.completedTests].
     *
     * Those are measurements the user made. Stopping the server says nothing
     * about them, and silently discarding results because a listening socket
     * closed is data loss, not cleanup. They go only when the user says so,
     * via [clearResults].
     */
    internal fun reset() = _state.update {
        Iperf3ServerState(completedTests = it.completedTests)
    }

    fun clearResults() = _state.update { it.copy(completedTests = emptyList()) }
}
