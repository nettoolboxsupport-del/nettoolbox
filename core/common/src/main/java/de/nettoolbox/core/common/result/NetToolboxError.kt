package de.nettoolbox.core.common.result

import java.io.IOException
import java.net.BindException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Why something failed, as data rather than as a message string.
 *
 * The spec forbids swallowing errors in a toast; every failure has to carry a
 * cause and a recovery hint. Keeping the reason as an enum here lets :core:ui map
 * it to localised text in both languages, and lets tests assert on the reason
 * instead of on a string.
 */
enum class ErrorReason {
    PERMISSION_DENIED,
    PERMISSION_PERMANENTLY_DENIED,
    LOCATION_DISABLED,
    NO_NETWORK,
    HOST_UNREACHABLE,
    TIMEOUT,
    CONNECTION_REFUSED,
    PORT_IN_USE,
    SCAN_THROTTLED,
    MULTICAST_LOCK_DENIED,
    NATIVE_UNAVAILABLE,
    UNSUPPORTED_ON_DEVICE,
    INVALID_INPUT,
    IO,

    // --- SSH ---------------------------------------------------------------
    /** Credentials were rejected, or every offered authentication method failed. */
    AUTH_FAILED,

    /**
     * The host is not in the known-hosts store yet.
     *
     * Not an error in the usual sense - it is the first-contact decision the
     * user has to make. It is modelled as a failure so that connecting can
     * never proceed by default without that decision.
     */
    HOST_KEY_UNKNOWN,

    /**
     * The host presented a different key than the one stored for it.
     *
     * This is what a man-in-the-middle looks like. It is kept apart from
     * HOST_KEY_UNKNOWN on purpose: the two demand completely different
     * warnings, and merging them would let the graver case borrow the milder
     * one's wording.
     */
    HOST_KEY_CHANGED,

    /** The user declined to trust the presented host key. */
    HOST_KEY_REJECTED,

    UNKNOWN,
}

/**
 * @param reason machine-readable cause, drives the recovery hint shown to the user
 * @param detail optional technical detail (host name, port, exception message);
 *   never contains personal data and is safe to show in the UI
 * @param retryAfterMillis set when the operation can succeed again later, e.g.
 *   Wi-Fi scan throttling - the UI turns this into a countdown
 */
data class NetToolboxError(
    val reason: ErrorReason,
    val detail: String? = null,
    val retryAfterMillis: Long? = null,
    val cause: Throwable? = null,
)

/**
 * Maps platform exceptions onto the error domain.
 *
 * [CancellationException] is deliberately not mapped - a cancelled operation is
 * not a failure, and swallowing it here would break structured concurrency.
 */
fun Throwable.toNetToolboxError(): NetToolboxError = when (this) {
    is CancellationException -> throw this
    is SocketTimeoutException -> NetToolboxError(ErrorReason.TIMEOUT, message, cause = this)
    is UnknownHostException -> NetToolboxError(ErrorReason.HOST_UNREACHABLE, message, cause = this)
    is NoRouteToHostException -> NetToolboxError(ErrorReason.HOST_UNREACHABLE, message, cause = this)
    is PortUnreachableException -> NetToolboxError(ErrorReason.HOST_UNREACHABLE, message, cause = this)
    is ConnectException -> NetToolboxError(ErrorReason.CONNECTION_REFUSED, message, cause = this)
    is BindException -> NetToolboxError(ErrorReason.PORT_IN_USE, message, cause = this)
    is SecurityException -> NetToolboxError(ErrorReason.PERMISSION_DENIED, message, cause = this)
    is UnsatisfiedLinkError -> NetToolboxError(ErrorReason.NATIVE_UNAVAILABLE, message, cause = this)
    is IllegalArgumentException -> NetToolboxError(ErrorReason.INVALID_INPUT, message, cause = this)
    is IOException -> NetToolboxError(ErrorReason.IO, message, cause = this)
    else -> NetToolboxError(ErrorReason.UNKNOWN, message, cause = this)
}
