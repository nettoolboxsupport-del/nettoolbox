package de.nettoolbox.feature.wifi.domain

data class WifiNetwork(
    val bssid: String,
    val ssid: String?,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val channel: Int?,
    val band: WifiBand,
    /** Channel width in MHz where the platform reports one. */
    val channelWidthMhz: Int?,
    val centerFrequency0: Int?,
    val centerFrequency1: Int?,
    val security: WifiSecurityInfo,
    val standard: WifiStandard,
    val capabilities: String?,
    val lastSeenMillis: Long,
) {
    /** An empty SSID means the network does not broadcast its name. */
    val isHidden: Boolean get() = ssid.isNullOrBlank()

    val displayName: String get() = if (isHidden) "<hidden>" else ssid.orEmpty()
}

/**
 * Wi-Fi generation. Only reported from Android 11 on; below that the platform
 * gives no answer, and guessing one from the channel width would be a fabrication.
 */
enum class WifiStandard(val label: String) {
    LEGACY("802.11a/b/g"),
    N("Wi-Fi 4 (n)"),
    AC("Wi-Fi 5 (ac)"),
    AX("Wi-Fi 6 (ax)"),
    BE("Wi-Fi 7 (be)"),
    UNKNOWN("?"),
}
