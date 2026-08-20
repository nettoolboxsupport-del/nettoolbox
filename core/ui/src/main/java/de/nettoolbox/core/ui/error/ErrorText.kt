package de.nettoolbox.core.ui.error

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.ui.R
import kotlin.math.ceil

/**
 * Headline for an error: what went wrong.
 */
@Composable
fun errorTitle(error: NetToolboxError): String = stringResource(
    when (error.reason) {
        ErrorReason.PERMISSION_DENIED,
        ErrorReason.PERMISSION_PERMANENTLY_DENIED,
        -> R.string.error_title_permission

        ErrorReason.LOCATION_DISABLED -> R.string.error_title_location_disabled
        ErrorReason.NO_NETWORK -> R.string.error_title_no_network
        ErrorReason.HOST_UNREACHABLE -> R.string.error_title_host_unreachable
        ErrorReason.TIMEOUT -> R.string.error_title_timeout
        ErrorReason.CONNECTION_REFUSED -> R.string.error_title_connection_refused
        ErrorReason.PORT_IN_USE -> R.string.error_title_port_in_use
        ErrorReason.SCAN_THROTTLED -> R.string.error_title_scan_throttled
        ErrorReason.MULTICAST_LOCK_DENIED -> R.string.error_title_multicast
        ErrorReason.NATIVE_UNAVAILABLE -> R.string.error_title_native
        ErrorReason.UNSUPPORTED_ON_DEVICE -> R.string.error_title_unsupported
        ErrorReason.INVALID_INPUT -> R.string.error_title_invalid_input
        ErrorReason.IO -> R.string.error_title_io
        ErrorReason.AUTH_FAILED -> R.string.error_title_auth_failed
        ErrorReason.HOST_KEY_UNKNOWN -> R.string.error_title_host_key_unknown
        ErrorReason.HOST_KEY_CHANGED -> R.string.error_title_host_key_changed
        ErrorReason.HOST_KEY_REJECTED -> R.string.error_title_host_key_rejected
        ErrorReason.UNKNOWN -> R.string.error_title_unknown
    },
)

/**
 * What the user can actually do about it. The spec forbids dead-end errors, so
 * every reason has a hint - and a throttled scan gets a countdown instead of a
 * vague "try again later".
 */
@Composable
fun errorHint(error: NetToolboxError): String {
    val seconds = error.retryAfterMillis?.let { ceil(it / 1000.0).toInt() }
    if (error.reason == ErrorReason.SCAN_THROTTLED && seconds != null) {
        return stringResource(R.string.error_hint_scan_throttled_countdown, seconds)
    }

    return stringResource(
        when (error.reason) {
            ErrorReason.PERMISSION_DENIED -> R.string.error_hint_permission
            ErrorReason.PERMISSION_PERMANENTLY_DENIED -> R.string.error_hint_permission_permanent
            ErrorReason.LOCATION_DISABLED -> R.string.error_hint_location_disabled
            ErrorReason.NO_NETWORK -> R.string.error_hint_no_network
            ErrorReason.HOST_UNREACHABLE -> R.string.error_hint_host_unreachable
            ErrorReason.TIMEOUT -> R.string.error_hint_timeout
            ErrorReason.CONNECTION_REFUSED -> R.string.error_hint_connection_refused
            ErrorReason.PORT_IN_USE -> R.string.error_hint_port_in_use
            ErrorReason.SCAN_THROTTLED -> R.string.error_hint_scan_throttled
            ErrorReason.MULTICAST_LOCK_DENIED -> R.string.error_hint_multicast
            ErrorReason.NATIVE_UNAVAILABLE -> R.string.error_hint_native
            ErrorReason.UNSUPPORTED_ON_DEVICE -> R.string.error_hint_unsupported
            ErrorReason.INVALID_INPUT -> R.string.error_hint_invalid_input
            ErrorReason.IO -> R.string.error_hint_io
            ErrorReason.AUTH_FAILED -> R.string.error_hint_auth_failed
            ErrorReason.HOST_KEY_UNKNOWN -> R.string.error_hint_host_key_unknown
            ErrorReason.HOST_KEY_CHANGED -> R.string.error_hint_host_key_changed
            ErrorReason.HOST_KEY_REJECTED -> R.string.error_hint_host_key_rejected
            ErrorReason.UNKNOWN -> R.string.error_hint_unknown
        },
    )
}
