package de.nettoolbox.feature.tools.domain.scan

import android.content.Context
import android.net.wifi.WifiManager
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.feature.tools.domain.subnet.Ipv4Address
import de.nettoolbox.feature.tools.domain.subnet.Ipv4Subnet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.system.measureTimeMillis

data class IpScanOptions(
    val probePorts: List<Int> = DEFAULT_PROBE_PORTS,
    val timeoutMillis: Int = 600,
    val parallelism: Int = 64,
    val useSsdp: Boolean = true,
    val useMdns: Boolean = true,
    val useNetBios: Boolean = true,
    val useReverseDns: Boolean = true,
) {
    companion object {
        /**
         * A handful of ports that almost anything with an address answers on.
         * Kept short on purpose: this is a liveness probe, not a port scan - the
         * port scanner exists for that.
         */
        val DEFAULT_PROBE_PORTS = listOf(22, 80, 443, 445, 8080, 9100)
    }
}

sealed interface IpScanEvent {
    data class Progress(val completed: Int, val total: Int) : IpScanEvent
    data class HostFound(val host: DiscoveredHost) : IpScanEvent
    data class Failed(val error: NetToolboxError) : IpScanEvent
    data class Completed(val hosts: List<DiscoveredHost>) : IpScanEvent
}

/**
 * Finds hosts on the local subnet.
 *
 * There is deliberately no ARP sweep: `/proc/net/arp` is blocked by SELinux from
 * Android 10 on, so the app cannot see the neighbour table and cannot learn MAC
 * addresses. The four methods used here compensate - a TCP probe finds anything
 * with an open port, SSDP finds media and NAS devices, NetBIOS names Windows
 * machines, mDNS names Apple, Linux and IoT devices.
 */
class IpScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
    private val ssdpDiscovery: SsdpDiscovery,
    private val netBiosDiscovery: NetBiosDiscovery,
    private val mdnsDiscovery: MdnsDiscovery,
) {

    fun scan(subnet: Ipv4Subnet, options: IpScanOptions = IpScanOptions()): Flow<IpScanEvent> =
        channelFlow {
            val addresses = subnet.hostAddresses()
            if (addresses.isEmpty()) {
                send(
                    IpScanEvent.Failed(
                        NetToolboxError(ErrorReason.INVALID_INPUT, detail = subnet.toString()),
                    ),
                )
                return@channelFlow
            }

            // Without a multicast lock the Wi-Fi driver filters multicast packets
            // before they reach the app, and both mDNS and SSDP silently find
            // nothing at all.
            val multicastLock = context.getSystemService<WifiManager>()
                ?.createMulticastLock(MULTICAST_LOCK_TAG)
                ?.apply {
                    setReferenceCounted(true)
                    runCatching { acquire() }
                }

            val hosts = ConcurrentHashMap<String, DiscoveredHost>()
            val completed = AtomicInteger(0)

            suspend fun record(host: DiscoveredHost) {
                val merged = hosts.compute(host.ip) { _, existing ->
                    existing?.mergeWith(host) ?: host
                } ?: host
                send(IpScanEvent.HostFound(merged))
            }

            try {
                coroutineScope {
                    // The name-based methods run alongside the sweep instead of
                    // after it: they are one-shot broadcasts whose answers arrive
                    // while the TCP probes are still working through the subnet.
                    val sideChannels = listOf(
                        async { if (options.useSsdp) ssdpDiscovery.discover() else emptyList() },
                        async { if (options.useMdns) mdnsDiscovery.discover() else emptyList() },
                        async {
                            if (options.useNetBios) {
                                netBiosDiscovery.discover(addresses)
                            } else {
                                emptyList()
                            }
                        },
                    )

                    val permits = Semaphore(options.parallelism.coerceIn(1, MAX_PARALLELISM))
                    addresses.forEach { address ->
                        launch {
                            permits.withPermit {
                                probe(address, options)?.let { record(it) }
                                send(
                                    IpScanEvent.Progress(
                                        completed = completed.incrementAndGet(),
                                        total = addresses.size,
                                    ),
                                )
                            }
                        }
                    }

                    sideChannels.forEach { deferred ->
                        deferred.await().forEach { record(it) }
                    }
                }

                if (options.useReverseDns) {
                    resolveNames(hosts) { record(it) }
                }
            } finally {
                multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
            }

            send(IpScanEvent.Completed(hosts.values.sortedBy { it.ip.toSortKey() }))
        }.flowOn(dispatcher)

    private fun probe(address: String, options: IpScanOptions): DiscoveredHost? {
        val openPorts = mutableListOf<Int>()
        var fastest = Long.MAX_VALUE

        options.probePorts.forEach { port ->
            var reachable = false
            val elapsed = measureTimeMillis {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(address, port), options.timeoutMillis)
                        reachable = true
                    }
                } catch (io: IOException) {
                    reachable = false
                }
            }
            if (reachable) {
                openPorts += port
                if (elapsed < fastest) fastest = elapsed
            }
        }

        return if (openPorts.isEmpty()) {
            null
        } else {
            DiscoveredHost(
                ip = address,
                openPorts = openPorts,
                rttMillis = fastest.takeIf { it != Long.MAX_VALUE },
                sources = setOf(DiscoverySource.TCP_PROBE),
            )
        }
    }

    /** Fills in names for hosts that no discovery protocol named. */
    private suspend fun resolveNames(
        hosts: ConcurrentHashMap<String, DiscoveredHost>,
        record: suspend (DiscoveredHost) -> Unit,
    ) {
        hosts.values.filter { it.hostname == null }.forEach { host ->
            val name = runCatching {
                InetAddress.getByName(host.ip).canonicalHostName
            }.getOrNull()

            // getCanonicalHostName returns the address itself when there is no
            // PTR record, which is not a name.
            if (name != null && name != host.ip) {
                record(host.copy(hostname = name, sources = setOf(DiscoverySource.REVERSE_DNS)))
            }
        }
    }

    private companion object {
        const val MAX_PARALLELISM = 256
        const val MULTICAST_LOCK_TAG = "nettoolbox-discovery"
    }
}

/** Host addresses of the subnet, without the network and broadcast address. */
private fun Ipv4Subnet.hostAddresses(): List<String> {
    if (totalAddresses > MAX_SWEEP_ADDRESSES) return emptyList()
    val first = firstUsableHost.value
    val last = lastUsableHost.value
    if (last < first) return emptyList()
    return (first..last).map { Ipv4Address(it).toString() }
}

/** Sorts 192.168.1.9 before 192.168.1.10 instead of lexically. */
private fun String.toSortKey(): Long =
    Ipv4Address.parseOrNull(this)?.value?.toLong() ?: Long.MAX_VALUE

private const val MAX_SWEEP_ADDRESSES = 4096L
