package de.nettoolbox.feature.map.domain

import de.nettoolbox.core.database.entity.CellSampleEntity
import de.nettoolbox.core.database.entity.KnownCellEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the GeoJSON the map renders.
 *
 * A source rather than individual markers, as the spec requires: MapLibre draws a
 * GeoJSON layer on the GPU, while ten thousand marker views would not survive the
 * first pan. Everything here is pure and tested, so the map layer itself has no
 * data logic to get wrong.
 */
object MapGeoJson {

    /**
     * Above this many points the display is thinned out. The renderer copes with
     * more, but the JSON string handed across the JNI boundary does not stay free.
     */
    const val MAX_DISPLAYED_POINTS = 10_000

    /**
     * @param samples oldest first
     * @return a FeatureCollection with `rsrp` and `quality` properties, ready to
     *   drive a data-driven style
     */
    fun samplesToGeoJson(samples: List<CellSampleEntity>): String {
        val positioned = samples.filter { it.lat != null && it.lon != null }
        val displayed = downsample(positioned, MAX_DISPLAYED_POINTS)

        val features = buildJsonArray {
            displayed.forEach { sample ->
                val lat = sample.lat ?: return@forEach
                val lon = sample.lon ?: return@forEach

                add(
                    buildJsonObject {
                        put("type", "Feature")
                        put("geometry", point(lon, lat))
                        put(
                            "properties",
                            buildJsonObject {
                                put("ts", sample.ts)
                                put("rat", sample.rat.name)
                                sample.rsrp?.let { put("rsrp", it) }
                                sample.rsrq?.let { put("rsrq", it) }
                                sample.sinr?.let { put("sinr", it) }
                                sample.cid?.let { put("cid", it) }
                                sample.pci?.let { put("pci", it) }
                                // A named bucket rather than a colour: the style
                                // owns the palette, the data owns the meaning.
                                put("quality", qualityOf(sample.rsrp))
                            },
                        )
                    },
                )
            }
        }

        return featureCollection(features)
    }

    fun knownCellsToGeoJson(cells: List<KnownCellEntity>): String {
        val features = buildJsonArray {
            cells.forEach { cell ->
                add(
                    buildJsonObject {
                        put("type", "Feature")
                        put("geometry", point(cell.lon, cell.lat))
                        put(
                            "properties",
                            buildJsonObject {
                                put("cid", cell.cid)
                                put("mcc", cell.mcc)
                                put("mnc", cell.mnc)
                                put("rat", cell.rat.name)
                                put("source", cell.source.name)
                                cell.rangeMeters?.let { put("range", it) }
                                cell.azimuth?.let { put("azimuth", it) }
                            },
                        )
                    },
                )
            }
        }
        return featureCollection(features)
    }

    /**
     * Keeps every nth point so the route's shape survives.
     *
     * Not a random sample and not the first n: both would either break the line
     * or cut the drive short halfway. The first and last point are always kept so
     * the track still starts and ends where it did.
     */
    fun <T> downsample(points: List<T>, maxPoints: Int): List<T> {
        require(maxPoints > 1) { "maxPoints must be greater than one, was $maxPoints" }
        if (points.size <= maxPoints) return points

        val step = points.size.toDouble() / (maxPoints - 1)
        val result = ArrayList<T>(maxPoints)
        var index = 0.0
        while (result.size < maxPoints - 1) {
            result += points[index.toInt().coerceAtMost(points.lastIndex)]
            index += step
        }
        result += points.last()
        return result
    }

    /** Buckets match the thresholds the rest of the app uses for RSRP. */
    fun qualityOf(rsrp: Int?): String = when {
        rsrp == null -> "unknown"
        rsrp >= -80 -> "excellent"
        rsrp >= -90 -> "good"
        rsrp >= -100 -> "fair"
        rsrp >= -110 -> "poor"
        else -> "bad"
    }

    private fun point(lon: Double, lat: Double) = buildJsonObject {
        put("type", "Point")
        // GeoJSON is longitude first.
        put("coordinates", JsonArray(listOf(JsonPrimitive(lon), JsonPrimitive(lat))))
    }

    private fun featureCollection(features: JsonArray): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()
}
