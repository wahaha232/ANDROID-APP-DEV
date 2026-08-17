# WEATHER APP_V2（天氣預報 App）

Production-Ready 的原生 Android 天氣預報 App，Kotlin + Jetpack Compose 實作，
採 Clean Architecture（Data / Domain / Presentation）+ MVI（StateFlow + UI State）架構。

## 技術棧

| 項目 | 選用 |
|---|---|
| 語言 / UI | Kotlin、Jetpack Compose、Material 3、Edge-to-Edge |
| SDK | compileSdk / targetSdk 35（Android 15）、minSdk 26 |
| 架構 | Clean Architecture（data / domain / presentation）+ MVI |
| 網路 | Retrofit2 + kotlinx.serialization（[Open-Meteo](https://open-meteo.com/) 免費氣象 API，無需 API Key） |
| 定位 | Google Play Services Location（FusedLocationProviderClient）+ Accompanist Permissions |
| 本地儲存 | Room（最愛城市清單）、DataStore Preferences（單位偏好與功能開關） |
| 背景工作 | WorkManager（每日天氣通知） |
| 廣告 | Google AdMob 橫幅廣告 |
| 依賴注入 | 手動 Service Locator（`di/AppContainer.kt`），未引入 Hilt/Koin |

## 專案結構

```
WEATHER APP_V2/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        └── java/com/wahaha232/weatherforecast/
            ├── WeatherApplication.kt, MainActivity.kt
            ├── di/                 # 手動依賴注入容器
            ├── domain/             # model, repository interface, usecase（純 Kotlin，無 Android 依賴）
            ├── data/               # remote(Retrofit/DTO), local(Room/DataStore), repository 實作, mapper, worker
            └── presentation/       # theme, navigation, weather(MVI), search(MVI), settings(抽屜選單)
```

## 核心功能

- **目前天氣**：即時氣溫、體感溫度、天氣圖示（WMO Code 對照）、濕度、風速/風向、氣壓、紫外線指數。
- **24 小時逐時預報** + **7 天每日預報**（含當日最高/最低溫）。
- **城市搜尋**：300ms Debounce 防抖搜尋全球城市，可加入/移除最愛城市（Room 持久化）。
- **完整 UI State**：Loading（Shimmer 骨架屏）／Success／Error（附 Retry 重試）。
- **下拉刷新**（Material 3 `PullToRefreshBox`）。
- **空氣品質 (AQI)**、**過敏原推估**、**攝影黃金/藍色時段**、**日出日落與月相**、**雷達地圖**（示意用途，Open-Meteo 免費方案無雷達圖磚 API，以裝飾性漸層卡片保留版位）。
- **左側抽屜選單**：城市清單（當前位置／編輯位置／收藏城市）、單位設定（溫度/風速/氣壓/能見度/降水）、功能開關（每日天氣通知/天氣背景/夜間資訊）、評價我／反饋意見／分享／隱私政策／版本。
- **每日天氣通知**：開關開啟後透過 WorkManager 排程每 24 小時背景更新一次通知（Android 13+ 會請求 `POST_NOTIFICATIONS` 權限）。

## 如何開啟與編譯

1. 用 **Android Studio**（Koala 2024.1 以上）開啟本資料夾。
2. 本專案未附帶 `gradlew` / `gradle-wrapper.jar`（二進位檔，依循此 repo 慣例不進版控），Android Studio 開啟時會自動提示產生，或手動執行 `gradle wrapper --gradle-version 8.7`。
3. 需要 Android SDK Platform 35 + Build-Tools 35.0.0、JDK 17。
4. 指令列編譯：`gradle assembleDebug`（需系統已安裝 Gradle 8.7 對應版本，或用 Android Studio 內建 Gradle）。

## 已知限制 / 待辦事項

- **AdMob App ID**：`AndroidManifest.xml` 中暫時使用 Google 官方**測試用** App ID（`ca-app-pub-3940256099942544~3347511713`）；橫幅廣告單元 ID 已改為正式的 `ca-app-pub-1512317781873771/6879519480`。正式上架前請把 App ID 換成你自己 AdMob 後台對應此廣告單元的正式 App ID，否則廣告可能無法正確歸戶。
- **雷達地圖**：Open-Meteo 免費方案不提供逐格降水雷達圖磚，卡片目前僅為裝飾性示意圖，如需真實雷達影像需另外串接 RainViewer / Windy 等付費圖磚服務。
- **隱私政策**：目前僅以應用內文字（Toast 提示）呈現，尚未有對外託管的正式隱私權政策網址；上架 Google Play 前必須補上實際可公開存取的隱私政策頁面。
- **反饋信箱**：`AppInfoActions.kt` 中的意見回饋信箱目前是預留的 `support@example.com`，請換成實際可用信箱。

## 主題

跟隨裝置深色/淺色模式，並支援 Android 12+ 動態色彩（Material You）。
