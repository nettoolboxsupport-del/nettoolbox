package de.nettoolbox.feature.tools.domain.ping

/**
 * One meaningful line of `ping` output.
 */
sealed interface PingLine {

    data class Reply(
        val sequence: Int,
        val from: String,
        val ttl: Int?,
        val rttMillis: Double,
    ) : PingLine

    /** Router reporting an expired TTL - the basis of the traceroute fallback. */
    data class TtlExceeded(val from: String, val sequence: Int?) : PingLine

    data class Unreachable(val from: String, val sequence: Int?) : PingLine

    /** Banners, summaries, blank lines. */
    data object Ignored : PingLine
}

/**
 * Parser for the stdout of `/system/bin/ping`.
 *
 * Android ships toybox on most devices, busybox on some, and iputils on others,
 * and the three do not agree: busybox writes `seq=0` where iputils writes
 * `icmp_seq=1`, and a resolved host appears as `host (1.2.3.4)`. The regexes are
 * therefore deliberately loose about separators and strict only about the values
 * that are actually read.
 *
 * Pure and free of Android APIs so all three formats can be tested.
 */
object PingOutputParser {

    private val replyRegex = Regex(
        """bytes from ([^\s:(]+)(?:\s+\(([^)]+)\))?:?\s+(?:icmp_)?seq[=\s]\s*(\d+)\s+ttl[=\s]\s*(\d+)\s+time[=<]\s*([\d.]+)\s*ms""",
        RegexOption.IGNORE_CASE,
    )

    private val fromRegex = Regex("""from\s+([^\s:(,]+)""", RegexOption.IGNORE_CASE)

    private val sequenceRegex = Regex("""(?:icmp_)?seq[=\s]\s*(\d+)""", RegexOption.IGNORE_CASE)

    fun parseLine(line: String): PingLine {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return PingLine.Ignored

        replyRegex.find(trimmed)?.let { match ->
            // Group 2 is the address in parentheses behind a resolved name; when
            // present it is the more useful of the two.
            val host = match.groupValues[2].ifEmpty { match.groupValues[1] }
            return PingLine.Reply(
                sequence = match.groupValues[3].toInt(),
                from = host,
                ttl = match.groupValues[4].toIntOrNull(),
                rttMillis = match.groupValues[5].toDoubleOrNull() ?: return PingLine.Ignored,
            )
        }

        val lower = trimmed.lowercase()
        val from = fromRegex.find(trimmed)?.groupValues?.get(1)
        val sequence = sequenceRegex.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()

        return when {
            from == null -> PingLine.Ignored
            "time to live exceeded" in lower || "ttl expired" in lower ->
                PingLine.TtlExceeded(from, sequence)

            "unreachable" in lower -> PingLine.Unreachable(from, sequence)
            else -> PingLine.Ignored
        }
    }
}
