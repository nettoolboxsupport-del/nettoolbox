package de.nettoolbox.feature.cellular.domain.export

import de.nettoolbox.core.database.entity.CellSampleEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

enum class ExportFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"),
    GEOJSON("geojson", "application/geo+json"),
    KML("kml", "application/vnd.google-earth.kml+xml"),
}

/**
 * Turns recorded samples into the three formats a drive test is handed over in.
 *
 * Pure and unit-tested: an export is the one artefact that leaves the app and
 * gets opened by someone else's tool, so a stray separator or a comma decimal
 * point breaks the deliverable rather than the app.
 */
object MeasurementExporter {

    fun export(samples: List<CellSampleEntity>, format: ExportFormat): String = when (format) {
        ExportFormat.CSV -> toCsv(samples)
        ExportFormat.GEOJSON -> toGeoJson(samples)
        ExportFormat.KML -> toKml(samples)
    }

    // ---- CSV ----------------------------------------------------------------

    private val CSV_COLUMNS = listOf(
        "timestamp", "lat", "lon", "accuracy_m", "speed_mps",
        "sub_id", "rat", "mcc", "mnc", "operator",
        "cell_id", "pci", "tac", "arfcn", "band", "bandwidth_khz",
        "rsrp", "rsrq", "sinr", "rssi", "cqi", "timing_advance",
        "is_serving", "is_roaming",
    )

    fun toCsv(samples: List<CellSampleEntity>): String = buildString {
        appendLine(CSV_COLUMNS.joinToString(","))
        samples.forEach { sample ->
            appendLine(
                listOf(
                    sample.ts.toString(),
                    sample.lat.csv(),
                    sample.lon.csv(),
                    sample.accuracy.csv(),
                    sample.speed.csv(),
                    sample.subId.toString(),
                    sample.rat.name,
                    sample.mcc.csv(),
                    sample.mnc.csv(),
                    sample.operatorName.csvQuoted(),
                    sample.cid.csv(),
                    sample.pci.csv(),
                    sample.tac.csv(),
                    sample.arfcn.csv(),
                    sample.band.csv(),
                    sample.bandwidthKhz.csv(),
                    sample.rsrp.csv(),
                    sample.rsrq.csv(),
                    sample.sinr.csv(),
                    sample.rssi.csv(),
                    sample.cqi.csv(),
                    sample.timingAdvance.csv(),
                    sample.isServing.toString(),
                    sample.isRoaming.toString(),
                ).joinToString(","),
            )
        }
    }

    /** Empty rather than "null": a spreadsheet reads an empty cell as missing. */
    private fun Number?.csv(): String = when (this) {
        null -> ""
        // Locale.ROOT throughout: a German decimal comma would split the column.
        is Double -> String.format(Locale.ROOT, "%.6f", this)
        is Float -> String.format(Locale.ROOT, "%.2f", this)
        else -> toString()
    }

    private fun String?.csvQuoted(): String {
        if (this == null) return ""
        val escaped = replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' }) "\"$escaped\"" else escaped
    }

    // ---- GeoJSON ------------------------------------------------------------

    /**
     * Only samples with a position become features - a GeoJSON point without
     * coordinates is invalid, and silently writing 0/0 would drop every such
     * sample into the Gulf of Guinea.
     */
    fun toGeoJson(samples: List<CellSampleEntity>): String {
        val features = buildJsonArray {
            samples.forEach { sample ->
                val lat = sample.lat
                val lon = sample.lon
                if (lat == null || lon == null) return@forEach

                add(
                    buildJsonObject {
                        put("type", "Feature")
                        put(
                            "geometry",
                            buildJsonObject {
                                put("type", "Point")
                                put(
                                    "coordinates",
                                    // GeoJSON is longitude first.
                                    JsonArray(listOf(JsonPrimitive(lon), JsonPrimitive(lat))),
                                )
                            },
                        )
                        put("properties", sample.toProperties())
                    },
                )
            }
        }

        val root = buildJsonObject {
            put("type", "FeatureCollection")
            put("features", features)
        }
        return Json { prettyPrint = true }.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            root,
        )
    }

    private fun CellSampleEntity.toProperties() = buildJsonObject {
        put("timestamp", ts)
        put("rat", rat.name)
        // Missing values are omitted rather than written as null: a consumer that
        // sees no key knows the modem did not report it.
        mcc?.let { put("mcc", it) }
        mnc?.let { put("mnc", it) }
        operatorName?.let { put("operator", it) }
        cid?.let { put("cell_id", it) }
        pci?.let { put("pci", it) }
        tac?.let { put("tac", it) }
        arfcn?.let { put("arfcn", it) }
        band?.let { put("band", it) }
        rsrp?.let { put("rsrp", it) }
        rsrq?.let { put("rsrq", it) }
        sinr?.let { put("sinr", it) }
        rssi?.let { put("rssi", it) }
        timingAdvance?.let { put("timing_advance", it) }
        put("is_roaming", isRoaming)
    }

    // ---- KML ----------------------------------------------------------------

    fun toKml(samples: List<CellSampleEntity>, documentName: String = "NetToolbox drive test"): String =
        buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
            appendLine("  <Document>")
            appendLine("    <name>${documentName.xml()}</name>")

            KML_STYLES.forEach { (id, colour) ->
                appendLine("    <Style id=\"$id\">")
                appendLine("      <IconStyle><color>$colour</color><scale>0.8</scale></IconStyle>")
                appendLine("    </Style>")
            }

            samples.forEach { sample ->
                val lat = sample.lat
                val lon = sample.lon
                if (lat == null || lon == null) return@forEach

                appendLine("    <Placemark>")
                appendLine("      <name>${(sample.cid?.toString() ?: sample.rat.name).xml()}</name>")
                appendLine("      <styleUrl>#${styleFor(sample.rsrp)}</styleUrl>")
                appendLine("      <description>${sample.describe().xml()}</description>")
                appendLine(
                    "      <Point><coordinates>" +
                        String.format(Locale.ROOT, "%.6f,%.6f,0", lon, lat) +
                        "</coordinates></Point>",
                )
                appendLine("    </Placemark>")
            }

            appendLine("  </Document>")
            appendLine("</kml>")
        }

    private fun CellSampleEntity.describe(): String = buildString {
        append("RAT: ${rat.name}")
        rsrp?.let { append(" | RSRP: $it dBm") }
        rsrq?.let { append(" | RSRQ: $it dB") }
        sinr?.let { append(" | SINR: $it dB") }
        band?.let { append(" | Band: $it") }
        pci?.let { append(" | PCI: $it") }
    }

    /** KML colours are aabbggrr, not rrggbb. */
    private val KML_STYLES = listOf(
        "q-excellent" to "ff3bb26f",
        "q-good" to "ff16d9af",
        "q-fair" to "ff00c9ff",
        "q-poor" to "ff1a6bff",
        "q-bad" to "ff2020ba",
        "q-unknown" to "ff8a8a8a",
    )

    private fun styleFor(rsrp: Int?): String = when {
        rsrp == null -> "q-unknown"
        rsrp >= -80 -> "q-excellent"
        rsrp >= -90 -> "q-good"
        rsrp >= -100 -> "q-fair"
        rsrp >= -110 -> "q-poor"
        else -> "q-bad"
    }

    private fun String.xml(): String = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
