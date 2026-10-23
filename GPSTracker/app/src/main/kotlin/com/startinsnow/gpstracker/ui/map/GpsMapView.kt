package com.startinsnow.gpstracker.ui.map

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.startinsnow.gpstracker.export.BaseMapStyles
import com.startinsnow.gpstracker.export.BaseMapType
import com.startinsnow.gpstracker.export.TerrainProviderConfig
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
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
    private var currentLocationMarker: Marker? = null

    fun setRoute(coordinates: List<Pair<Double, Double>>) {
        style?.getSourceAs<GeoJsonSource>(SOURCE_ROUTE)?.setGeoJson(lineStringGeoJson(coordinates))
    }

    /** 依速度／海拔上色的路線：每段一個 Feature，屬性 color 是 #rrggbb；沒有 color 的 Feature 用預設綠色。 */
    fun setRouteColored(segments: List<com.startinsnow.gpstracker.core.analysis.ColoredSegment>) {
        val features = JSONArray()
        segments.forEach { seg ->
            val coords = JSONArray()
            seg.coordinates.forEach { (lat, lon) -> coords.put(JSONArray().put(lon).put(lat)) }
            val geometry = JSONObject().put("type", "LineString").put("coordinates", coords)
            features.put(
                JSONObject().put("type", "Feature").put("geometry", geometry)
                    .put("properties", JSONObject().put("color", seg.color))
            )
        }
        style?.getSourceAs<GeoJsonSource>(SOURCE_ROUTE)?.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
    }

    fun setPoints(sourceId: String, points: List<Pair<Double, Double>>) {
        style?.getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(pointsGeoJson(points))
    }

    /** GPS 中斷（搭捷運）的估算路徑：每段一條線，color 為路線色（#rrggbb）；畫成虛線，與實際 GPS 路線區隔。 */
    fun setGapSegments(segments: List<Pair<List<Pair<Double, Double>>, String>>) {
        val features = JSONArray()
        segments.forEach { (coords, color) ->
            val arr = JSONArray()
            coords.forEach { (lat, lon) -> arr.put(JSONArray().put(lon).put(lat)) }
            features.put(
                JSONObject().put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "LineString").put("coordinates", arr))
                    .put("properties", JSONObject().put("color", color))
            )
        }
        style?.getSourceAs<GeoJsonSource>(SOURCE_GAP)?.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
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
        // 所有點幾乎重疊（靜止不動、零距離軌跡）時，newLatLngBounds 會縮到最大層級，地圖變成空白：改用固定縮放。
        val latSpan = coordinates.maxOf { it.first } - coordinates.minOf { it.first }
        val lonSpan = coordinates.maxOf { it.second } - coordinates.minOf { it.second }
        if (maxOf(latSpan, lonSpan) < MIN_FIT_SPAN_DEGREES) {
            val (lat, lon) = coordinates.first()
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), STATIONARY_ZOOM))
            return
        }
        val boundsBuilder = LatLngBounds.Builder()
        coordinates.forEach { (lat, lon) -> boundsBuilder.include(LatLng(lat, lon)) }
        runCatching { map?.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 64)) }
    }

    /** 縮放按鈕用：放大（delta > 0）或縮小（delta < 0）。手勢被外層捲動吃掉時仍可操作地圖。 */
    fun zoomBy(delta: Double) {
        map?.animateCamera(CameraUpdateFactory.zoomBy(delta))
    }

    fun panTo(lat: Double, lon: Double) {
        map?.easeCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lon)), 300)
    }

    /** 首頁「目前位置」：在地圖上放一個大頭針，並把鏡頭拉近到該位置。 */
    fun showCurrentLocation(lat: Double, lon: Double) {
        val currentMap = map ?: return
        val position = LatLng(lat, lon)
        val marker = currentLocationMarker
        if (marker == null) {
            currentLocationMarker = currentMap.addMarker(MarkerOptions().position(position))
        } else {
            marker.position = position
        }
        currentMap.animateCamera(
            CameraUpdateFactory.newLatLngZoom(position, CURRENT_LOCATION_ZOOM),
            500
        )
    }

    /**
     * 3D 飛越用：直接把相機放到指定座標上方（含俯角 tilt 與方位 bearing），
     * 用立即移動（非動畫）以便逐格驅動出平順的飛行。
     */
    fun flyTo(lat: Double, lon: Double, zoom: Double, tilt: Double, bearing: Double) {
        val position = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(zoom)
            .tilt(tilt)
            .bearing(bearing)
            .build()
        map?.moveCamera(CameraUpdateFactory.newCameraPosition(position))
    }

    /** 抓取目前地圖畫面（供分享快照）。 */
    fun snapshot(onReady: (Bitmap) -> Unit) {
        val current = map ?: return
        current.snapshot { bitmap -> onReady(bitmap) }
    }

    companion object {
        const val SOURCE_ROUTE = "route-source"
        private const val MIN_FIT_SPAN_DEGREES = 0.0004 // 約 40 公尺
        private const val STATIONARY_ZOOM = 17.0
        const val SOURCE_START = "start-source"
        const val SOURCE_END = "end-source"
        const val SOURCE_PHOTO = "photo-source"
        const val SOURCE_OUTAGE = "outage-source"
        const val SOURCE_GAP = "gap-source"
        const val SOURCE_PLAYBACK = "playback-source"
        private const val CURRENT_LOCATION_ZOOM = 16.0

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

/** 底圖載入完成後才加上的軌跡圖層；向量與圖片圖磚底圖共用同一份，避免漏掉任何一層。 */
private fun addTrackLayers(style: Style) {
    style.addSource(GeoJsonSource(GpsMapController.SOURCE_ROUTE))
    style.addSource(GeoJsonSource(GpsMapController.SOURCE_START))
    style.addSource(GeoJsonSource(GpsMapController.SOURCE_END))
    style.addSource(GeoJsonSource(GpsMapController.SOURCE_PHOTO))
    style.addSource(GeoJsonSource(GpsMapController.SOURCE_OUTAGE))
    style.addSource(GeoJsonSource(GpsMapController.SOURCE_GAP))
    style.addSource(GeoJsonSource(GpsMapController.SOURCE_PLAYBACK))

    style.addLayer(
        LineLayer("route-layer", GpsMapController.SOURCE_ROUTE).withProperties(
            PropertyFactory.lineColor(
                org.maplibre.android.style.expressions.Expression.toColor(
                    org.maplibre.android.style.expressions.Expression.coalesce(
                        org.maplibre.android.style.expressions.Expression.get("color"),
                        org.maplibre.android.style.expressions.Expression.literal("#00C853")
                    )
                )
            ),
            PropertyFactory.lineWidth(4f)
        )
    )
    // 估算的捷運路徑：深色描邊 + 路線色虛線，畫在實際路線之上。
    style.addLayer(
        LineLayer("gap-casing-layer", GpsMapController.SOURCE_GAP).withProperties(
            PropertyFactory.lineColor("#04121F"),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineOpacity(0.55f)
        )
    )
    style.addLayer(
        LineLayer("gap-layer", GpsMapController.SOURCE_GAP).withProperties(
            PropertyFactory.lineColor(
                org.maplibre.android.style.expressions.Expression.toColor(
                    org.maplibre.android.style.expressions.Expression.coalesce(
                        org.maplibre.android.style.expressions.Expression.get("color"),
                        org.maplibre.android.style.expressions.Expression.literal("#888888")
                    )
                )
            ),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineDasharray(arrayOf(1.4f, 1.2f))
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
}

@Composable
fun GpsMapView(
    modifier: Modifier = Modifier,
    baseMapType: BaseMapType = BaseMapType.DEFAULT,
    terrain: TerrainProviderConfig? = null,
    onMapReady: (GpsMapController) -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { GpsMapController() }
    val mapViewState = remember { androidx.compose.runtime.mutableStateOf<MapView?>(null) }

    // MapView.onDestroy() 只能呼叫一次：ON_DESTROY 與 AndroidView.onRelease 兩條路徑都會走到，
    // 且 release 時若還沒 onPause/onStop 必須先補上，否則 native 資源會留下洩漏或狀態錯亂。
    val destroyed = remember { booleanArrayOf(false) }
    val stopped = remember { booleanArrayOf(true) }
    fun destroyOnce(view: MapView) {
        if (destroyed[0]) return
        destroyed[0] = true
        view.onDestroy()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = mapViewState.value ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> { stopped[0] = false; view.onStart() }
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> { stopped[0] = true; view.onStop() }
                Lifecycle.Event.ON_DESTROY -> destroyOnce(view)
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
                view.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN,
                        android.view.MotionEvent.ACTION_POINTER_DOWN,
                        android.view.MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false // 不消費事件，地圖照常處理手勢
                }
                view.getMapAsync { mapLibreMap ->
                    controller.map = mapLibreMap
                    // 圖磚來源最高 z=maxZoom（OSM 標準圖磚是 19）；超過的層級用最高層級放大顯示，不再去要不存在的圖磚。
                    mapLibreMap.setMaxZoomPreference(baseMapType.maxZoom + 1.0)
                    val styleBuilder = if (baseMapType.vectorStyleUrl != null) {
                        // 簡易地圖：MapLibre 直接載入 OpenFreeMap 向量樣式，圖資來源標註由樣式／TileJSON 提供。
                        Style.Builder().fromUri(baseMapType.vectorStyleUrl)
                    } else {
                        Style.Builder().fromJson(
                            requireNotNull(BaseMapStyles.rasterStyleJson(baseMapType, terrain)) {
                                "圖片圖磚底圖必須有圖磚設定"
                            }
                        )
                    }
                    mapLibreMap.setStyle(styleBuilder) { style ->
                        // 向量樣式載入後才加軌跡圖層；圖片圖磚底圖同樣走這裡，確保兩種底圖的圖層完全一致。
                        addTrackLayers(style)
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
            runCatching {
                if (!stopped[0]) {
                    view.onPause()
                    view.onStop()
                    stopped[0] = true
                }
                destroyOnce(view)
            }
            // 地圖與樣式已釋放，不要讓 controller 繼續持有（避免之後對已銷毀的地圖下指令）。
            controller.style = null
            controller.map = null
        }
    )
}
