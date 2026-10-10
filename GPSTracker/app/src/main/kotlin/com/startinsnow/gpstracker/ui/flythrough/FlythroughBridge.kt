package com.startinsnow.gpstracker.ui.flythrough

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import kotlinx.coroutines.flow.MutableStateFlow

/** 飛越過程中的即時 HUD 數值（供快照疊加資訊用）。 */
data class FlyoverHud(
    val distanceKm: Double = 0.0,
    val speedKmh: Double = 0.0,
    val altitudeM: Double = 0.0
)

/**
 * WebView（MapLibre GL JS）與 Kotlin 之間的橋樑。JS 端的 onReady / onProgress /
 * onHud / onFinished / onSnapshot 會回呼到這裡；StateFlow 供 Compose 訂閱。
 */
class FlythroughBridge(
    private val onReady: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    val progress = MutableStateFlow(0f)
    val hud = MutableStateFlow(FlyoverHud())
    val snapshotDataUrl = MutableStateFlow<String?>(null)
    val finishedCount = MutableStateFlow(0)

    @JavascriptInterface
    fun onReady() {
        mainHandler.post { onReady() }
    }

    @JavascriptInterface
    fun onProgress(value: Double) {
        progress.value = value.toFloat().coerceIn(0f, 1f)
    }

    @JavascriptInterface
    fun onHud(distanceKm: Double, speedKmh: Double, altitudeM: Double) {
        hud.value = FlyoverHud(distanceKm, speedKmh, altitudeM)
    }

    @JavascriptInterface
    fun onFinished() {
        finishedCount.value = finishedCount.value + 1
    }

    @JavascriptInterface
    fun onSnapshot(dataUrl: String) {
        snapshotDataUrl.value = dataUrl.ifBlank { null }
    }
}
