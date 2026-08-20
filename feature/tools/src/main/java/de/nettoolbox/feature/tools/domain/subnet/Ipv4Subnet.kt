package de.nettoolbox.feature.tools.domain.subnet

/**
 * An IPv4 address as an unsigned 32-bit value.
 *
 * Deliberately not `java.net.InetAddress`: that class resolves host names, can
 * touch the network, and hides the integer arithmetic every subnet operation
 * needs. A value class keeps the calculator pure and instant.
 */
@JvmInline
value class Ipv4Address(val value: UInt) : Comparable<Ipv4Address> {

    override fun compareTo(other: Ipv4Address): Int = value.compareTo(other.value)

    override fun toString(): String = (3 downTo 0).joinToString(".") { octet ->
        (((value shr (octet * 8)) and 0xFFu)).toString()
    }

    /** Dotted binary, as network engineers write it on a whiteboard. */
    fun toBinaryString(): String = (3 downTo 0).joinToString(".") { octet ->
        ((value shr (octet * 8)) and 0xFFu).toString(2).padStart(8, '0')
    }

    companion object {
        fun parseOrNull(text: String): Ipv4Address? {
            val parts = text.trim().split('.')
            if (parts.size != 4) return null

            var result = 0u
            for (part in parts) {
                // Reject "01" and "1 " style input: a leading zero means octal in
                // some resolvers, and silently accepting it invites confusion.
                if (part.isEmpty() || part.length > 3) return null
                if (part.length > 1 && part[0] == '0') return null
                if (!part.all { it.isDigit() }) return null

                val octet = part.toInt()
                if (octet > 255) return null
                result = (result shl 8) or octet.toUInt()
            }
            return Ipv4Address(result)
        }

        fun parse(text: String): Ipv4Address =
            parseOrNull(text) ?: throw IllegalArgumentException("Not an IPv4 address: $text")

        /** Netmask for a prefix length, e.g. 24 -> 255.255.255.0. */
        fun netmaskOf(prefixLength: Int): Ipv4Address {
            require(prefixLength in 0..32) { "Prefix length out of range: $prefixLength" }
            // Shifting a 32-bit value by 32 is undefined, so /0 is handled directly.
            val mask = if (prefixLength == 0) 0u else UInt.MAX_VALUE shl (32 - prefixLength)
            return Ipv4Address(mask)
        }

        /**
         * Prefix length of a contiguous netmask, or null if the mask has holes
         * (255.255.0.255 and friends).
         */
        fun prefixLengthOf(netmask: Ipv4Address): Int? {
            val value = netmask.value
            val ones = value.countOneBits()
            val expected = if (ones == 0) 0u else UInt.MAX_VALUE shl (32 - ones)
            return if (value == expected) ones else null
        }
    }
}

/**
 * An IPv4 network.
 *
 * [address] is kept as entered, so the UI can show both "the address you typed"
 * and "the network it belongs to" - the distinction a technician is usually
 * checking for in the first place.
 */
data class Ipv4Subnet(
    val address: Ipv4Address,
    val prefixLength: Int,
) {
    init {
        require(prefixLength in 0..32) { "Prefix length out of range: $prefixLength" }
    }

    val netmask: Ipv4Address get() = Ipv4Address.netmaskOf(prefixLength)

    val wildcard: Ipv4Address get() = Ipv4Address(netmask.value.inv())

    val network: Ipv4Address get() = Ipv4Address(address.value and netmask.value)

    val broadcast: Ipv4Address get() = Ipv4Address(network.value or wildcard.value)

    val totalAddresses: Long get() = 1L shl (32 - prefixLength)

    /** A /32 is one host, a /31 is a point-to-point link with two (RFC 3021). */
    val isSingleHost: Boolean get() = prefixLength == 32

    val isPointToPoint: Boolean get() = prefixLength == 31

    val usableHosts: Long
        get() = when {
            isSingleHost -> 1L
            isPointToPoint -> 2L
            else -> totalAddresses - 2L
        }

    val firstUsableHost: Ipv4Address
        get() = when {
            isSingleHost || isPointToPoint -> network
            else -> Ipv4Address(network.value + 1u)
        }

    val lastUsableHost: Ipv4Address
        get() = when {
            isSingleHost -> network
            isPointToPoint -> broadcast
            else -> Ipv4Address(broadcast.value - 1u)
        }

    operator fun contains(other: Ipv4Address): Boolean =
        (other.value and netmask.value) == network.value

    operator fun contains(other: Ipv4Subnet): Boolean =
        other.prefixLength >= prefixLength && other.network in this

    /**
     * Splits this network into equally sized subnets with [newPrefixLength].
     *
     * @throws IllegalArgumentException if the split would produce more than
     *   [MAX_SPLIT_RESULTS] subnets - materialising a /8 into /30s is 4 million
     *   objects and would take the app down rather than answer the question.
     */
    fun split(newPrefixLength: Int): List<Ipv4Subnet> {
        require(newPrefixLength in prefixLength..32) {
            "Cannot split /$prefixLength into /$newPrefixLength"
        }
        val count = 1L shl (newPrefixLength - prefixLength)
        require(count <= MAX_SPLIT_RESULTS) {
            "Split would produce $count subnets, limit is $MAX_SPLIT_RESULTS"
        }

        val step = 1L shl (32 - newPrefixLength)
        val start = network.value.toLong()
        return (0 until count).map { index ->
            Ipv4Subnet(Ipv4Address((start + index * step).toUInt()), newPrefixLength)
        }
    }

    /**
     * The network one prefix bit shorter, if this subnet and [other] are the two
     * halves of it. Returns null when they are not adjacent or not aligned.
     */
    fun supernetWith(other: Ipv4Subnet): Ipv4Subnet? {
        if (prefixLength != other.prefixLength || prefixLength == 0) return null

        val supernet = Ipv4Subnet(network, prefixLength - 1)
        val halves = supernet.split(prefixLength)
        return if (halves.map { it.network }.toSet() == setOf(network, other.network)) {
            supernet
        } else {
            null
        }
    }

    override fun toString(): String = "$network/$prefixLength"

    companion object {
        const val MAX_SPLIT_RESULTS = 4096L

        /**
         * Accepts "192.168.1.10/24" and "192.168.1.10 255.255.255.0".
         *
         * @throws IllegalArgumentException on anything else, including masks with
         *   non-contiguous bits.
         */
        fun parse(text: String): Ipv4Subnet {
            val trimmed = text.trim()
            val separator = trimmed.indexOfFirst { it == '/' || it.isWhitespace() }

            if (separator < 0) {
                // A bare address is a single host.
                return Ipv4Subnet(Ipv4Address.parse(trimmed), 32)
            }

            val addressPart = trimmed.substring(0, separator)
            val maskPart = trimmed.substring(separator + 1).trim()
            val address = Ipv4Address.parse(addressPart)

            val prefix = maskPart.toIntOrNull()
                ?: Ipv4Address.parseOrNull(maskPart)?.let { mask ->
                    Ipv4Address.prefixLengthOf(mask)
                        ?: throw IllegalArgumentException("Netmask is not contiguous: $maskPart")
                }
                ?: throw IllegalArgumentException("Not a prefix length or netmask: $maskPart")

            require(prefix in 0..32) { "Prefix length out of range: $prefix" }
            return Ipv4Subnet(address, prefix)
        }

        fun parseOrNull(text: String): Ipv4Subnet? = try {
            parse(text)
        } catch (invalid: IllegalArgumentException) {
            null
        }
    }
}
