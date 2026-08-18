// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.wahaha232.weatherforecast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.startinsnow.weatherforecast"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "DEBUG_NETWORK_LOGGING", "false")
        }
        debug {
            // 開發模式啟用 OkHttp 日誌攔截器，方便除錯網路請求
            buildConfigField("boolean", "DEBUG_NETWORK_LOGGING", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ---------- Jetpack Compose & Material 3 ----------
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---------- Activity & Lifecycle ----------
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // ---------- Navigation ----------
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // ---------- Kotlin Coroutines ----------
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ---------- kotlinx.serialization ----------
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ---------- Retrofit2 (使用 kotlinx.serialization converter，串接 Open-Meteo 免費氣象 API) ----------
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ---------- Room（收藏城市本地快取） ----------
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ---------- Google Play Services Location（GPS 定位） ----------
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // ---------- Google AdMob（橫幅廣告） ----------
    implementation("com.google.android.gms:play-services-ads:23.5.0")

    // ---------- Accompanist Permissions（運行時權限請求） ----------
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // ---------- DataStore Preferences（App 設定：單位、功能開關） ----------
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ---------- WorkManager（每日天氣通知排程） ----------
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ---------- Jetpack Glance（桌面天氣小工具，可調整大小） ----------
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // ---------- Unit Test ----------
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
