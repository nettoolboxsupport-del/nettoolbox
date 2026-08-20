package de.nettoolbox.core.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The SDK gating is the part that silently breaks in the field: asking for a
 * permission that does not exist on the running Android version produces no
 * dialog and no error, just an empty scan result.
 */
class PermissionBundleTest {

    @Test
    fun `NEARBY_WIFI_DEVICES is only requested from Android 13 on`() {
        assertFalse(AppPermission.NEARBY_WIFI_DEVICES.isRelevantOn(sdkInt = 32))
        assertTrue(AppPermission.NEARBY_WIFI_DEVICES.isRelevantOn(sdkInt = 33))
    }

    @Test
    fun `the Wi-Fi bundle drops NEARBY_WIFI_DEVICES on Android 12`() {
        val onAndroid12 = PermissionBundle.WIFI_SCAN.permissionsFor(sdkInt = 32)
        val onAndroid13 = PermissionBundle.WIFI_SCAN.permissionsFor(sdkInt = 33)

        assertEquals(
            listOf(AppPermission.FINE_LOCATION, AppPermission.COARSE_LOCATION),
            onAndroid12,
        )
        assertTrue(AppPermission.NEARBY_WIFI_DEVICES in onAndroid13)
    }

    @Test
    fun `no bundle asks for background location`() {
        // Guards a decision, not an implementation detail. Drive-test logging
        // runs in a foreground service of type "location", which Android allows
        // to keep using location after the app leaves the foreground as long as
        // the service was started from the UI. Background location would add a
        // separate Play Store review with a submitted video and a permission
        // users are right to be wary of, for no capability the app lacks.
        //
        // Asserted on the manifest name rather than an enum constant so that
        // re-adding the permission cannot make this test pass again by accident.
        PermissionBundle.entries.forEach { bundle ->
            bundle.permissionsFor(sdkInt = 34).forEach { permission ->
                assertFalse(
                    permission.manifestName == "android.permission.ACCESS_BACKGROUND_LOCATION",
                    "${bundle.name} must not request background location - " +
                        "see the note in AndroidManifest.xml before changing this",
                )
            }
        }
    }

    @Test
    fun `the cellular bundle is available on the minimum supported SDK`() {
        val onAndroid9 = PermissionBundle.CELLULAR_LIVE.permissionsFor(sdkInt = 28)

        assertEquals(
            listOf(
                AppPermission.FINE_LOCATION,
                AppPermission.COARSE_LOCATION,
                AppPermission.READ_PHONE_STATE,
            ),
            onAndroid9,
        )
    }

    @Test
    fun `notification permission is skipped below Android 13`() {
        assertTrue(PermissionBundle.SERVICE_NOTIFICATIONS.permissionsFor(sdkInt = 30).isEmpty())
    }
}
