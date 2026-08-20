package de.nettoolbox.feature.tools.domain.scan

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import de.nettoolbox.feature.tools.domain.subnet.Ipv4Address
import de.nettoolbox.feature.tools.domain.subnet.Ipv4Subnet
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

data class LocalNetwork(
    val interfaceName: String?,
    val ipv4Address: String?,
    val subnet: Ipv4Subnet?,
    val dnsServers: List<String>,
    val ipv6Addresses: List<String>,
) {
    val hasScannableSubnet: Boolean get() = subnet != null
}

/**
 * Where the device currently sits on the network.
 *
 * Read from [ConnectivityManager] and [LinkAddress] rather than from
 * `WifiManager.getDhcpInfo()`: that call is deprecated, knows nothing about IPv6,
 * and returns nothing at all on a mobile or VPN connection.
 */
@Singleton
class LocalNetworkProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Subnets larger than this are not offered for scanning. A /16 is 65k probes
     * and would take the better part of an hour - almost always the sign of a
     * VPN or a point-to-point link rather than a LAN worth sweeping.
     */
    private val smallestScannablePrefix = 22

    fun current(): LocalNetwork {
        val connectivityManager = context.getSystemService<ConnectivityManager>()
        val network = connectivityManager?.activeNetwork
        val linkProperties = network?.let { connectivityManager.getLinkProperties(it) }
            ?: return LocalNetwork(null, null, null, emptyList(), emptyList())

        val ipv4 = linkProperties.linkAddresses.firstOrNull { it.address is Inet4Address }

        return LocalNetwork(
            interfaceName = linkProperties.interfaceName,
            ipv4Address = ipv4?.address?.hostAddress,
            subnet = ipv4?.toScannableSubnet(),
            dnsServers = linkProperties.dnsServers.mapNotNull { it.hostAddress },
            ipv6Addresses = linkProperties.linkAddresses
                .filter { it.address !is Inet4Address }
                .mapNotNull { it.address.hostAddress },
        )
    }

    private fun LinkAddress.toScannableSubnet(): Ipv4Subnet? {
        val host = address.hostAddress?.let { Ipv4Address.parseOrNull(it) } ?: return null
        if (prefixLength < smallestScannablePrefix || prefixLength > 32) return null
        return Ipv4Subnet(host, prefixLength)
    }
}
