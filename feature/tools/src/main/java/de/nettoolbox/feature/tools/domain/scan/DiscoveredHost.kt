package de.nettoolbox.feature.tools.domain.scan

/** How a host made itself known. Several can apply to the same host. */
enum class DiscoverySource {
    TCP_PROBE,
    MDNS,
    SSDP,
    NETBIOS,
    REVERSE_DNS,
}

data class DiscoveredHost(
    val ip: String,
    val hostname: String? = null,
    val openPorts: List<Int> = emptyList(),
    val services: List<String> = emptyList(),
    val rttMillis: Long? = null,
    val sources: Set<DiscoverySource> = emptySet(),
) {
    /**
     * Merges what two discovery methods found about the same address.
     *
     * Merging rather than replacing matters: mDNS knows a friendly name but no
     * ports, the TCP probe knows ports but no name, and NetBIOS knows the Windows
     * name. Any one of them alone gives half a host.
     */
    fun mergeWith(other: DiscoveredHost): DiscoveredHost {
        require(ip == other.ip) { "Cannot merge $ip with ${other.ip}" }
        return DiscoveredHost(
            ip = ip,
            hostname = hostname ?: other.hostname,
            openPorts = (openPorts + other.openPorts).distinct().sorted(),
            services = (services + other.services).distinct(),
            rttMillis = rttMillis ?: other.rttMillis,
            sources = sources + other.sources,
        )
    }
}
