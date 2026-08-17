// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/WeatherApplication.kt
package com.wahaha232.weatherforecast

import android.app.Application
import com.google.android.gms.ads.MobileAds
import com.wahaha232.weatherforecast.di.AppContainer

class WeatherApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        MobileAds.initialize(this)
    }
}
