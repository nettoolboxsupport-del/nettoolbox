package de.nettoolbox.feature.wifi.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import de.nettoolbox.feature.wifi.domain.ScanThrottle
import de.nettoolbox.feature.wifi.domain.WifiChannels
import de.nettoolbox.feature.wifi.domain.WifiNetwork
import de.nettoolbox.feature.wifi.domain.WifiSecurityParser
import de.nettoolbox.feature.wifi.domain.WifiStandard
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ScanRequestResult {
    data object Started : ScanRequestResult

    /** The platform refused; results from other apps' scans still arrive. */
    data class Throttled(val retryAfterMillis: Long) : ScanRequestResult

    data object Unavailable : ScanRequestResult
}

/**
 * Wi-Fi scanning, throttling included.
 *
 * Two sources feed the same stream: scans this app requests, and the
 * SCAN_RESULTS_AVAILABLE broadcast, which fires whenever *any* app or the system
 * scans. That passive channel is what keeps the list fresh while the app's own
 * scan budget is exhausted - without it the analyzer would sit still for two
 * minutes at a time.
 */
@Singleton
class WifiScanRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val wifiManager: WifiManager?
        get() = context.getSystemService<WifiManager>()

    /** Timestamps of scans this app requested, for the throttle calculation. */
    private val recentScans = AtomicReference<List<Long>>(emptyList())

    val recentScanTimestamps: List<Long> get() = recentScans.get()

    fun observeScanResults(): Flow<List<WifiNetwork>> = callbackFlow {
        val manager = wifiManager
        if (manager == null) {
            send(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        fun publish() {
            // Requires location permission; without it the platform returns an
            // empty list rather than throwing, which is why the screen gates on
            // the permission instead of on an error.
            val results = runCatching { manager.scanResults }.getOrDefault(emptyList())
            trySend(results.map { it.toWifiNetwork() })
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = publish()
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        publish()

        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    /**
     * Asks the platform for a fresh scan. The throttle is evaluated here rather
     * than after the fact, so the UI can show a countdown instead of a button
     * that quietly fails.
     */
    fun requestScan(now: Long = System.currentTimeMillis()): ScanRequestResult {
        val manager = wifiManager ?: return ScanRequestResult.Unavailable

        val pruned = ScanThrottle.prune(recentScans.get(), now)
        val wait = ScanThrottle.millisUntilNextScan(pruned, now)
        if (wait > 0) {
            recentScans.set(pruned)
            return ScanRequestResult.Throttled(wait)
        }

        @Suppress("DEPRECATION")
        // startScan is deprecated from API 28 with no replacement for apps that
        // are not the system Wi-Fi picker; it still works and remains the only
        // way to request a scan.
        val started = runCatching { manager.startScan() }.getOrDefault(false)

        return if (started) {
            recentScans.set(pruned + now)
            ScanRequestResult.Started
        } else {
            // A false return also means throttled on most devices.
            recentScans.set(pruned + now)
            ScanRequestResult.Throttled(ScanThrottle.WINDOW_MILLIS)
        }
    }

    fun isWifiEnabled(): Boolean = wifiManager?.isWifiEnabled == true

    private fun ScanResult.toWifiNetwork(): WifiNetwork {
        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wifiSsid?.toString()?.trim('"')
        } else {
            @Suppress("DEPRECATION")
            SSID
        }

        return WifiNetwork(
            bssid = BSSID.orEmpty(),
            ssid = ssid?.takeIf { it.isNotBlank() },
            rssiDbm = level,
            frequencyMhz = frequency,
            channel = WifiChannels.channelOf(frequency),
            band = WifiChannels.bandOf(frequency),
            channelWidthMhz = channelWidth.toWidthMhz(),
            centerFrequency0 = centerFreq0.takeIf { it > 0 },
            centerFrequency1 = centerFreq1.takeIf { it > 0 },
            security = WifiSecurityParser.parse(capabilities),
            standard = readStandard(),
            capabilities = capabilities,
            lastSeenMillis = System.currentTimeMillis(),
        )
    }

    private fun Int.toWidthMhz(): Int? = when (this) {
        ScanResult.CHANNEL_WIDTH_20MHZ -> 20
        ScanResult.CHANNEL_WIDTH_40MHZ -> 40
        ScanResult.CHANNEL_WIDTH_80MHZ -> 80
        ScanResult.CHANNEL_WIDTH_160MHZ -> 160
        ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
        else -> null
    }

    private fun ScanResult.readStandard(): WifiStandard {
        // getWifiStandard exists from Android 11. Below that the platform simply
        // does not know, and inferring a generation from the channel width would
        // be an invention.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return WifiStandard.UNKNOWN

        return when (wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> WifiStandard.LEGACY
            ScanResult.WIFI_STANDARD_11N -> WifiStandard.N
            ScanResult.WIFI_STANDARD_11AC -> WifiStandard.AC
            ScanResult.WIFI_STANDARD_11AX -> WifiStandard.AX
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                wifiStandard == ScanResult.WIFI_STANDARD_11BE
            ) {
                WifiStandard.BE
            } else {
                WifiStandard.UNKNOWN
            }
        }
    }
}
