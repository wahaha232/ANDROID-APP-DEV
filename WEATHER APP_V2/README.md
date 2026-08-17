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
| 反向地理編碼 | Nominatim (OpenStreetMap)，精確到村里等級（例如「新北市新莊區中信里」） |
| 本地儲存 | Room（最愛城市清單）、DataStore Preferences（單位偏好與功能開關） |
| 背景工作 | WorkManager（每日天氣通知） |
| 桌面小工具 | Jetpack Glance（`glance-appwidget`），可調整大小 |
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
            ├── presentation/       # theme, navigation, weather(MVI), search(MVI), settings(抽屜選單)
            └── widget/             # 桌面小工具（Jetpack Glance）
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
- **精確定位**：GPS 座標透過 Nominatim 反向地理編碼解析到村里等級（縣市＋鄉鎮市區＋村里，例如「新北市新莊區中信里」），非台灣地區則自動退回較粗略的層級。
- **桌面天氣小工具**：城市名稱、即時溫度、天氣圖示、時間/日期/星期、4 天預報列，右上角有手動更新按鈕；支援拖曳調整大小（Small / Medium / Large 三種 Responsive 版面），與 App 內顯示的城市自動同步。

## 如何開啟與編譯

1. 用 **Android Studio**（Koala 2024.1 以上）開啟本資料夾。
2. 本專案未附帶 `gradlew` / `gradle-wrapper.jar`（二進位檔，依循此 repo 慣例不進版控），Android Studio 開啟時會自動提示產生，或手動執行 `gradle wrapper --gradle-version 8.7`。
3. 需要 Android SDK Platform 35 + Build-Tools 35.0.0、JDK 17。
4. 指令列編譯：`gradle assembleDebug`（需系統已安裝 Gradle 8.7 對應版本，或用 Android Studio 內建 Gradle）。

## 廣告（Google AdMob）

畫面中的橫幅廣告已串接**正式的 AdMob App ID / Ad Unit ID**（在 [AdMob 後台](https://apps.admob.com/) 申請）：

1. `app/src/main/AndroidManifest.xml` 裡的 `com.google.android.gms.ads.APPLICATION_ID`：`ca-app-pub-1512317781873771~8436143739`
2. `presentation/weather/components/AdBannerView.kt` 裡的 `WEATHER_BANNER_AD_UNIT_ID`：`ca-app-pub-1512317781873771/6879519480`

**注意：這是正式廣告單元，不是測試 ID**——開發者本人或用模擬器/測試裝置反覆點擊自己的廣告會違反 AdMob 政策，嚴重可能導致帳號被停用。如果之後要在自己手機上頻繁測試，建議改用 Google 官方測試 ID（`ca-app-pub-3940256099942544~3347511713` / `ca-app-pub-3940256099942544/6300978111`），或在 AdMob 後台把測試裝置加入白名單。

## 已知限制 / 待辦事項

- **雷達地圖**：Open-Meteo 免費方案不提供逐格降水雷達圖磚，卡片目前僅為裝飾性示意圖，如需真實雷達影像需另外串接 RainViewer / Windy 等付費圖磚服務。
- **隱私政策**：目前僅以應用內文字（Toast 提示）呈現，尚未有對外託管的正式隱私權政策網址；上架 Google Play 前必須補上實際可公開存取的隱私政策頁面。
- **反饋信箱**：`AppInfoActions.kt` 中的意見回饋信箱目前是預留的 `support@example.com`，請換成實際可用信箱。
- **小工具城市**：目前所有已放置的小工具都顯示「App 內最後檢視的城市」同一份快照，尚未實作「每個小工具各自選擇城市」的 Configuration Activity。
- **Nominatim 使用限制**：反向地理編碼呼叫的是 OpenStreetMap 官方免費 Nominatim 服務，使用政策限制每秒最多 1 次請求，僅適合輕量個人使用；正式大量上線建議改用自架 Nominatim 或付費地理編碼服務。

## 主題

跟隨裝置深色/淺色模式，並支援 Android 12+ 動態色彩（Material You）。
