package de.nettoolbox.core.permissions

import android.Manifest
import androidx.annotation.StringRes

/**
 * Runtime permissions the app can ask for, with the API level from which they
 * exist.
 *
 * The SDK gating is data rather than scattered `Build.VERSION` checks: asking
 * for NEARBY_WIFI_DEVICES on Android 12 silently fails.
 *
 * ACCESS_BACKGROUND_LOCATION is absent on purpose - see the app manifest. The
 * drive-test foreground service is always started from the UI, which is the
 * case Android permits without it.
 */
enum class AppPermission(
    val manifestName: String,
    val minSdk: Int = 1,
) {
    FINE_LOCATION(Manifest.permission.ACCESS_FINE_LOCATION),
    COARSE_LOCATION(Manifest.permission.ACCESS_COARSE_LOCATION),
    READ_PHONE_STATE(Manifest.permission.READ_PHONE_STATE),
    NEARBY_WIFI_DEVICES(Manifest.permission.NEARBY_WIFI_DEVICES, minSdk = 33),
    POST_NOTIFICATIONS(Manifest.permission.POST_NOTIFICATIONS, minSdk = 33),
    ;

    fun isRelevantOn(sdkInt: Int): Boolean = sdkInt >= minSdk
}

/**
 * A feature's permission requirement, as one named set.
 *
 * Screens ask for a bundle, never for individual strings - that keeps the
 * rationale text and the permission list from drifting apart, and it is the only
 * place that knows background location must be requested on its own.
 */
enum class PermissionBundle(
    @param:StringRes val rationaleRes: Int,
    private val members: List<AppPermission>,
) {
    /** Serving cell, neighbours and signal metrics. */
    CELLULAR_LIVE(
        rationaleRes = R.string.permission_rationale_cellular,
        members = listOf(
            AppPermission.FINE_LOCATION,
            AppPermission.COARSE_LOCATION,
            AppPermission.READ_PHONE_STATE,
        ),
    ),

    /** Wi-Fi scanning. Scan results are treated as a location source, so
     *  NEARBY_WIFI_DEVICES is requested without `neverForLocation`. */
    WIFI_SCAN(
        rationaleRes = R.string.permission_rationale_wifi,
        members = listOf(
            AppPermission.FINE_LOCATION,
            AppPermission.COARSE_LOCATION,
            AppPermission.NEARBY_WIFI_DEVICES,
        ),
    ),

    /** Foreground-service notifications for logging, iperf3 server, TFTP. */
    SERVICE_NOTIFICATIONS(
        rationaleRes = R.string.permission_rationale_notifications,
        members = listOf(AppPermission.POST_NOTIFICATIONS),
    ),
    ;

    /**
     * The members that actually exist on the given API level. Everything else
     * would be a request Android never surfaces to the user.
     */
    fun permissionsFor(sdkInt: Int): List<AppPermission> =
        members.filter { it.isRelevantOn(sdkInt) }
}
