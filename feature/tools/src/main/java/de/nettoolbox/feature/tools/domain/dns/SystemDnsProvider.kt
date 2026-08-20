package de.nettoolbox.feature.tools.domain.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the device itself is configured to use.
 *
 * Read from [LinkProperties] of the active network rather than from the
 * long-deprecated `getprop net.dns1`, which has returned nothing useful since
 * Android 8 and never knew about per-network resolvers or IPv6.
 */
data class SystemDnsConfiguration(
    val servers: List<String>,
    val searchDomains: String?,
    val privateDnsActive: Boolean,
    val privateDnsServerName: String?,
)

@Singleton
class SystemDnsProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun current(): SystemDnsConfiguration {
        val connectivityManager = context.getSystemService<ConnectivityManager>()
        val network = connectivityManager?.activeNetwork
        val linkProperties = network?.let { connectivityManager.getLinkProperties(it) }
            ?: return SystemDnsConfiguration(emptyList(), null, false, null)

        return SystemDnsConfiguration(
            servers = linkProperties.dnsServers.mapNotNull { it.hostAddress },
            searchDomains = linkProperties.domains,
            privateDnsActive = linkProperties.isPrivateDnsActive,
            privateDnsServerName = linkProperties.privateDnsServerName,
        )
    }

    /**
     * The system resolvers as query targets. Plain UDP even when private DNS is
     * on: the app then queries the same server directly, and showing that its
     * answer matches the system's is the point of the comparison.
     */
    fun asTargets(): List<DnsResolverTarget> = current().servers.map { server ->
        DnsResolverTarget(
            label = server,
            kind = DnsTransportKind.UDP,
            address = server,
        )
    }
}
