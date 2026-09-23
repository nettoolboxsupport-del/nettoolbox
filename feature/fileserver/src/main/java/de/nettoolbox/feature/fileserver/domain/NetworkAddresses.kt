package de.nettoolbox.feature.fileserver.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/** One address a client could actually use to reach the servers. */
data class ReachableAddress(
    val interfaceName: String,
    val address: String,
    val isIpv6: Boolean,
    val transport: AddressTransport,
) {
    /**
     * The literal form for a URL.
     *
     * IPv6 needs brackets, and a link-local address keeps its zone index -
     * without it "fe80::1" is ambiguous across interfaces and simply does not
     * connect.
     */
    val urlLiteral: String get() = if (isIpv6) "[$address]" else address
}

enum class AddressTransport { WIFI, CELLULAR, ETHERNET, LOOPBACK, OTHER }

/**
 * Works out where the servers can be reached, and on which interface to listen.
 *
 * This is the part users get wrong on every other file-server app: the app says
 * "running" and shows one address, the client cannot connect, and there is no
 * way to tell whether the phone is on a guest network with client isolation,
 * whether the address shown belongs to the mobile interface, or whether the
 * server bound to the wrong thing. Showing every address with its interface
 * turns that into something a technician can diagnose in one look.
 */
@Singleton
class NetworkAddresses @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * The address to bind to, or null for "every interface".
     *
     * Returning null for [BindScope.WIFI_ONLY] when there is no Wi-Fi address
     * would silently widen the exposure to every interface, which is the
     * opposite of what was asked. The caller treats null-with-WIFI_ONLY as a
     * refusal to start, and the failure is reported rather than downgraded.
     */
    fun bindAddressFor(scope: BindScope): InetAddress? = when (scope) {
        BindScope.ALL_INTERFACES -> null
        BindScope.LOOPBACK_ONLY -> InetAddress.getByName("127.0.0.1")
        BindScope.WIFI_ONLY -> wifiAddress()
    }

    /** True when a Wi-Fi-only bind is currently impossible. */
    fun wifiAvailable(): Boolean = wifiAddress() != null

    /**
     * Every address a client could dial, most useful first.
     *
     * Loopback is included only when nothing else is up: it is the honest
     * answer for a device with no network, and it is genuinely useful for
     * testing from a terminal on the same phone.
     */
    fun reachable(scope: BindScope): List<ReachableAddress> {
        if (scope == BindScope.LOOPBACK_ONLY) {
            return listOf(
                ReachableAddress("lo", "127.0.0.1", isIpv6 = false, transport = AddressTransport.LOOPBACK),
            )
        }

        val wifiInterfaceNames = wifiInterfaceNames()
        val found = buildList {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
                ?: return@buildList
            for (nif in interfaces) {
                if (!runCatching { nif.isUp }.getOrDefault(false)) continue
                if (runCatching { nif.isLoopback }.getOrDefault(false)) continue
                val transport = transportOf(nif.name, wifiInterfaceNames)
                if (scope == BindScope.WIFI_ONLY && transport != AddressTransport.WIFI) continue

                for (address in nif.inetAddresses) {
                    // A link-local IPv6 address is reachable, but only with the
                    // zone index, and no client types that by hand. Skipped so
                    // the list holds addresses that actually work when copied.
                    if (address.isLinkLocalAddress || address.isAnyLocalAddress) continue
                    val literal = address.hostAddress?.substringBefore('%') ?: continue
                    add(
                        ReachableAddress(
                            interfaceName = nif.name,
                            address = literal,
                            isIpv6 = address is Inet6Address,
                            transport = transport,
                        ),
                    )
                }
            }
        }

        if (found.isEmpty()) {
            return listOf(
                ReachableAddress("lo", "127.0.0.1", isIpv6 = false, transport = AddressTransport.LOOPBACK),
            )
        }

        // Wi-Fi IPv4 first: it is what a client on the same network will use,
        // and it is the one short enough to read off a screen and type.
        return found.sortedWith(
            compareBy<ReachableAddress> { it.transport != AddressTransport.WIFI }
                .thenBy { it.isIpv6 }
                .thenBy { it.interfaceName },
        )
    }

    private fun wifiAddress(): InetAddress? {
        val names = wifiInterfaceNames()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return null
        for (nif in interfaces) {
            if (!runCatching { nif.isUp }.getOrDefault(false)) continue
            if (transportOf(nif.name, names) != AddressTransport.WIFI) continue
            // IPv4 specifically: binding a server socket to an IPv6 address
            // makes it unreachable from IPv4-only clients, and on a local
            // network almost every client is IPv4-only.
            for (address in nif.inetAddresses) {
                if (address is Inet4Address && !address.isLinkLocalAddress) return address
            }
        }
        return null
    }

    /**
     * Interface names that the system currently reports as Wi-Fi.
     *
     * Asked of ConnectivityManager rather than matched against "wlan": vendors
     * do not agree on interface naming, and the platform already knows the
     * answer. The name match below is only the fallback for interfaces the
     * system has no capabilities for.
     */
    private fun wifiInterfaceNames(): Set<String> {
        val manager = context.getSystemService<ConnectivityManager>() ?: return emptySet()
        return buildSet {
            for (network in manager.allNetworks) {
                val capabilities = manager.getNetworkCapabilities(network) ?: continue
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                manager.getLinkProperties(network)?.interfaceName?.let(::add)
            }
        }
    }

    private fun transportOf(name: String, wifiNames: Set<String>): AddressTransport = when {
        name in wifiNames -> AddressTransport.WIFI
        name.startsWith("wlan") || name.startsWith("ap") -> AddressTransport.WIFI
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") ->
            AddressTransport.CELLULAR
        name.startsWith("eth") || name.startsWith("usb") -> AddressTransport.ETHERNET
        name.startsWith("lo") -> AddressTransport.LOOPBACK
        else -> AddressTransport.OTHER
    }
}
