package de.nettoolbox.feature.fileserver.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class Protocol { TFTP, FTP, SFTP, SCP }

enum class TransferDirection { UPLOAD, DOWNLOAD, NONE }

enum class LogLevel { INFO, WARNING, ERROR }

/**
 * One line of the live log.
 *
 * The log is the feature's answer to the question every file-server app leaves
 * unanswered: it worked or it did not, and if not, why. A client that fails
 * authentication, hits a path outside the root, or aborts halfway through a
 * firmware image each produce a distinct line here - which is the difference
 * between "it doesn't work" and a diagnosis.
 */
data class TransferEvent(
    val atMillis: Long,
    val protocol: Protocol,
    val level: LogLevel,
    val clientAddress: String,
    val message: String,
    val path: String? = null,
    val direction: TransferDirection = TransferDirection.NONE,
    val bytes: Long? = null,
    val durationMillis: Long? = null,
) {
    /** Bytes per second, or null when the transfer was too short to mean anything. */
    val throughputBytesPerSecond: Double?
        get() {
            val duration = durationMillis ?: return null
            val transferred = bytes ?: return null
            if (duration < MINIMUM_MEASURABLE_MILLIS) return null
            return transferred * 1000.0 / duration
        }

    private companion object {
        /**
         * Below this a rate is arithmetic, not measurement: a 40-byte file
         * finishing in 1 ms would be reported as 40 MB/s, which is nonsense
         * that a technician might act on.
         */
        const val MINIMUM_MEASURABLE_MILLIS = 50L
    }
}

/**
 * The rolling in-memory log shared by all three servers.
 *
 * In memory and bounded on purpose. Writing every transfer to disk would mean
 * the app keeps a persistent record of who fetched what from the device, which
 * is a privacy claim this project does not want to have to make - and the log's
 * whole use is live, while the servers are running.
 */
@Singleton
class TransferLog @Inject constructor() {

    private val _events = MutableStateFlow<List<TransferEvent>>(emptyList())
    val events: StateFlow<List<TransferEvent>> = _events.asStateFlow()

    private val _totals = MutableStateFlow(TransferTotals())
    val totals: StateFlow<TransferTotals> = _totals.asStateFlow()

    fun record(event: TransferEvent) {
        _events.update { current -> (current + event).takeLast(MAX_EVENTS) }
        val transferred = event.bytes ?: return
        _totals.update { totals ->
            when (event.direction) {
                TransferDirection.UPLOAD -> totals.copy(
                    bytesReceived = totals.bytesReceived + transferred,
                    filesReceived = totals.filesReceived + 1,
                )

                TransferDirection.DOWNLOAD -> totals.copy(
                    bytesSent = totals.bytesSent + transferred,
                    filesSent = totals.filesSent + 1,
                )

                TransferDirection.NONE -> totals
            }
        }
    }

    fun info(protocol: Protocol, client: String, message: String, path: String? = null) =
        record(
            TransferEvent(
                atMillis = System.currentTimeMillis(),
                protocol = protocol,
                level = LogLevel.INFO,
                clientAddress = client,
                message = message,
                path = path,
            ),
        )

    fun warn(protocol: Protocol, client: String, message: String, path: String? = null) =
        record(
            TransferEvent(
                atMillis = System.currentTimeMillis(),
                protocol = protocol,
                level = LogLevel.WARNING,
                clientAddress = client,
                message = message,
                path = path,
            ),
        )

    fun error(protocol: Protocol, client: String, message: String, path: String? = null) =
        record(
            TransferEvent(
                atMillis = System.currentTimeMillis(),
                protocol = protocol,
                level = LogLevel.ERROR,
                clientAddress = client,
                message = message,
                path = path,
            ),
        )

    fun transferred(
        protocol: Protocol,
        client: String,
        path: String,
        direction: TransferDirection,
        bytes: Long,
        durationMillis: Long,
    ) = record(
        TransferEvent(
            atMillis = System.currentTimeMillis(),
            protocol = protocol,
            level = LogLevel.INFO,
            clientAddress = client,
            message = MESSAGE_TRANSFER_COMPLETE,
            path = path,
            direction = direction,
            bytes = bytes,
            durationMillis = durationMillis,
        ),
    )

    fun clear() {
        _events.value = emptyList()
        _totals.value = TransferTotals()
    }

    companion object {
        /**
         * Kept small enough that the whole log renders without paging. A busy
         * server producing thousands of lines would push the interesting first
         * failure out of reach anyway.
         */
        const val MAX_EVENTS = 300

        /**
         * A marker rather than prose: the UI renders completed transfers with
         * their own layout - path, direction, size and rate - and translating
         * a sentence that never gets shown would be busywork.
         */
        const val MESSAGE_TRANSFER_COMPLETE = "transfer.complete"
    }
}

data class TransferTotals(
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val filesSent: Int = 0,
    val filesReceived: Int = 0,
)
