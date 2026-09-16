# GPS TRACKER

個人 GPS 軌跡記錄 App（「簡化版 Relive」）。Kotlin + Jetpack Compose 實作，`applicationId`
為 `com.startinsnow.gpstracker`。不上架 Google Play，僅供 APK 自行安裝使用。

核心原則：Offline First、Battery Efficient、Location Quality Aware、Real GPS Data、
Local Data First + Batch Cloud Sync、Interactive Playback、No Google Maps、No Fake Features。

## 技術棧

- Kotlin 2.0.21、AGP 8.6.1、Gradle 8.7、JDK 17、compileSdk/targetSdk 35、minSdk 26
- UI：Jetpack Compose + Navigation Compose
- 地圖：**MapLibre**（Android）／**Leaflet**（Browser 匯出的 index.html），完全不使用 Google Maps
- 定位：`android.location.LocationManager`（GPS_PROVIDER + NETWORK_PROVIDER），不依賴 Google Play
  Services Fused Location，確保核心 GPS 邏輯與網路、Google 服務解耦
- 資料庫：Room（WAL 模式，支援 Crash Recovery）
- 拍照：CameraX
- 步數：`Sensor.TYPE_STEP_COUNTER`（無則退回 `TYPE_STEP_DETECTOR`），不整合 Google Fit / Health Connect
- 雲端備份：Google Drive REST v3（OkHttp 直接呼叫，未使用笨重的 google-api-client），
  透過 `play-services-auth` 取得 OAuth Token

## 專案結構

```
GPSTracker/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
└── app/
    └── src/
        ├── main/kotlin/com/startinsnow/gpstracker/
        │   ├── core/                # 平台無關的資料模型與純數學（GeoMath）
        │   ├── location/            # LocationTracker、GpsDriftFilter、LocationQualityManager、AdaptiveSamplingPolicy
        │   ├── movement/            # MovementDetector（Walking/Bicycle/Motorcycle/Car/Bus/Airplane/Stationary）
        │   ├── sensor/              # StepCounterManager / StepCounterMath
        │   ├── photo/               # TrackPhotoManager（CameraX）
        │   ├── service/             # TrackingForegroundService（背景記錄引擎）、AutoCleanupWorker
        │   ├── data/                # Room Entities/DAO/Database、TrackRepository、StatsCalculator、SettingsRepository(DataStore)
        │   ├── playback/            # TrackPlaybackEngine（Relive 風格互動式回放的內插邏輯）
        │   ├── export/              # GpxExporter/JsonExporter/CsvExporter/TrackHtmlGenerator(Leaflet)/TrackZipExporter
        │   ├── sync/                # GoogleAuthManager、DriveSyncManager
        │   └── ui/                  # Compose 畫面（Home/Recording/History/Detail+Playback/Settings）+ MapLibre 封裝
        └── test/kotlin/...          # JVM Unit Test（不需要裝置/模擬器）
```

## 建置方式

```bash
cd GPSTracker
./gradlew testDebugUnitTest   # 44 個純邏輯 Unit Test
./gradlew assembleDebug       # 產出 app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug           # 0 errors, 少量非阻斷性 warning
```

需要本機已安裝 Android SDK（compileSdk 35 / build-tools 35.0.0）並設定 `local.properties` 的
`sdk.dir`（此檔案已加入 .gitignore，不會被提交）。

## 待實機驗證項目

以下功能在純 CLI/CI 環境中已完成程式碼與架構，但需要一台真實 Android 裝置（GPS 訊號、相機、
步數感測器、背景服務生命週期、實際電量）才能完整驗證：

- GPS 收訊、室內定位降級、GPS Lost/Recovery、GPS 漂移過濾的實測行為
- Movement Mode（交通模式）在真實移動情境下的判斷準確度
- Local Step Counter 在真實裝置上的計步準確度與重開機後的接續
- CameraX 拍照與 EXIF/GPS 綁定
- Foreground Service 背景記錄（App 切背景、電池優化白名單、Doze 模式）
- Google Drive 上傳：需要在 Google Cloud Console 設定對應的 OAuth Client（Android，含正確的
  SHA-1 憑證指紋）才能完成登入與上傳，目前尚未內建任何專案的 OAuth Client 設定
- MapLibre 地圖圖磚下載與離線地圖下載（目前預設使用 OpenStreetMap 標準圖磚，正式/高流量使用
  請依 OSM Tile Usage Policy 更換為自架或商用 Tile Provider，設定在
  `export/ExportTrackData.kt` 的 `TileProviderConfig`）
