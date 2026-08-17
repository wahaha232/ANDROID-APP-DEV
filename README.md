# ANDROID-APP-DEV

多個 Android App 專案的集中管理 repo，每個 App 各自獨立一個資料夾，方便未來擴充。

## 目前的 App

- [`Calculator/`](Calculator/) — 計算機 App（詳見資料夾內的 README）
- [`WEATHER APP_V2/`](WEATHER%20APP_V2/) — 天氣預報 App，Kotlin + Jetpack Compose，Clean Architecture + MVI（詳見資料夾內的 README）

## 慣例

- 每個 App 都是完整、獨立的 Gradle 專案（有自己的 `settings.gradle.kts`），彼此不共用程式碼。
- 編譯用的 GitHub Actions workflow 統一放在根目錄的 `.github/workflows/`（GitHub 規定只認這個路徑），但實際編譯指令會 `cd` 進對應的 App 資料夾執行。
- 每個 App 資料夾底下有自己的 `releases/` 子資料夾，存放最新一次編譯成功的 debug APK。
