package de.nettoolbox.feature.tools.domain.scan

/**
 * Parsing and presets for port selections.
 *
 * Pure, because a mis-parsed range is the difference between scanning eight ports
 * and scanning eight thousand - on someone else's network.
 */
object PortSpec {

    const val MIN_PORT = 1
    const val MAX_PORT = 65535

    /** Guards against a typo turning into a full 65k sweep by accident. */
    const val MAX_PORTS_PER_SCAN = 8192

    /**
     * Accepts comma-separated ports and ranges, e.g. `22,80,443,8000-8100`.
     * Whitespace is ignored, duplicates collapse, the result is sorted.
     *
     * @throws IllegalArgumentException on malformed input, out-of-range ports,
     *   reversed ranges or a selection larger than [MAX_PORTS_PER_SCAN]
     */
    fun parse(spec: String): List<Int> {
        val trimmed = spec.trim()
        require(trimmed.isNotEmpty()) { "No ports given" }

        val ports = sortedSetOf<Int>()
        trimmed.split(',').forEach { rawToken ->
            val token = rawToken.trim()
            require(token.isNotEmpty()) { "Empty entry in the port list" }

            val dash = token.indexOf('-')
            if (dash < 0) {
                ports += token.toPortOrThrow()
            } else {
                val from = token.substring(0, dash).trim().toPortOrThrow()
                val to = token.substring(dash + 1).trim().toPortOrThrow()
                require(from <= to) { "Range runs backwards: $token" }
                require(to - from + 1 <= MAX_PORTS_PER_SCAN) { "Range too large: $token" }
                (from..to).forEach { ports += it }
            }

            require(ports.size <= MAX_PORTS_PER_SCAN) {
                "More than $MAX_PORTS_PER_SCAN ports selected"
            }
        }
        return ports.toList()
    }

    fun parseOrNull(spec: String): List<Int>? = try {
        parse(spec)
    } catch (invalid: IllegalArgumentException) {
        null
    }

    private fun String.toPortOrThrow(): Int {
        val port = toIntOrNull() ?: throw IllegalArgumentException("Not a port number: $this")
        require(port in MIN_PORT..MAX_PORT) { "Port out of range: $port" }
        return port
    }

    /**
     * A curated set of ports worth probing on a site visit: infrastructure
     * management, printing, storage, monitoring and the usual application ports.
     * Shorter and more relevant to a network engineer than a generic top-1000.
     */
    val COMMON_PORTS: List<Int> = listOf(
        21, 22, 23, 25, 53, 67, 69, 80, 88, 110, 111, 123, 135, 137, 138, 139,
        143, 161, 162, 389, 443, 445, 465, 500, 514, 515, 520, 587, 623, 636,
        873, 902, 993, 995, 1080, 1194, 1433, 1521, 1723, 1883, 2049, 2082,
        2083, 3000, 3128, 3260, 3306, 3389, 4500, 5000, 5060, 5061, 5201, 5222,
        5353, 5432, 5601, 5672, 5900, 5985, 5986, 6379, 8000, 8006, 8008, 8080,
        8081, 8123, 8443, 8006, 8888, 9000, 9090, 9100, 9200, 9443, 10000,
        11211, 27017, 32400, 47808,
    ).distinct().sorted()

    /** Ports a technician checks first when standing in front of a rack. */
    val QUICK_PORTS: List<Int> = listOf(22, 23, 80, 161, 443, 445, 3389, 8080, 8443, 9100)

    private val SERVICE_NAMES: Map<Int, String> = mapOf(
        21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp", 53 to "dns",
        67 to "dhcp", 69 to "tftp", 80 to "http", 88 to "kerberos", 110 to "pop3",
        111 to "rpcbind", 123 to "ntp", 135 to "msrpc", 137 to "netbios-ns",
        139 to "netbios-ssn", 143 to "imap", 161 to "snmp", 162 to "snmp-trap",
        389 to "ldap", 443 to "https", 445 to "smb", 465 to "smtps", 500 to "isakmp",
        514 to "syslog", 515 to "printer", 587 to "submission", 623 to "ipmi",
        636 to "ldaps", 873 to "rsync", 902 to "vmware", 993 to "imaps",
        995 to "pop3s", 1194 to "openvpn", 1433 to "mssql", 1521 to "oracle",
        1723 to "pptp", 1883 to "mqtt", 2049 to "nfs", 3128 to "squid",
        3260 to "iscsi", 3306 to "mysql", 3389 to "rdp", 5060 to "sip",
        5201 to "iperf3", 5353 to "mdns", 5432 to "postgres", 5672 to "amqp",
        5900 to "vnc", 5985 to "winrm", 6379 to "redis", 8006 to "proxmox",
        8080 to "http-alt", 8443 to "https-alt", 9090 to "prometheus",
        9100 to "jetdirect", 9200 to "elasticsearch", 11211 to "memcached",
        27017 to "mongodb", 32400 to "plex", 47808 to "bacnet",
    )

    fun serviceName(port: Int): String? = SERVICE_NAMES[port]
}
