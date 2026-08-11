# Calculator（Android 計算機）

一個簡單的 Android 計算機 App，Kotlin + View Binding 實作，支援加減乘除、正負號切換、百分比、清除、退格。

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
        ├── java/com/example/calculator/MainActivity.kt
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

- App 圖示是用簡單向量圖形（`ic_launcher_foreground.xml`）畫的示意圖，不是設計稿，可以之後用 Android Studio 的 **Image Asset** 工具重新產生。
- 計算邏輯是「即時運算」型（例如 `5 + 3 + 2 =` 會依序算 5+3=8、8+2=10），不是完整的數學運算式解析器（不支援括號、運算子優先順序），這也是市面上大多數基本計算機 App 的行為方式。

## 廣告（Google AdMob）

畫面最下方有一個橫幅（Banner）廣告位，用 `com.google.android.gms:play-services-ads` 實作，已經串接**正式的 AdMob App ID / Ad Unit ID**（在 [AdMob 後台](https://apps.admob.com/) 申請，App 名稱「小算盤」）：

1. `app/src/main/AndroidManifest.xml` 裡的 `com.google.android.gms.ads.APPLICATION_ID`
2. `app/src/main/res/layout/activity_main.xml` 裡 `AdView` 的 `ads:adUnitId`

**注意：這是正式廣告單元，不是測試 ID**——開發者本人或用模擬器/測試裝置反覆點擊自己的廣告會違反 AdMob 政策，嚴重可能導致帳號被停用。如果之後要在自己手機上頻繁測試，建議改用 Google 官方測試 ID（`ca-app-pub-3940256099942544~3347511713` / `ca-app-pub-3940256099942544/6300978111`），或在 AdMob 後台把測試裝置加入白名單。
