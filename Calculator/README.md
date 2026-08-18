# Calculator（小算盤）

一個簡單的 Android 計算機 App，Kotlin + View Binding 實作，支援加減乘除、正負號切換、百分比、清除、退格。
App 顯示名稱為「小算盤」，`applicationId` 為 `com.startinsnow.calculator`（Kotlin 原始碼的 package
仍是 `com.wahaha232.calculator`，兩者可以不同，改 `applicationId` 不需要搬動原始碼資料夾）。

## 專案結構

```
AndroidCalculator/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/wahaha232/calculator/MainActivity.kt
        └── res/
            ├── layout/activity_main.xml
            ├── values/{strings,colors,themes,styles}.xml
            ├── drawable/{bg_button_round,ic_launcher_background,ic_launcher_foreground}.xml
            └── mipmap-anydpi-v26/{ic_launcher,ic_launcher_round}.xml
```

## 如何開啟與執行

1. 開啟 **Android Studio**（建議 Android Studio Koala 2024.1 以上），選擇 **Open** 並指向 `AndroidCalculator` 這個資料夾。
2. 第一次開啟時 Android Studio 會提示建立 **Gradle Wrapper**（本專案未附帶 `gradlew`/`gradle-wrapper.jar`，因為那是二進位檔），直接允許它自動產生即可，或手動執行 `gradle wrapper`。
3. 等待 Gradle Sync 完成（會依 `build.gradle.kts` 自動下載 AGP 8.5.0 / Kotlin 1.9.24 相關套件，需要網路）。
4. 用實機或模擬器（建議 API 24 以上）按下 **Run ▶**。

## 已知限制

- App 圖示是用向量圖形（`ic_launcher_foreground.xml`）畫的示意圖（Material 3 / Android 16 風格，含 Android 13+ 單色主題圖示支援），不是設計稿。
- 計算邏輯支援**先乘除後加減**的標準運算子優先順序（例如 `5 + 3 × 2 =` 會算出 11，不是 16），但不支援括號。

## 主題

跟隨裝置的深色/淺色模式自動切換（`values/colors.xml` 為淺色、`values-night/colors.xml` 為深色）。

## 頁籤：計算 / 換算

畫面最上方是「計算」「換算」頁籤（同一個 Activity 內切換，不是另開新頁面），右上角有「EN / 中」語言切換按鈕。

**換算頁**支援 TWD / USD / JPY / HKD / CNY / EUR / GBP 互相換算，匯率資料來自 [exchangerate-api.com](https://www.exchangerate-api.com/) 的免費公開端點（`open.er-api.com`，不需 API 金鑰，資料每日更新），開啟 App 時即時抓取。需要網路連線，抓取失敗會顯示錯誤訊息。目前只有匯率換算一種，長度/重量/溫度等其他換算功能之後可以再擴充成頁籤內的子分類。

## 多語系

`values/strings.xml`（繁體中文，預設）+ `values-en/strings.xml`（英文）。透過 AndroidX 的 Per-App Language API（`AppCompatDelegate.setApplicationLocales`）切換，不需要跟著改手機系統語言。切換語言會重新啟動畫面（Activity recreate），是正常行為。

## 廣告（Google AdMob）

畫面最下方有一個橫幅（Banner）廣告位，用 `com.google.android.gms:play-services-ads` 實作，已經串接**正式的 AdMob App ID / Ad Unit ID**（在 [AdMob 後台](https://apps.admob.com/) 申請，App 名稱「小算盤」）：

1. `app/src/main/AndroidManifest.xml` 裡的 `com.google.android.gms.ads.APPLICATION_ID`
2. `app/src/main/res/layout/activity_main.xml` 裡 `AdView` 的 `ads:adUnitId`

**注意：這是正式廣告單元，不是測試 ID**——開發者本人或用模擬器/測試裝置反覆點擊自己的廣告會違反 AdMob 政策，嚴重可能導致帳號被停用。如果之後要在自己手機上頻繁測試，建議改用 Google 官方測試 ID（`ca-app-pub-3940256099942544~3347511713` / `ca-app-pub-3940256099942544/6300978111`），或在 AdMob 後台把測試裝置加入白名單。
