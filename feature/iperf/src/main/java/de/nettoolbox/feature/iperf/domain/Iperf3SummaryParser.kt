package de.nettoolbox.feature.iperf.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the headline throughput numbers out of iperf3's JSON output.
 *
 * Shared by the client and the server runner - both get the same JSON shape
 * back from libiperf, so the parsing lives in one place rather than in each.
 *
 * Best-effort by design: a field that is not where expected becomes null
 * rather than an exception, and the raw JSON is always kept alongside the
 * parsed summary, so nothing is lost if a field name has moved between
 * iperf3 versions.
 */
@Singleton
class Iperf3SummaryParser @Inject constructor(
    private val json: Json,
) {

    fun parse(rawJson: String): Iperf3Summary {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
            ?: return EMPTY

        val end = root["end"]?.jsonObjectOrNull() ?: return EMPTY
        val sumSent = end["sum_sent"]?.jsonObjectOrNull()
        val sumReceived = end["sum_received"]?.jsonObjectOrNull()
        // UDP reports a single "sum" instead of separate sent/received in some
        // run modes; fall back to it for both directions.
        val sum = end["sum"]?.jsonObjectOrNull()

        return Iperf3Summary(
            sentBitsPerSecond = (sumSent ?: sum)?.doubleField("bits_per_second"),
            receivedBitsPerSecond = (sumReceived ?: sum)?.doubleField("bits_per_second"),
            retransmits = sumSent?.longField("retransmits"),
        )
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun JsonObject.doubleField(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.longField(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull

    private companion object {
        val EMPTY = Iperf3Summary(null, null, null)
    }
}
