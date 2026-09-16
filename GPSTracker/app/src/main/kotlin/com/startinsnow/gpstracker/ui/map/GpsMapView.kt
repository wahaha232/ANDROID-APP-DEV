package com.startinsnow.gpstracker.ui.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.startinsnow.gpstracker.export.TileProviderConfig
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/** 對應規格「37. Map 顯示」。用 GeoJsonSource + LineLayer/CircleLayer 畫路線與各種 Marker，不使用 Google Maps。 */
class GpsMapController {
    var map: MapLibreMap? = null
    var style: Style? = null

    fun setRoute(coordinates: List<Pair<Double, Double>>) {
        style?.getSourceAs<GeoJsonSource>(SOURCE_ROUTE)?.setGeoJson(lineStringGeoJson(coordinates))
    }

    fun setPoints(sourceId: String, points: List<Pair<Double, Double>>) {
        style?.getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(pointsGeoJson(points))
    }

    fun setPlaybackMarker(lat: Double, lon: Double) {
        style?.getSourceAs<GeoJsonSource>(SOURCE_PLAYBACK)?.setGeoJson(pointsGeoJson(listOf(lat to lon)))
    }

    fun fitBounds(coordinates: List<Pair<Double, Double>>) {
        if (coordinates.size < 2) {
            coordinates.firstOrNull()?.let { (lat, lon) ->
                map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 15.0))
            }
            return
        }
        val boundsBuilder = LatLngBounds.Builder()
        coordinates.forEach { (lat, lon) -> boundsBuilder.include(LatLng(lat, lon)) }
        runCatching { map?.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 64)) }
    }

    fun panTo(lat: Double, lon: Double) {
        map?.easeCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lon)), 300)
    }

    companion object {
        const val SOURCE_ROUTE = "route-source"
        const val SOURCE_START = "start-source"
        const val SOURCE_END = "end-source"
        const val SOURCE_PHOTO = "photo-source"
        const val SOURCE_OUTAGE = "outage-source"
        const val SOURCE_PLAYBACK = "playback-source"

        private fun lineStringGeoJson(coordinates: List<Pair<Double, Double>>): String {
            val coordsArray = JSONArray()
            coordinates.forEach { (lat, lon) -> coordsArray.put(JSONArray().put(lon).put(lat)) }
            val geometry = JSONObject().put("type", "LineString").put("coordinates", coordsArray)
            return JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", JSONObject()).toString()
        }

        private fun pointsGeoJson(points: List<Pair<Double, Double>>): String {
            val features = JSONArray()
            points.forEach { (lat, lon) ->
                val geometry = JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat))
                features.put(JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", JSONObject()))
            }
            return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
        }
    }
}

private fun buildStyleJson(tileProvider: TileProviderConfig): String = """
{
  "version": 8,
  "sources": {
    "base-tiles": {
      "type": "raster",
      "tiles": ["${tileProvider.tileUrlTemplate}"],
      "tileSize": 256,
      "attribution": "${tileProvider.attributionHtml.replace("\"", "\\\"")}"
    }
  },
  "layers": [
    { "id": "base-tiles-layer", "type": "raster", "source": "base-tiles" }
  ]
}
""".trimIndent()

@Composable
fun GpsMapView(
    modifier: Modifier = Modifier,
    tileProvider: TileProviderConfig = TileProviderConfig.DEFAULT_OSM,
    onMapReady: (GpsMapController) -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { GpsMapController() }
    val mapViewState = remember { androidx.compose.runtime.mutableStateOf<MapView?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = mapViewState.value ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                Lifecycle.Event.ON_DESTROY -> view.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            MapView(context).also { view ->
                mapViewState.value = view
                view.onCreate(null)
                view.getMapAsync { mapLibreMap ->
                    controller.map = mapLibreMap
                    mapLibreMap.setStyle(Style.Builder().fromJson(buildStyleJson(tileProvider))) { style ->
                        style.addSource(GeoJsonSource(GpsMapController.SOURCE_ROUTE))
                        style.addSource(GeoJsonSource(GpsMapController.SOURCE_START))
                        style.addSource(GeoJsonSource(GpsMapController.SOURCE_END))
                        style.addSource(GeoJsonSource(GpsMapController.SOURCE_PHOTO))
                        style.addSource(GeoJsonSource(GpsMapController.SOURCE_OUTAGE))
                        style.addSource(GeoJsonSource(GpsMapController.SOURCE_PLAYBACK))

                        style.addLayer(
                            LineLayer("route-layer", GpsMapController.SOURCE_ROUTE).withProperties(
                                PropertyFactory.lineColor("#00C853"),
                                PropertyFactory.lineWidth(4f)
                            )
                        )
                        style.addLayer(
                            CircleLayer("start-layer", GpsMapController.SOURCE_START).withProperties(
                                PropertyFactory.circleColor("#00C853"),
                                PropertyFactory.circleRadius(7f)
                            )
                        )
                        style.addLayer(
                            CircleLayer("end-layer", GpsMapController.SOURCE_END).withProperties(
                                PropertyFactory.circleColor("#E53935"),
                                PropertyFactory.circleRadius(7f)
                            )
                        )
                        style.addLayer(
                            CircleLayer("photo-layer", GpsMapController.SOURCE_PHOTO).withProperties(
                                PropertyFactory.circleColor("#FFB300"),
                                PropertyFactory.circleRadius(5f)
                            )
                        )
                        style.addLayer(
                            CircleLayer("outage-layer", GpsMapController.SOURCE_OUTAGE).withProperties(
                                PropertyFactory.circleColor("#9E9E9E"),
                                PropertyFactory.circleRadius(5f)
                            )
                        )
                        style.addLayer(
                            CircleLayer("playback-layer", GpsMapController.SOURCE_PLAYBACK).withProperties(
                                PropertyFactory.circleColor("#1565C0"),
                                PropertyFactory.circleRadius(9f)
                            )
                        )
                        controller.style = style
                        onMapReady(controller)
                    }
                }
            }
        },
        onRelease = { view ->
            // Compose 釋放 AndroidView 時務必呼叫 MapView.onDestroy()，
            // 否則 native 資源（GL context / tiles）會在每次進出畫面時累積洩漏。
            mapViewState.value = null
            runCatching { view.onDestroy() }
        }
    )
}
