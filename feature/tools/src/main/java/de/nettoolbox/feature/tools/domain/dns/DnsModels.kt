package de.nettoolbox.feature.tools.domain.dns

import kotlinx.serialization.Serializable

/**
 * The record types the tool can ask for, with their wire values from the IANA
 * registry.
 */
@Serializable
enum class DnsRecordType(val code: Int) {
    A(1),
    NS(2),
    CNAME(5),
    SOA(6),
    PTR(12),
    MX(15),
    TXT(16),
    AAAA(28),
    SRV(33),
    CAA(257),
    ;

    companion object {
        fun fromCode(code: Int): DnsRecordType? = entries.firstOrNull { it.code == code }
    }
}

@Serializable
enum class DnsResponseCode(val code: Int) {
    NOERROR(0),
    FORMERR(1),
    SERVFAIL(2),
    NXDOMAIN(3),
    NOTIMP(4),
    REFUSED(5),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromCode(code: Int): DnsResponseCode =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

@Serializable
data class DnsQuestion(
    val name: String,
    val type: DnsRecordType,
)

/**
 * One resource record.
 *
 * [data] is the decoded, human-readable form; [rawData] keeps the original bytes
 * as hex so the raw view can show what actually came back when the decoder does
 * not understand a type.
 */
@Serializable
data class DnsRecord(
    val name: String,
    val type: DnsRecordType?,
    val typeCode: Int,
    val ttlSeconds: Long,
    val data: String,
    val rawData: String,
)

@Serializable
data class DnsResponse(
    val id: Int,
    val responseCode: DnsResponseCode,
    val authoritative: Boolean,
    val truncated: Boolean,
    val recursionAvailable: Boolean,
    /** DNSSEC: the resolver says it validated the answer. */
    val authenticatedData: Boolean,
    val question: DnsQuestion?,
    val answers: List<DnsRecord>,
    val authority: List<DnsRecord>,
    val additional: List<DnsRecord>,
)

/** Where a query was sent and how it got there. */
@Serializable
data class DnsResolverTarget(
    val label: String,
    val kind: DnsTransportKind,
    /** IP or host for UDP/TCP/DoT, full URL for DoH. */
    val address: String,
    val port: Int = defaultPortFor(kind),
) {
    companion object {
        fun defaultPortFor(kind: DnsTransportKind): Int = when (kind) {
            DnsTransportKind.UDP, DnsTransportKind.TCP -> 53
            DnsTransportKind.DOT -> 853
            DnsTransportKind.DOH -> 443
        }
    }
}

@Serializable
enum class DnsTransportKind {
    UDP,
    TCP,
    DOT,
    DOH,
}

/** One resolver's answer, with the time it took. */
data class DnsLookupResult(
    val target: DnsResolverTarget,
    val response: DnsResponse?,
    val elapsedMillis: Long,
    val error: de.nettoolbox.core.common.result.NetToolboxError? = null,
)
