package de.nettoolbox.feature.tools.domain.http

/**
 * Turns what a user types into something that can be requested.
 *
 * Pure and separately tested, because getting this wrong is silent: prefixing
 * `http://` instead of `https://` would make the tool report a plaintext
 * connection for a site that is perfectly fine over TLS.
 */
object HttpUrlNormalizer {

    /**
     * Adds a scheme when none is given, defaulting to HTTPS. Returns null when
     * the input cannot be a URL at all.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isWhitespace() }) return null

        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            // A scheme we do not speak is an error, not something to prefix.
            trimmed.contains("://") -> return null
            else -> "https://$trimmed"
        }

        val host = withScheme.substringAfter("://").substringBefore('/').substringBefore('?')
        if (host.isEmpty()) return null
        // Reject a bare port or an empty host such as "https://:8080".
        if (host.startsWith(":")) return null

        return withScheme
    }
}
