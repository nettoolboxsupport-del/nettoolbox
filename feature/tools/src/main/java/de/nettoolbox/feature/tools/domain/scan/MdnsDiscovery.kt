package de.nettoolbox.feature.tools.domain.scan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import de.nettoolbox.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Service discovery over mDNS.
 *
 * Queries a fixed list of service types instead of enumerating
 * `_services._dns-sd._udp` as the spec suggests: the meta-query is answered
 * inconsistently by Android's own resolver, and a browse that returns nothing is
 * indistinguishable from a network with no services. The fixed list covers what
 * actually turns up on a site visit and gives a deterministic result.
 *
 * Resolves are serialised through a mutex because NsdManager handles exactly one
 * outstanding resolve reliably.
 */
class MdnsDiscovery @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    private val resolveMutex = Mutex()

    suspend fun discover(
        browseMillis: Long = DEFAULT_BROWSE_MILLIS,
    ): List<DiscoveredHost> = withContext(dispatcher) {
        val manager = context.getSystemService<NsdManager>() ?: return@withContext emptyList()
        val found = mutableMapOf<String, DiscoveredHost>()

        SERVICE_TYPES.forEach { serviceType ->
            val services = browse(manager, serviceType, browseMillis)
            services.forEach { service ->
                val resolved = resolve(manager, service) ?: return@forEach
                val ip = resolved.host?.hostAddress ?: return@forEach

                val host = DiscoveredHost(
                    ip = ip,
                    hostname = resolved.serviceName,
                    openPorts = listOf(resolved.port),
                    services = listOf("${resolved.serviceName} (${serviceType.trim('.')})"),
                    sources = setOf(DiscoverySource.MDNS),
                )
                found[ip] = found[ip]?.mergeWith(host) ?: host
            }
        }

        found.values.toList()
    }

    private suspend fun browse(
        manager: NsdManager,
        serviceType: String,
        browseMillis: Long,
    ): List<NsdServiceInfo> {
        val services = mutableListOf<NsdServiceInfo>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String?, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(type: String?, errorCode: Int) = Unit
            override fun onDiscoveryStarted(type: String?) = Unit
            override fun onDiscoveryStopped(type: String?) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                synchronized(services) { services += service }
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit
        }

        return try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            delay(browseMillis)
            synchronized(services) { services.toList() }
        } catch (illegal: IllegalArgumentException) {
            emptyList()
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(
        manager: NsdManager,
        service: NsdServiceInfo,
    ): NsdServiceInfo? = resolveMutex.withLock {
        withTimeoutOrNull(RESOLVE_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                // resolveService is deprecated from API 34 in favour of
                // registerServiceInfoCallback, but that callback is not available
                // on the minSdk this app supports. Replace once minSdk reaches 34.
                manager.resolveService(
                    service,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            if (continuation.isActive) continuation.resume(info)
                        }
                    },
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_BROWSE_MILLIS = 2_500L
        const val RESOLVE_TIMEOUT_MILLIS = 3_000L

        /** What a technician actually meets on a customer network. */
        val SERVICE_TYPES = listOf(
            "_http._tcp.",
            "_https._tcp.",
            "_ssh._tcp.",
            "_sftp-ssh._tcp.",
            "_smb._tcp.",
            "_afpovertcp._tcp.",
            "_workstation._tcp.",
            "_printer._tcp.",
            "_ipp._tcp.",
            "_pdl-datastream._tcp.",
            "_googlecast._tcp.",
            "_airplay._tcp.",
            "_raop._tcp.",
            "_homekit._tcp.",
        )
    }
}
