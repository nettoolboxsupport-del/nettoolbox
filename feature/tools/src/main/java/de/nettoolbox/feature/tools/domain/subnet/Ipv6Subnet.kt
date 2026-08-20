package de.nettoolbox.feature.tools.domain.subnet

import java.math.BigInteger
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * An IPv6 network.
 *
 * Address parsing is delegated to [InetAddress] rather than hand-written: `::`
 * compression, embedded IPv4 and zone identifiers are a lot of edge cases to get
 * wrong. The input is checked to be a numeric literal first, so the call can
 * never trigger a DNS lookup.
 */
data class Ipv6Subnet(
    val address: BigInteger,
    val prefixLength: Int,
) {
    init {
        require(prefixLength in 0..128) { "Prefix length out of range: $prefixLength" }
        require(address.signum() >= 0 && address.bitLength() <= 128) {
            "Address outside the IPv6 range"
        }
    }

    val netmask: BigInteger
        get() = if (prefixLength == 0) {
            BigInteger.ZERO
        } else {
            FULL_MASK.shiftLeft(128 - prefixLength).and(FULL_MASK)
        }

    val network: BigInteger get() = address.and(netmask)

    val lastAddress: BigInteger get() = network.or(netmask.xor(FULL_MASK))

    /** 2^(128 - prefix). Exceeds Long for anything shorter than /64. */
    val totalAddresses: BigInteger get() = TWO.pow(128 - prefixLength)

    operator fun contains(other: BigInteger): Boolean = other.and(netmask) == network

    fun formatNetwork(): String = format(network)

    fun formatLastAddress(): String = format(lastAddress)

    override fun toString(): String = "${formatNetwork()}/$prefixLength"

    companion object {
        // BigInteger.TWO only exists from Java 9 on and is not safe to rely on
        // across the supported Android versions.
        private val TWO: BigInteger = BigInteger.valueOf(2)

        private val FULL_MASK: BigInteger = TWO.pow(128).subtract(BigInteger.ONE)

        /**
         * True when [text] can only be a numeric IPv6 literal. Guards the
         * [InetAddress] call so a typo can never become a name resolution.
         */
        private fun isNumericLiteral(text: String): Boolean =
            text.contains(':') && text.all { it.isDigit() || it in "abcdefABCDEF:." }

        fun parse(text: String): Ipv6Subnet {
            val trimmed = text.trim()
            val slash = trimmed.indexOf('/')
            val addressPart = if (slash < 0) trimmed else trimmed.substring(0, slash)
            val prefix = if (slash < 0) {
                128
            } else {
                trimmed.substring(slash + 1).trim().toIntOrNull()
                    ?: throw IllegalArgumentException("Not a prefix length: ${trimmed.substring(slash + 1)}")
            }

            require(isNumericLiteral(addressPart)) { "Not an IPv6 address: $addressPart" }

            val parsed = try {
                InetAddress.getByName(addressPart)
            } catch (unknown: UnknownHostException) {
                throw IllegalArgumentException("Not an IPv6 address: $addressPart", unknown)
            }
            require(parsed is Inet6Address) { "Not an IPv6 address: $addressPart" }

            // Positive signum so a leading 0xff byte is not read as negative.
            return Ipv6Subnet(BigInteger(1, parsed.address), prefix)
        }

        fun parseOrNull(text: String): Ipv6Subnet? = try {
            parse(text)
        } catch (invalid: IllegalArgumentException) {
            null
        }

        /**
         * Canonical form per RFC 5952: lower case, leading zeros dropped, the
         * longest run of zero groups compressed once.
         */
        fun format(value: BigInteger): String {
            val groups = IntArray(8)
            var remaining = value
            for (index in 7 downTo 0) {
                groups[index] = remaining.and(BigInteger.valueOf(0xFFFF)).toInt()
                remaining = remaining.shiftRight(16)
            }

            var bestStart = -1
            var bestLength = 0
            var currentStart = -1
            var currentLength = 0
            for (index in 0..7) {
                if (groups[index] == 0) {
                    if (currentStart < 0) currentStart = index
                    currentLength++
                    if (currentLength > bestLength) {
                        bestStart = currentStart
                        bestLength = currentLength
                    }
                } else {
                    currentStart = -1
                    currentLength = 0
                }
            }
            // A single zero group is written out; compressing it saves nothing.
            if (bestLength < 2) {
                return groups.joinToString(":") { it.toString(16) }
            }

            val head = (0 until bestStart).joinToString(":") { groups[it].toString(16) }
            val tail = ((bestStart + bestLength)..7).joinToString(":") { groups[it].toString(16) }
            return "$head::$tail"
        }
    }
}
