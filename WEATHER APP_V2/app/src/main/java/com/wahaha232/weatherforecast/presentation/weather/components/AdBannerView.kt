// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/components/AdBannerView.kt
package com.wahaha232.weatherforecast.presentation.weather.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/** 天氣主畫面使用的橫幅廣告單元 ID（由 AdMob 後台建立） */
const val WEATHER_BANNER_AD_UNIT_ID = "ca-app-pub-1512317781873771/6879519480"

/**
 * 可重用的 AdMob 自適應橫幅廣告元件。
 * 使用 DisposableEffect 在 Composable 離開組合時呼叫 AdView.destroy()，避免記憶體洩漏。
 */
@Composable
fun AdBannerView(
    adUnitId: String = WEATHER_BANNER_AD_UNIT_ID,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp

    val adView = remember(context, adUnitId, screenWidthDp) {
        AdView(context).apply {
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, screenWidthDp))
            this.adUnitId = adUnitId
            loadAd(AdRequest.Builder().build())
        }
    }

    DisposableEffect(adView) {
        onDispose { adView.destroy() }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().wrapContentHeight(),
        factory = { adView }
    )
}
