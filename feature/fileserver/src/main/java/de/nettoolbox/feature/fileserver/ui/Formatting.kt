package de.nettoolbox.feature.fileserver.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.nettoolbox.feature.fileserver.R
import de.nettoolbox.feature.fileserver.domain.FileOpError
import de.nettoolbox.feature.fileserver.domain.TransferLog
import de.nettoolbox.feature.fileserver.service.ProtocolFailure
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Sizes in units people actually use.
 *
 * Binary prefixes with decimal names - "MB" for 1024 KB - because that is what
 * every file manager, every switch console and every vendor's release page
 * prints. Being pedantically correct with MiB here would make the number in
 * this app disagree with the number on the device the file is going to.
 */
fun formatBytes(bytes: Long): String {
    if (abs(bytes) < 1024) return "$bytes B"
    var value = bytes.toDouble()
    val units = listOf("KB", "MB", "GB", "TB")
    var index = -1
    while (abs(value) >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    // One decimal below 10, none above: "9.4 MB" is useful, "947.3 MB" is not.
    val pattern = if (abs(value) < 10) "%.1f %s" else "%.0f %s"
    return String.format(Locale.getDefault(), pattern, value, units[index])
}

fun formatRate(bytesPerSecond: Double): String =
    "${formatBytes(bytesPerSecond.toLong())}/s"

fun formatDuration(millis: Long): String = when {
    millis < 1000 -> "$millis ms"
    millis < 60_000 -> String.format(Locale.getDefault(), "%.1f s", millis / 1000.0)
    else -> String.format(
        Locale.getDefault(),
        "%d:%02d",
        millis / 60_000,
        (millis % 60_000) / 1000,
    )
}

fun formatTimeOfDay(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

fun formatDate(millis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * Turns a file operation failure into a sentence.
 *
 * The mapping lives here rather than in the domain layer so the errors stay
 * data - testable, and translated in one place instead of at every call site.
 */
@Composable
fun FileOpError.asText(): String = when (this) {
    FileOpError.OutsideRoot -> stringResource(R.string.fileop_error_outside)
    FileOpError.AlreadyExists -> stringResource(R.string.fileop_error_exists)
    FileOpError.NotFound -> stringResource(R.string.fileop_error_not_found)
    FileOpError.NotWritable -> stringResource(R.string.fileop_error_not_writable)
    FileOpError.InvalidName -> stringResource(R.string.fileop_error_invalid_name)
    FileOpError.TargetInsideSource -> stringResource(R.string.fileop_error_target_inside)
    is FileOpError.Io -> message
        ?.let { stringResource(R.string.fileop_error_io, it) }
        ?: stringResource(R.string.fileop_error_io_unknown)
}

@Composable
fun ProtocolFailure.asText(port: Int): String = when (this) {
    ProtocolFailure.PORT_IN_USE -> stringResource(R.string.protocol_failed_port_in_use, port)
    ProtocolFailure.PRIVILEGED_PORT -> stringResource(R.string.protocol_failed_privileged)
    ProtocolFailure.NO_BIND_ADDRESS -> stringResource(R.string.protocol_failed_no_bind)
    ProtocolFailure.TLS_UNAVAILABLE -> stringResource(R.string.protocol_failed_tls)
    ProtocolFailure.OTHER -> stringResource(R.string.protocol_failed_other)
}

/**
 * Maps a log marker to its sentence.
 *
 * The servers record markers rather than prose because they run far from any
 * Context and because a log line assembled in one language cannot be
 * re-rendered in another. Anything unrecognised falls through unchanged, which
 * is the right outcome for a message that came from a library rather than from
 * this code.
 */
@Composable
fun logMessageText(marker: String): String {
    @StringRes val resource = LOG_MESSAGES[marker] ?: return marker
    return stringResource(resource)
}

private val LOG_MESSAGES: Map<String, Int> = mapOf(
    TransferLog.MESSAGE_TRANSFER_COMPLETE to R.string.log_msg_transfer_complete,

    "tftp.malformed" to R.string.log_msg_tftp_malformed,
    "tftp.mode" to R.string.log_msg_tftp_mode,
    "tftp.outside" to R.string.log_msg_tftp_outside,
    "tftp.notfound" to R.string.log_msg_tftp_notfound,
    "tftp.readonly" to R.string.log_msg_tftp_readonly,
    "tftp.exists" to R.string.log_msg_tftp_exists,
    "tftp.notwritable" to R.string.log_msg_tftp_notwritable,
    "tftp.diskfull" to R.string.log_msg_tftp_diskfull,
    "tftp.timeout" to R.string.log_msg_tftp_timeout,
    "tftp.oack" to R.string.log_msg_tftp_oack,
    "tftp.abort" to R.string.log_msg_tftp_abort,
    "tftp.rename" to R.string.log_msg_tftp_rename,
    "tftp.write" to R.string.log_msg_tftp_write,
    "tftp.busy" to R.string.log_msg_tftp_busy,

    "ftp.connected" to R.string.log_msg_ftp_connected,
    "ftp.disconnected" to R.string.log_msg_ftp_disconnected,
    "ftp.login.ok" to R.string.log_msg_ftp_login_ok,
    "ftp.login.failed" to R.string.log_msg_ftp_login_failed,
    "ftp.sent" to R.string.log_msg_ftp_sent,
    "ftp.received" to R.string.log_msg_ftp_received,
    "ftp.transfer.failed" to R.string.log_msg_ftp_transfer_failed,
    "ftp.deleted" to R.string.log_msg_ftp_deleted,
    "ftp.created" to R.string.log_msg_ftp_created,

    "ssh.login.ok" to R.string.log_msg_ssh_login_ok,
    "ssh.login.key" to R.string.log_msg_ssh_login_key,
    "ssh.login.failed" to R.string.log_msg_ssh_login_failed,
    "ssh.readonly" to R.string.log_msg_ssh_readonly,
    "ssh.link" to R.string.log_msg_ssh_link,
    "ssh.transfer.failed" to R.string.log_msg_ssh_transfer_failed,

    "server.autostop" to R.string.log_msg_server_autostop,
)
