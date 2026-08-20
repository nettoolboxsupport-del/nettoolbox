package de.nettoolbox.feature.map.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The only file in the project that touches MapLibre.
 *
 * Everything the map draws arrives as a finished GeoJSON string from
 * `MapGeoJson`, which is pure and unit-tested. If the MapLibre API turns out to
 * differ from what is written here, this file is the whole blast radius.
 *
 * A GeoJSON source with a data-driven style rather than individual markers, as
 * the spec requires: markers are views, and ten thousand of them do not survive
 * the first pan.
 */
@Composable
fun MapLibreMapView(
    samplesGeoJson: String,
    knownCellsGeoJson: String,
    showSamples: Boolean,
    showKnownCells: Boolean,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = rememberMapViewWithLifecycle(lifecycleOwner.lifecycle)

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            view.getMapAsync { map ->
                map.setStyle(Style.Builder().fromJson(RASTER_STYLE_JSON)) { style ->
                    style.updateOrAddSource(SOURCE_SAMPLES, samplesGeoJson)
                    style.updateOrAddSource(SOURCE_KNOWN_CELLS, knownCellsGeoJson)

                    if (style.getLayer(LAYER_SAMPLES) == null) {
                        style.addLayer(sampleLayer())
                    }
                    if (style.getLayer(LAYER_KNOWN_CELLS) == null) {
                        style.addLayer(knownCellLayer())
                    }

                    style.getLayer(LAYER_SAMPLES)?.setProperties(
                        PropertyFactory.visibility(if (showSamples) "visible" else "none"),
                    )
                    style.getLayer(LAYER_KNOWN_CELLS)?.setProperties(
                        PropertyFactory.visibility(if (showKnownCells) "visible" else "none"),
                    )
                }
            }
        },
    )
}

private fun Style.updateOrAddSource(id: String, geoJson: String) {
    val existing = getSourceAs<GeoJsonSource>(id)
    if (existing == null) {
        addSource(GeoJsonSource(id, geoJson))
    } else {
        existing.setGeoJson(geoJson)
    }
}

/**
 * Colour by the `quality` property the data carries. The palette lives here, the
 * meaning lives in the data - so the same buckets drive the map, the list and the
 * KML export.
 */
private fun sampleLayer(): CircleLayer = CircleLayer(LAYER_SAMPLES, SOURCE_SAMPLES)
    .withProperties(
        PropertyFactory.circleRadius(4f),
        PropertyFactory.circleOpacity(0.85f),
        PropertyFactory.circleStrokeWidth(0.5f),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleColor(
            Expression.match(
                Expression.get("quality"),
                Expression.color(android.graphics.Color.parseColor("#8A8A8A")),
                Expression.stop("excellent", Expression.color(android.graphics.Color.parseColor("#1B7F3B"))),
                Expression.stop("good", Expression.color(android.graphics.Color.parseColor("#5A8A16"))),
                Expression.stop("fair", Expression.color(android.graphics.Color.parseColor("#9A6B00"))),
                Expression.stop("poor", Expression.color(android.graphics.Color.parseColor("#B55418"))),
                Expression.stop("bad", Expression.color(android.graphics.Color.parseColor("#B3261E"))),
            ),
        ),
    )

private fun knownCellLayer(): CircleLayer = CircleLayer(LAYER_KNOWN_CELLS, SOURCE_KNOWN_CELLS)
    .withProperties(
        PropertyFactory.circleRadius(6f),
        PropertyFactory.circleColor("#0B6FA4"),
        PropertyFactory.circleOpacity(0.6f),
        PropertyFactory.circleStrokeWidth(1f),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
    )

/**
 * Creates the [MapView] once and forwards the activity lifecycle to it. MapLibre
 * holds a GL surface and leaks it if these calls are missed.
 */
@Composable
private fun rememberMapViewWithLifecycle(lifecycle: Lifecycle): MapView {
    val context = androidx.compose.ui.platform.LocalContext.current

    val mapView = remember {
        // Must run before the first MapView exists.
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    return mapView
}

private const val SOURCE_SAMPLES = "nettoolbox-samples"
private const val SOURCE_KNOWN_CELLS = "nettoolbox-known-cells"
private const val LAYER_SAMPLES = "nettoolbox-samples-layer"
private const val LAYER_KNOWN_CELLS = "nettoolbox-known-cells-layer"

/**
 * Raster style over the standard OpenStreetMap tiles.
 *
 * **Usage policy:** these tiles are provided for light use only. Bulk downloading
 * is forbidden, and a released build should point at a self-hosted or commercial
 * tile source - the settings already carry a MapTiler key field for that. The
 * attribution below is mandatory and must stay visible.
 */
private const val RASTER_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [
    { "id": "osm", "type": "raster", "source": "osm" }
  ]
}
"""
