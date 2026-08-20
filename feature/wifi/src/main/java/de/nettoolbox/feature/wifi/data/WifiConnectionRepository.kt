package de.nettoolbox.feature.wifi.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import de.nettoolbox.feature.wifi.domain.WifiChannels
import de.nettoolbox.feature.wifi.domain.WifiBand
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

data class WifiConnection(
    val ssid: String?,
    val bssid: String?,
    val rssiDbm: Int?,
    val frequencyMhz: Int?,
    val channel: Int?,
    val band: WifiBand,
    val txLinkSpeedMbps: Int?,
    val rxLinkSpeedMbps: Int?,
    val ipv4Address: String?,
    val ipv6Addresses: List<String>,
    val gateway: String?,
    val dnsServers: List<String>,
    val mtu: Int?,
    val interfaceName: String?,
    /** Null when the platform has not decided yet. */
    val hasInternet: Boolean?,
    val isCaptivePortal: Boolean,
    val isMetered: Boolean,
)

/**
 * Details of the connection the device is actually on.
 *
 * Three sources, because no single one has the whole picture: [WifiManager] knows
 * the radio, [ConnectivityManager]'s link properties know the addressing, and the
 * network capabilities know whether the link actually reaches the internet.
 */
@Singleton
class WifiConnectionRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    @Suppress("DEPRECATION")
    fun current(): WifiConnection? {
        val connectivityManager = context.getSystemService<ConnectivityManager>() ?: return null
        val wifiManager = context.getSystemService<WifiManager>()

        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val linkProperties = connectivityManager.getLinkProperties(network)

        // getConnectionInfo is deprecated from API 31; the replacement delivers
        // WifiInfo through a network callback, which is worth switching to once
        // the screen needs live updates rather than a snapshot.
        val wifiInfo = wifiManager?.connectionInfo

        val frequency = wifiInfo?.frequency?.takeIf { it > 0 }
        val gateway = linkProperties?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress

        return WifiConnection(
            ssid = wifiInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID },
            bssid = wifiInfo?.bssid?.takeIf { it != INVALID_BSSID },
            rssiDbm = wifiInfo?.rssi,
            frequencyMhz = frequency,
            channel = frequency?.let { WifiChannels.channelOf(it) },
            band = frequency?.let { WifiChannels.bandOf(it) } ?: WifiBand.UNKNOWN,
            txLinkSpeedMbps = wifiInfo?.linkSpeed?.takeIf { it > 0 },
            rxLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiInfo?.rxLinkSpeedMbps?.takeIf { it > 0 }
            } else {
                null
            },
            ipv4Address = linkProperties?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address }
                ?.address
                ?.hostAddress,
            ipv6Addresses = linkProperties?.linkAddresses
                ?.filter { it.address !is Inet4Address }
                ?.mapNotNull { it.address.hostAddress }
                .orEmpty(),
            gateway = gateway,
            dnsServers = linkProperties?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            mtu = linkProperties?.mtu?.takeIf { it > 0 },
            interfaceName = linkProperties?.interfaceName,
            hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            // A captive portal is reported as the absence of "validated" plus the
            // explicit portal capability; showing it explains an "online but
            // nothing loads" complaint in one line.
            isCaptivePortal = capabilities
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            isMetered = capabilities
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
        )
    }

    private companion object {
        const val UNKNOWN_SSID = "<unknown ssid>"
        const val INVALID_BSSID = "02:00:00:00:00:00"
    }
}
