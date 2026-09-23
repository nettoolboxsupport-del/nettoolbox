package de.nettoolbox.feature.fileserver.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.nettoolbox.feature.fileserver.domain.Protocol
import de.nettoolbox.feature.fileserver.domain.ReachableAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What one protocol is doing.
 *
 * [failure] is kept per protocol rather than as one message for the whole
 * server, because the ordinary failure is one port being taken while the other
 * two start perfectly. Collapsing that into "the server failed to start" would
 * hide a working FTP server behind a busy SSH port.
 */
data class ProtocolStatus(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val port: Int = 0,
    val failure: ProtocolFailure? = null,
)

enum class ProtocolFailure { PORT_IN_USE, PRIVILEGED_PORT, NO_BIND_ADDRESS, TLS_UNAVAILABLE, OTHER }

data class FileServerState(
    val isStarting: Boolean = false,
    val tftp: ProtocolStatus = ProtocolStatus(),
    val ftp: ProtocolStatus = ProtocolStatus(),
    val ssh: ProtocolStatus = ProtocolStatus(),
    val addresses: List<ReachableAddress> = emptyList(),
    val startedAtMillis: Long? = null,
    /** When the auto-stop will fire, or null when it is switched off. */
    val autoStopAtMillis: Long? = null,
    /** Set when nothing could be started at all - a different case from one protocol failing. */
    val fatalFailure: ProtocolFailure? = null,
) {
    val anyRunning: Boolean get() = tftp.running || ftp.running || ssh.running

    val isBusy: Boolean get() = isStarting || anyRunning

    fun statusOf(protocol: Protocol): ProtocolStatus = when (protocol) {
        Protocol.TFTP -> tftp
        Protocol.FTP -> ftp
        Protocol.SFTP, Protocol.SCP -> ssh
    }
}

/**
 * Shared state between the server service and the UI.
 *
 * Same shape as the iperf3 server controller, and for the same reason: listening
 * sockets have to survive rotation and the screen going off, so they live in a
 * service and the UI only ever observes them.
 */
@Singleton
class FileServerController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val _state = MutableStateFlow(FileServerState())
    val state: StateFlow<FileServerState> = _state.asStateFlow()

    fun start() {
        _state.update { it.copy(isStarting = true, fatalFailure = null) }
        ContextCompat.startForegroundService(
            context,
            Intent(context, FileServerService::class.java).setAction(FileServerService.ACTION_START),
        )
    }

    fun stop() {
        context.startService(
            Intent(context, FileServerService::class.java).setAction(FileServerService.ACTION_STOP),
        )
    }

    internal fun update(transform: (FileServerState) -> FileServerState) = _state.update(transform)

    /** Back to idle, keeping nothing: unlike measurements, none of this is user data. */
    internal fun reset() = _state.update { FileServerState() }
}
