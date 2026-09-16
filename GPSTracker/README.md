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
./gradlew testDebugUnitTest   # 66 個純邏輯 Unit Test
./gradlew assembleDebug       # 產出 app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug           # 0 errors, 少量非阻斷性 warning
```

需要 **JDK 17**（AGP 8.6.1 需求）與本機已安裝 Android SDK（compileSdk 35 / build-tools 35.0.0），
並設定 `local.properties` 的 `sdk.dir`（此檔案已加入 .gitignore，不會被提交）。

GitHub Actions：`.github/workflows/gpstracker-build.yml` 會在 GPSTracker 有變更時自動執行
`testDebugUnitTest` → `lintDebug` → `assembleDebug` 並上傳 APK artifact。

## 本次除錯修正（Debug Pass）

### Critical
1. **權限請求競態造成無法開始記錄**：`RecordingScreen` 原本同時送出兩個權限請求
   （定位 + 相機），Android 的結果回呼會互相干擾，導致 `startRecording()` 永遠不被呼叫、
   相機權限也拿不到。→ 改為單一 `RequestMultiplePermissions`，且 `FINE || COARSE` 皆視為可記錄。
2. **相機靜默失敗（按拍照沒反應）**：拍照失敗被 `runCatching` 吞掉、對話框直接關閉；
   相機未 ready 時按下拍照只是「再要一次權限」。→ `TrackPhotoManager` 回傳 `Result`，
   `RecordingUiState.message` 統一顯示錯誤、相機準備中時按鈕顯示狀態、`DisposableEffect` 釋放相機。
3. **Service 例外會直接讓 App 崩潰**：`startRecording()` 全程沒有 `try/catch`，且 `lifecycleScope`
   沒有 exception handler。→ 加入例外邊界、錯誤回報到 UI，並在失敗時收乾淨前景服務。
4. **Crash Recovery 會產生幽靈記錄**：`MainActivity` 每次啟動都自動續錄未完成 Track，
   與使用者按下的「開始記錄」互相打架，並在 DB 留下多筆 `RECORDING` 孤兒。
   → 改為偵測後由 Home 顯示「接續這筆記錄 / 結束這筆未完成記錄」，並加上 Service 防重入（`isStarting`）。

### High
5. **Movement Mode 用 `speed ?: 0.0` 導致永遠 STATIONARY**：GPS 未提供 speed 時會一路判定靜止並在
   60 秒後自動暫停。→ 改用「位移 / 時間」推算速度作為後備值。
6. **`autoPauseEnabled` / `gpsMode` 是無效設定**：Service 從來沒讀過設定。
   → 兩者實際生效（自動暫停開關、高精度模式提高 GNSS 更新頻率）。
7. **Crash Recovery 步數被歸零**：`seedAccumulatedSteps()` 之後立刻被 `reset()` 清掉。
   → 調整 seed / reset 順序，並補上對應 Unit Test。
8. **沒有「尚未收到定位」的提示**：室內 / GPS 未開啟時畫面永遠停在 0 km。
   → `LocationQualityManager` 新增「從未收到定位」逾時判斷，Service 主動提示原因。
9. **每個 GPS 點都做一次 DB read+update**，與註解「定期快照」矛盾。
   → 統計快照節流（10 秒），並改用 `hasOpenOutage` 快取避免每點查 DB。

### Medium
10. **照片沒有 EXIF GPS**：→ 拍照時寫入 `ImageCapture.Metadata.location`（經緯度 / 高度 / 時間）。
11. **刪除 Track 留下孤兒檔案**：→ 一併刪除 `exports/<trackId>.zip` 與相機照片目錄。
12. **地圖沒有畫出 GPS 中斷標記、大量點會卡頓**：→ 餵入 outage 圖層，並新增 `RouteSimplifier`
    只縮減「地圖顯示」座標（匯出仍為完整資料）。
13. **MapView 沒有 `onDestroy()`**（native 資源洩漏）→ 改用 `AndroidView(onRelease = { it.onDestroy() })`。
14. **Track 統計不一致**：`durationMs` 用 wall clock（含暫停時間）、重算可能小於即時快照。
    → `finalizeTrack` 接受實際 active duration，並以「重算 / 快照取較大值」確保不倒退。
15. **Google 登入取消可能 NPE**、**Drive 重複上傳 / `+` 編碼查不到資料夾 / 每次新建 OkHttpClient**
    → 全部修正，並加入同名檔案略過（duplicate prevention）。
16. **離線地圖每次開關都重建區域並重複下載** → 先列出既有 region，同 metadata 直接沿用。
17. **Room `exportSchema = true` 但沒有 schema 目錄** → 設定 `room.schemaLocation`（已產出 `app/schemas/…/1.json`）。
18. **AutoCleanupWorker 全表載入記憶體** → 改由 SQL 篩選。

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
