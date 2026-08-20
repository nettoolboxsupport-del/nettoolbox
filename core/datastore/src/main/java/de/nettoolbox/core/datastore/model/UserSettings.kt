package de.nettoolbox.core.datastore.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
    FIELD,
}

/**
 * How ICMP is actually produced on this device.
 *
 * Which of these works cannot be known in advance - unprivileged ICMP datagram
 * sockets depend on the kernel's `ping_group_range`, and the `ping` binary's
 * output format differs between toybox and busybox. The app probes once and
 * caches the answer here, and the user can override it.
 */
@Serializable
enum class PingMethod {
    /** Probe on first use and pick the best available. */
    AUTO,

    /** SOCK_DGRAM + IPPROTO_ICMP through the native module. */
    ICMP_DATAGRAM,

    /** /system/bin/ping parsed from stdout. */
    SYSTEM_BINARY,

    /** TCP connect timing - labelled as such in the UI, never as ICMP. */
    TCP_CONNECT,
}

/**
 * Display language.
 *
 * [SYSTEM] follows the device, which is what most users want. The explicit
 * choices exist because a technician handing a phone to a customer, or working on
 * an English-language site with a German phone, needs to switch without changing
 * the whole device.
 */
@Serializable
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    GERMAN("de"),
    ENGLISH("en"),
}

@Serializable
enum class MapTileSource {
    OSM_RASTER,
    MAPTILER,
    OFFLINE_MBTILES,
}

/**
 * All user settings in one serializable object.
 *
 * Every field has a default, and unknown fields are ignored on read, so an app
 * downgrade or an interrupted write cannot leave the user without settings.
 */
@Serializable
data class UserSettings(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val dynamicColor: Boolean = true,
    val language: AppLanguage = AppLanguage.SYSTEM,

    /** Shown as a greeting on the dashboard. Empty means no greeting. */
    val userName: String = "",

    /** Cellular sampling interval; the platform caps this at roughly 1 Hz. */
    val cellSampleIntervalSeconds: Int = 2,

    /** Skip a sample unless the device moved at least this far. 0 disables it. */
    val minUpdateDistanceMeters: Int = 0,

    val pingMethod: PingMethod = PingMethod.AUTO,

    /** Result of the one-off probe; null until it has run. */
    val detectedPingMethod: PingMethod? = null,

    /** Concurrent sockets during an IP or port scan. */
    val maxParallelProbes: Int = 64,

    val mapTileSource: MapTileSource = MapTileSource.OSM_RASTER,

    /** Supplied by the user, never bundled - the spec forbids hardcoded keys. */
    val openCellIdApiKey: String? = null,
    val mapTilerApiKey: String? = null,

    /** Confirmation that scans may only run on authorised networks. */
    val scannerDisclaimerAccepted: Boolean = false,

    /**
     * Whether the first-run introduction has been dismissed.
     *
     * Defaults to false so an existing installation sees it once after the
     * update too. That is intended: it is where the app explains what it does
     * with location and telephony data, and an upgrading user has never been
     * told either.
     */
    val onboardingCompleted: Boolean = false,

    /** Drop samples older than this. 0 keeps everything. */
    val pruneAfterDays: Int = 0,

    /**
     * BSSIDs the user put on the Wi-Fi watchlist. Stored rather than kept in
     * memory because the point of a watchlist is that it survives leaving the
     * site and coming back.
     */
    val watchedBssids: Set<String> = emptySet(),
)
