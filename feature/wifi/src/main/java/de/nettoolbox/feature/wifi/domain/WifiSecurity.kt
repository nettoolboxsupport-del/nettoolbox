package de.nettoolbox.feature.wifi.domain

enum class WifiSecurity(val label: String) {
    OPEN("Open"),
    WEP("WEP"),
    WPA("WPA"),
    WPA2("WPA2"),
    WPA3("WPA3"),
    /** Opportunistic Wireless Encryption - encrypted but without a passphrase. */
    OWE("OWE"),
    UNKNOWN("?"),
}

data class WifiSecurityInfo(
    val security: WifiSecurity,
    val isEnterprise: Boolean,
    val isWps: Boolean,
) {
    val label: String get() = if (isEnterprise) "${security.label}-Enterprise" else security.label

    /**
     * Networks a technician should flag on a site survey. WEP is broken, plain
     * WPA is deprecated, and an open network is open.
     */
    val isWeak: Boolean
        get() = security == WifiSecurity.OPEN ||
            security == WifiSecurity.WEP ||
            security == WifiSecurity.WPA
}

/**
 * Reads `ScanResult.capabilities`.
 *
 * The raw platform string is parsed rather than mapped through a platform enum,
 * because the string is what the driver actually reports and it grows with every
 * Wi-Fi generation. Kept pure so all the shapes seen in the field can be tested.
 */
object WifiSecurityParser {

    fun parse(capabilities: String?): WifiSecurityInfo {
        val text = capabilities?.uppercase().orEmpty()

        val security = when {
            // SAE is WPA3's handshake; a transition-mode AP advertises both, and
            // WPA3 is the more useful answer of the two.
            "SAE" in text -> WifiSecurity.WPA3
            "OWE" in text -> WifiSecurity.OWE
            "RSN" in text || "WPA2" in text -> WifiSecurity.WPA2
            "WPA" in text -> WifiSecurity.WPA
            "WEP" in text -> WifiSecurity.WEP
            text.isEmpty() -> WifiSecurity.UNKNOWN
            else -> WifiSecurity.OPEN
        }

        return WifiSecurityInfo(
            security = security,
            isEnterprise = "EAP" in text,
            isWps = "WPS" in text,
        )
    }
}
