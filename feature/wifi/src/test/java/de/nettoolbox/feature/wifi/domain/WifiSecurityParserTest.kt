package de.nettoolbox.feature.wifi.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The capability strings are taken from what drivers actually report.
 */
class WifiSecurityParserTest {

    @Test
    fun `recognises the common personal modes`() {
        assertEquals(WifiSecurity.WPA2, WifiSecurityParser.parse("[WPA2-PSK-CCMP][ESS]").security)
        assertEquals(WifiSecurity.WPA, WifiSecurityParser.parse("[WPA-PSK-TKIP][ESS]").security)
        assertEquals(WifiSecurity.WEP, WifiSecurityParser.parse("[WEP][ESS]").security)
        assertEquals(WifiSecurity.OPEN, WifiSecurityParser.parse("[ESS]").security)
    }

    @Test
    fun `SAE is reported as WPA3, including in transition mode`() {
        assertEquals(WifiSecurity.WPA3, WifiSecurityParser.parse("[RSN-SAE-CCMP][ESS]").security)
        // A transition-mode AP advertises both; WPA3 is the more useful answer.
        assertEquals(
            WifiSecurity.WPA3,
            WifiSecurityParser.parse("[RSN-PSK+SAE-CCMP][ESS]").security,
        )
    }

    @Test
    fun `recognises OWE, which is encrypted without a passphrase`() {
        assertEquals(WifiSecurity.OWE, WifiSecurityParser.parse("[RSN-OWE-CCMP][ESS]").security)
    }

    @Test
    fun `flags enterprise and WPS separately from the mode`() {
        val enterprise = WifiSecurityParser.parse("[RSN-EAP-CCMP][ESS]")

        assertEquals(WifiSecurity.WPA2, enterprise.security)
        assertTrue(enterprise.isEnterprise)
        assertEquals("WPA2-Enterprise", enterprise.label)

        assertTrue(WifiSecurityParser.parse("[WPA2-PSK-CCMP][WPS][ESS]").isWps)
    }

    @Test
    fun `marks the modes worth flagging on a site survey`() {
        assertTrue(WifiSecurityParser.parse("[ESS]").isWeak)
        assertTrue(WifiSecurityParser.parse("[WEP][ESS]").isWeak)
        assertTrue(WifiSecurityParser.parse("[WPA-PSK-TKIP][ESS]").isWeak)

        assertFalse(WifiSecurityParser.parse("[WPA2-PSK-CCMP][ESS]").isWeak)
        assertFalse(WifiSecurityParser.parse("[RSN-SAE-CCMP][ESS]").isWeak)
    }

    @Test
    fun `a missing capability string is unknown, not open`() {
        // Reporting "open" for a network we know nothing about would be a
        // security claim the data does not support.
        assertEquals(WifiSecurity.UNKNOWN, WifiSecurityParser.parse(null).security)
        assertEquals(WifiSecurity.UNKNOWN, WifiSecurityParser.parse("").security)
    }
}
