# HealthTracker UI 開源資源評估報告

檢查日期：2026-10-20（所有 repo 皆以 `git clone --depth 1` 放在 `D:\Claude\scratch\ui_research\<repo名>`，只用 Read/Grep 讀檔，未執行任何下載來的建置腳本或程式）。
範圍：只做研究與報告，未修改 HealthTracker 任何原始碼，未 push、未部署、未發布 APK。

## 0. 本 App 現況與缺口（已讀程式確認）

- 技術：Kotlin 2.0.21、Compose BOM 2024.10.01、Material3、material-icons-extended、navigation-compose，單純 Compose、無圖表套件。
- 總覽 `OverviewScreen.kt`（275 行）：`GlucoseHero` 漸層大數字、`TrendCard`／`TrendChart`（Canvas 自繪，含使用者目標範圍色帶、2dp 直線連接、格線、只標最後一點數值）。
- 紀錄 `RecordsScreen.kt`：FlowRow 篩選 chip、依日期分組 LazyColumn、刪除確認 AlertDialog。空狀態只有一行 `Text`。
- 已具備：淺/深色主題、無障礙 contentDescription、App 不做好壞判斷（色帶只依使用者設定）。
- 觀察到的缺口：
  1. 趨勢圖無法觸碰查看某一點的數值/時間（只標最後一點）。
  2. 空狀態只是一行字，沒有圖示與行動按鈕。
  3. `collectAsState(initial = emptyList())`（OverviewScreen、RecordsScreen 都有）會讓資料庫還沒讀回來的第一幀被當成「沒有紀錄」，可能閃一下空狀態或「還沒有血糖紀錄」。
  4. 7／30 天用兩個獨立 FilterChip，語意是單選但外觀像複選。

## 1. 實際檢查的 Repository

| # | Repository | 網址 | 最後 commit | LICENSE | 結論 |
|---|---|---|---|---|---|
| 1 | Gurupreet/ComposeCookBook | https://github.com/Gurupreet/ComposeCookBook | 2026-09-16 | MIT | 僅參考寫法（不加依賴） |
| 2 | Hiten24/Compose-Ui-Templates | https://github.com/Hiten24/Compose-Ui-Templates | 2021-11-20 | Apache-2.0 | 淘汰（過舊） |
| 3 | android/compose-samples | https://github.com/android/compose-samples | 2026-10-19 | Apache-2.0 | 採用（借 JetLagged 圖表互動做法） |
| 4 | android/architecture-templates | https://github.com/android/architecture-templates | 2024-12-12 | Apache-2.0 | 淘汰（不相關，不是 UI 元件庫） |
| 5 | Trendyol/komposto | https://github.com/Trendyol/komposto | 2026-08-17 | Apache-2.0 | 淘汰（自有設計系統） |
| 6 | samadtalukder/Jetpack-Awesome-UI | https://github.com/samadtalukder/Jetpack-Awesome-UI | 2025-05-21 | 無 LICENSE 檔 | 淘汰（授權不明） |
| 7 | ananyavaidya7/glucose-records-tracker | https://github.com/ananyavaidya7/glucose-records-tracker | 2026-07-13 | MIT | 僅參考（選用：數字鍵盤） |
| 8 | patrykandpatrick/vico | https://github.com/patrykandpatrick/vico | 2026-10-18 | Apache-2.0 | 不採用（現有 Canvas 已夠） |
| 9 | RevenueCat/placeholder-compose | https://github.com/RevenueCat/placeholder-compose | 2026-09-08 | Apache-2.0 | 不採用（無必要新增依賴） |
| 10 | Mohamed-AB5/SugarCare | https://github.com/Mohamed-AB5/SugarCare | 2026-09-29 | 無 LICENSE 檔 | 淘汰（授權不明） |
| 11 | dzenancerk/meditrack | https://github.com/dzenancerk/meditrack | 2026-10-01 | 無 LICENSE 檔 | 淘汰（授權不明） |
| 12 | So1uv/Leafit | https://github.com/So1uv/Leafit | 2026-09-25 | GPL-3.0 | 淘汰（GPL，不得整合） |

另有搜尋到但未 clone、標為「未檢查」：marlonlom/glucoreo（最後更新 2023）、khairyKY/sukoon、psjostrom/strimma、Juggluco 的 Compose PR（授權為 GPL 系，且是 CGM 即時監測，與本 App 場景不同）。這些只出現在搜尋摘要，授權與程式碼都沒驗證，不建議採用。

## 2. 各候選詳細評估

### 1. ComposeCookBook（MIT，2026-09-16）
- 程式實況：Kotlin 2.1.20、BOM 2024.12.01、AGP 8.7.3；約 96 個檔用 Material3、141 個檔仍用舊 Material2 `androidx.compose.material.*`，混用。是「展示合集」不是可直接引用的元件庫。
- 可看的：`components/charts/.../Charts.kt`（306 行）的 `LineChart` 用 `Animatable` 做折線「由左到右畫出」動畫。
- 缺點：圖表用隨機資料、無座標軸/無互動；無單位測試；程式風格偏展示。
- 結論：不加依賴。只參考「Animatable 進場動畫」概念，可選。

### 2. Compose-Ui-Templates（Apache-2.0，2021-11-20）
- Compose 1.0.3、Kotlin 1.5.30，0 個檔用 material3。四年前的版本，API 與本專案差距大。
- 結論：淘汰。

### 3. compose-samples（Apache-2.0，2026-10-19）
- 官方維護，BOM 2026.09.00，品質高。與本 App 最相關的是 `JetLagged`（睡眠/心率儀表板）：
  - `heartrate/HeartRateGraph.kt`：`drawWithCache` + `Path` 繪圖、`drawHighlight()`（虛線垂直指示線 + 圓點 + 圓角數值氣泡，並用 `coerceIn` 避免氣泡超出畫面）。
  - `sleep/JetLaggedHeaderTabs.kt`：Tab 指示器動畫。
- 注意：該圖用 `cubicTo` 平滑曲線，且範例資料是假資料；本 App 建議維持直線連接（平滑曲線會畫出沒有量測過的數值，對健康紀錄不誠實），只借「指示線與氣泡」的做法。氣泡顏色是寫死白/綠色，不可照抄，要改用 `MaterialTheme.colorScheme`。
- 結論：採用「借寫法」，不加依賴。檔案頭有 Apache-2.0 標頭，借用時要在原始碼註明來源與授權。

### 4. architecture-templates（Apache-2.0，2024-12-12）
- 是專案架構範本（ViewModel/Hilt/Room 骨架），只有 main 分支範本，沒有可借用的 UI 元件。本 App 架構已運作，不需換。
- 結論：淘汰（與 UI 缺口無關）。

### 5. komposto（Apache-2.0，2026-08-17）
- Kotlin 2.1.21，是 Trendyol 的完整設計系統（自有 core/theme/bottomsheet 模組，用 Maven 發布），核心模組未使用 material3。引入等於換一套設計語言，違反「不整套換 UI 框架」。
- 結論：淘汰。

### 6. Jetpack-Awesome-UI（無 LICENSE，2025-05-21）
- 根目錄沒有 LICENSE 檔（只有 CONTRIBUTING.md、README.md）。86 個 .kt、56 個用 material3，技術上相容，但依規則「授權不明一律淘汰」。
- 結論：淘汰，未讀其元件細節以外的內容，不可複製程式碼。

### 7. glucose-records-tracker（MIT，2026-07-13）
- 技術相容度很高：Kotlin 2.0.21、BOM 2024.12.01，與本 App 幾乎同版。Room + Compose，17 個 .kt，其中 `ui/RecordApp.kt` 615 行。
- 性質：給長輩用、純記錄，README 明寫「不做醫療解讀」，與本 App 理念一致。
- 可借的：
  - `NumericKeypad`/`KeyRow`（`RecordApp.kt` 約 500–527 行）：72dp 高的大按鈕自製數字鍵盤，不需系統 QWERTY 鍵盤。
  - `ReadingCard` 的「下一筆待輸入」強調（primaryContainer + 邊框）。
  - 日期前後切換列 `DateNavigationBar`。
- 缺點：全部英文硬編碼字串、`MaterialTheme` 全名冗長、一個大檔案、檔案有亂碼符號（`âŒ«`，要換成 Icon）。
- 結論：只參考，不加依賴；鍵盤是選用項（見建議 4）。

### 8. Vico（Apache-2.0，2026-10-18）
- 最新版 toml：Kotlin 2.4.21、Compose 1.12.2、Material3 1.9.0，與本專案的 Kotlin 2.0.21 / BOM 2024.10 落差大，用最新版需升級整個工具鏈；要用舊版就要自己挑相容版本並實測。
- 它是完整圖表庫（軸、互動、縮放），但本 App 圖表需求很單純（單一折線 + 使用者目標色帶），而且色帶「只依使用者範圍」這種客製，用現成圖表庫反而要寫裝飾器。
- 結論：不採用。若日後需要縮放/大量資料點（數千筆）再重評。

### 9. placeholder-compose（Apache-2.0，2026-09-08）
- KMP 函式庫，Shimmer/Pulse/Fade 效果，有 M3 主題整合。但本 App 資料來自本機 Room，載入是毫秒級，不需要骨架屏；為此加依賴不划算。
- 結論：不採用。需要時 Shimmer 約 40 行，可自寫（概念可參考其 `Shimmer.kt` 的 linearGradient 位移做法，Apache-2.0）。

### 10–12. SugarCare、meditrack、Leafit
- SugarCare（Compose + Firebase + Material3，有血糖情境記錄）、meditrack（掃描+OCR+血糖圖表）：根目錄都沒有 LICENSE 檔，授權不明，淘汰。
- Leafit：GPL-3.0，不得整合，淘汰（也沒有血糖功能）。

## 3. 針對各畫面的具體改善建議（依價值排序）

標記 ★ 為建議優先做的前 3 項。

### ★1. 總覽趨勢圖：點選／拖曳顯示該點數值與時間（價值最高）
- 現況：`TrendChart` 只標最後一點的值，使用者想知道圖上某個點是幾點幾分、多少 mg/dL，只能去紀錄頁找。
- 做法：借 compose-samples/JetLagged `HeartRateGraph.kt` 的 `drawHighlight` 概念（虛線指示線 + 圓點 + 圓角氣泡 + `coerceIn` 夾邊）。實作用 `Modifier.pointerInput { detectTapGestures / detectHorizontalDragGestures }` 找離手指最近的資料點，狀態用 `remember { mutableStateOf<Int?>(null) }`。氣泡顏色用 `MaterialTheme.colorScheme`，不要照抄範例寫死的白/綠。氣泡內容為「數值 + 日期時間 + 情境」，不加任何好壞字樣。
- 修改檔案：僅 `ui/OverviewScreen.kt`（`TrendChart`）；可再抽成純函式（最近點計算）放進 `OverviewMath` 以便單元測試。
- 風險：低。不加依賴；注意觸控目標、`semantics` 無障礙描述要同步更新；保持直線連接，不要改成平滑曲線。可選再加 `Animatable` 進場畫線動畫（參考 CookBook `Charts.kt`），要尊重系統「移除動畫」設定。

### ★2. 空狀態改善 + 修正「載入前閃空狀態」
- 現況：`RecordsScreen` 空清單只有一行 `Text`；`collectAsState(initial = emptyList())` 讓第一幀誤判為無資料。
- 做法：不需借用 repo，Material3 自組即可：`EmptyState(icon, title, body, actionLabel)`（大圖示 + 說明 + 「新增第一筆」按鈕）。載入狀態改成 `collectAsState(initial = null)`（型別 `List<HealthRecord>?`），null 時顯示空白或簡單的 `CircularProgressIndicator`，不要顯示空狀態。若將來真的需要骨架屏，再自寫約 40 行 shimmer，不引入 placeholder-compose。
- 修改檔案：`ui/RecordsScreen.kt`、`ui/OverviewScreen.kt`（`list`、`GlucoseHero` 的 null 判斷要區分「尚未載入」與「沒有紀錄」）、可能新增 `ui/EmptyState.kt`。
- 風險：低到中。改動 `list` 型別會影響下游使用處，要逐一檢查；文案維持中性，不暗示好壞。

### ★3. 7／30 天切換改用 M3 SegmentedButton
- 現況：兩個獨立 FilterChip 當單選。
- 做法：用 Material3 內建 `SingleChoiceSegmentedButtonRow` + `SegmentedButton`（BOM 2024.10 已含，免加依賴）；Tab 指示器動畫可參考 JetLagged `JetLaggedHeaderTabs.kt` 但不必抄。
- 修改檔案：`ui/OverviewScreen.kt`（`TrendCard`）。
- 風險：極低；注意在小螢幕與大字體下不被截斷；情境篩選（6 個以上選項）維持現有可橫向捲動 chip，不要塞進 SegmentedButton。

### 4. 新增/編輯對話框：選用的大數字鍵盤
- 借 glucose-records-tracker（MIT）`RecordApp.kt` 的 `NumericKeypad`/`KeyRow` 寫法。適合長輩單手輸入；但現有流程有拍照 OCR 與多種數值（血壓兩欄、體重小數點），要支援小數點與跳欄位才有意義，不是單純複製。
- 修改檔案：`ui/AddRecordDialog.kt`（需先讀該檔確認目前輸入方式，這次研究沒有深入驗證）。
- 風險：中（輸入驗證、小數點、血壓雙欄位都要重做；需要補單元測試）。建議先確認使用者是否真有輸入困擾再做。
- 若借用，需在檔頭註明「Adapted from ananyavaidya7/glucose-records-tracker, MIT License, Copyright (c) 2026 Ananya Vaidya」並保留 MIT 全文於專案 NOTICE。

### 5. 紀錄頁：日期分組 sticky header 與滑動刪除
- 做法：LazyColumn 的 `stickyHeader`（Foundation 內建）取代目前的普通 `item`；滑動刪除可用 M3 `SwipeToDismissBox`，但必須接現有的刪除確認對話框，不可直接刪除（刪除會連照片一併刪）。
- 修改檔案：`ui/RecordsScreen.kt`。風險：低；`stickyHeader` 仍屬實驗性 API，需 `@OptIn(ExperimentalFoundationApi::class)`。

### 6. 不建議做的項目（沒有缺口或不划算）
- 引入 Vico/其他圖表庫、placeholder-compose、komposto：理由見第 2 節。
- 深色模式：已有。
- Onboarding：個人自用，沒有明確需求；若要做，M3 `HorizontalPager` 自寫即可，不需借用 repo。
- 任何會自動標示「正常/偏高/偏低」顏色的元件：一律不採用；本次檢查的 SugarCare 類專案有此類判斷，剛好也因授權不明而淘汰。

## 4. 建置與測試結果

- 建置與測試結果：**未修改程式，未執行建置**。

若日後採用上述建議，驗證方式：
1. 純函式（最近資料點計算、空/載入狀態判斷）加 JUnit 單元測試，放 `app/src/test`，執行 `gradlew testDebugUnitTest`。
2. `gradlew assembleDebug` 與 `lintDebug` 通過，確認沒有新增依賴（`app/build.gradle.kts` 的 dependencies 不變）。
3. 在實機/模擬器人工檢查：淺色與深色、字型放大 200%、TalkBack 朗讀圖表描述、資料 0/1/2/大量筆數、剛開啟時不閃空狀態、系統「移除動畫」開啟時動畫停用。
4. 檢查新增圖片/文字不含「正常/偏高/偏低」等判斷詞；範圍色帶仍只依使用者設定。
5. 借用程式碼處的檔頭有來源與授權註明；若借用 MIT 專案，補上 NOTICE。
6. 此類 UI 修改依賴現有 `R8` 設定，release 版仍需另外實測一次（目前待辦清單已有此項）。

## 5. 授權小結

- 可借用：Apache-2.0（compose-samples、vico、placeholder-compose、komposto、architecture-templates、Compose-Ui-Templates）、MIT（ComposeCookBook、glucose-records-tracker）。借用單一元件寫法時需保留版權與授權標頭。
- 淘汰：無 LICENSE（Jetpack-Awesome-UI、SugarCare、meditrack）、GPL-3.0（Leafit）。
- 本報告不構成醫療建議；授權判讀為技術參考，若要正式發布 App，建議再請法務確認。

---

# 第二輪：視覺風格、新增流程、圖表、動畫、引導與設定頁

檢查日期：2026-10-20。同樣只用 `git clone --depth 1` 放在 `D:\Claude\scratch\ui_research\<repo名>`（全新資料夾），只用 Read/Grep 讀檔，未執行任何下載來的建置腳本。未修改 HealthTracker 原始碼、未 push、未部署、未發布 APK。第一輪已檢查的 12 個 repo 不重複。

## R2-0. 本輪重新確認的現況

- `AddRecordDialog.kt` 目前是 `AlertDialog`（內含 OutlinedTextField、DatePickerDialog／TimePicker、照片與 OCR、情境 FilterChip），本輪只讀到前 150 行，輸入欄位排版細節未逐行驗證。
- `SettingsScreen.kt` 用一排 FilterChip 切 7 個分組（個人資料、匯入資料、雲端同步、報告、備份與隱私、提醒與安全、關於），一次只顯示一組。
- `theme/Theme.kt` 已有 `AppCard`（白底細邊框無陰影）、品牌色、深淺色 ColorScheme、自訂 Shapes；視覺基礎已完整，本輪不建議大改風格。
- 專案設定：Kotlin 2.0.21、Compose BOM 2024.10.01、minSdk 26。以下「相容性」都以此為準。

## R2-1. 實際檢查的 Repository

| # | Repository | 網址 | 最後 commit | LICENSE | Kotlin / Compose | 結論 |
|---|---|---|---|---|---|---|
| 13 | Meko123456/heatmap-compose | https://github.com/Meko123456/heatmap-compose | 2026-10-16（1c9c942） | MIT | Kotlin 2.4.21、BOM 2026.06.01；函式庫只依賴 compose-ui / foundation | 採用：借「日曆熱度圖」寫法（約 200 行可改寫，不加依賴） |
| 14 | android/nowinandroid | https://github.com/android/nowinandroid | 2026-09-22（a49ed25） | Apache-2.0 | Kotlin 2.3.0、BOM 2025.09.01 alpha | 部分採用：只借設定頁區塊／載入狀態／onboarding 的寫法，不借架構 |
| 15 | android/compose-samples 的其他範例（JetLagged TimeGraph、Jetsnack、JetNews） | https://github.com/android/compose-samples | 2026-10-19（第一輪已登記） | Apache-2.0 | 官方最新 | 採用：借 JetLagged `TimeGraph` 的「時間＝比例位置」概念；Jetsnack 共享元素不適用 |
| 16 | theadityatiwari/RulerKit | https://github.com/theadityatiwari/RulerKit | 2026-03-30（6e28938） | MIT | Kotlin 2.0.21、BOM 2024.09.00（與本專案幾乎同版） | 僅參考：技術相容，但不建議用於血糖輸入 |
| 17 | meticha/material-3-expressive-catalog | https://github.com/meticha/material-3-expressive-catalog | 2026-10-14（d62dfcc） | Apache-2.0 | Kotlin 2.1.21、BOM 2025.06.02 alpha、minSdk 31 | 不採用：需升級 BOM 並提高 minSdk |
| 18 | zj565061763/compose-wheel-picker | https://github.com/zj565061763/compose-wheel-picker | 2024-11-28（b17a60e） | MIT | Kotlin 1.9.25、Compose 1.7.5 | 淘汰：約兩年沒更新 |
| 19 | hazlema/glucose-log | https://github.com/hazlema/glucose-log | 2026-09-16（5f4d696） | MIT | 傳統 View（EditText／Spinner），不是 Compose；Kotlin 2.0.21 | 淘汰：不是 Compose，程式碼無法直接借；只取設計想法 |
| 20 | kmuchiri/SugrNote | https://github.com/kmuchiri/SugrNote | 2026-10-17（5865e7d） | 根目錄無 LICENSE 檔 | 未驗證 | 淘汰（授權不明）；未讀其程式碼，也不可複製 |
| 21 | EngHusseinAkil/GlucoTime | https://github.com/EngHusseinAkil/GlucoTime | 2026-10-13（979791c） | 根目錄無 LICENSE 檔 | 未驗證 | 淘汰（授權不明）；未讀其程式碼 |

另外，搜尋結果出現但「未檢查」：PerfectGymCoach、CaffeineHealth、nomi（M3 Expressive 健康／健身類）、Juggluco 系列（mogluco、JugglucoNG）、Yet-Another-Habit-Tracker、runTrack、其他滾輪選擇器（PickTime-Compose、WheelPickerCompose、asleep-ai/compose-wheelpicker）、chargemap/compose-numberpicker。理由：只看過搜尋摘要，授權與程式碼未驗證；Juggluco 系屬 CGM 即時監測且授權偏 GPL 系，場景不同；滾輪選擇器對血糖數值輸入並非必要（見 R2-2 第 16 項）。這些不是判定不好，而是受「最多深入約 8 個 repo」的範圍限制。

## R2-2. 各候選的實際發現

### 13. heatmap-compose（MIT）
- 實際讀了 `ContributionHeatmap.kt`（112 行）、`HeatmapLayout.kt`（69 行）、`HeatmapLevel.kt`（24 行）。單一 `Canvas`，一欄一週、七列（週日到週六），`drawRoundRect` 畫格子，`detectTapGestures` + 純函式 `dayAt(x, y, width, endDay, weeks)` 做點選命中，`semantics { contentDescription }` 提供 TalkBack 描述；有單元測試（HeatmapLayoutTest、HeatmapLevelTest）。
- 優點：邏輯純函式化，容易寫單元測試；`maxCount` 可固定成絕對刻度。
- 注意：專案很新、0 顆星、單一維護者；顏色是 GitHub 綠色（寫死），不能照抄；對本 App 絕不能用「綠＝好」的語意。用法必須是「當天有幾筆紀錄」（記錄頻率），不是數值高低。
- 結論：不加依賴，改寫成 `ui/CalendarHeatmap.kt`，用 `MaterialTheme.colorScheme.primary` 以 4 個透明度階層上色。檔頭註明 Adapted from Meko123456/heatmap-compose（MIT License，Copyright (c) 2026 Merab Kochlamazashvili），並在 NOTICE 附 MIT 全文。

### 14. nowinandroid（Apache-2.0）
- 實際讀了 `feature/settings/impl/.../SettingsDialog.kt` 與 `feature/foryou/impl/.../ForYouScreen.kt` 的載入與 onboarding 段落。
- 設定頁：`SettingsDialogSectionTitle`（titleMedium、上 16dp 下 8dp）+ `Column(Modifier.selectableGroup())` + `Row(Modifier.selectable(role = Role.RadioButton))`（整列可點、RadioButton 的 onClick 設 null，無障礙寫法正確）；`AnimatedVisibility` 讓選項依條件展開。
- 載入：UiState 密封介面 `Loading / Success`，Loading 時顯示載入提示而不是空狀態；這正是第一輪「載入前閃空狀態」需要的寫法。
- Onboarding：只是列表最上面的一個卡片項目（標題 + 說明 + 選項 + 「完成」按鈕，`isDismissable` 控制按鈕可用），由 `OnboardingUiState.Shown/NotShown` 控制是否顯示，不是多頁精靈。這個做法很輕量，適合本 App。
- 缺點：多模組 + Hilt + 自家設計系統，不可整包搬；BOM 2025.09 alpha、Kotlin 2.3.0 比本專案新，不能直接複製帶 `hiltViewModel`、`NiaTheme` 的程式。
- 結論：只借寫法。檔頭有 Apache-2.0 標頭，借用處要保留。

### 15. compose-samples 其他範例（Apache-2.0）
- JetLagged `sleep/TimeGraph.kt`（124 行）：自訂 `Layout` + `ParentDataModifier`，把「開始時間／持續時間」換算成 0 到 1 的比例，再乘以寬度擺放；這是「一天中各時段」圖的核心概念。本 App 的點圖更簡單，直接用 Canvas 把時間換成 x 比例即可，不需要它的 Layout。
- Jetsnack：`SharedTransitionLayout`、`sharedBounds` 用在列表到詳細頁的共享元素轉場（需 `ExperimentalSharedTransitionApi`）；本 App 沒有「列表到詳細頁」的導覽，共享元素沒有實際用途。
- 在 compose-samples 全專案 grep 不到 `ModalBottomSheet`，所以底部面板沒有官方範例可借，但它是 Material3 內建元件，直接用即可。

### 16. RulerKit（MIT）
- 實際讀了 `RulerPicker.kt`（353 行）：Canvas 畫刻度、`awaitEachGesture` + `VelocityTracker` + `animateDecay` 做慣性、結束後吸附到最近刻度、`HapticFeedbackType.TextHandleMove` 觸覺回饋。技術上與本專案相容（Kotlin 2.0.21、BOM 2024.09）。
- 問題：2026-03 才有的新專案、8 顆星；顏色用 `Int` 設定而非 Compose `Color`／主題；每一影格都讀 `animatable.value` 觸發重組；設計目標是體重／身高這類「大範圍、不需精準」的值。血糖需要準確輸入單一數字，尺規慢又容易滑錯，對長輩更難；鍵盤輸入更快更準。
- 結論：不採用。若日後要為「體重」類型做尺規，再借其吸附與慣性邏輯，約 150 行。

### 17. material-3-expressive-catalog（Apache-2.0）
- 讀了結構與 import：展示 `LoadingIndicator`、`ToggleButton`、`ButtonGroup`、FAB Menu 等，需要 `@ExperimentalMaterial3ExpressiveApi`；`app/build.gradle.kts` 的 minSdk 為 31、BOM 為 2025.06.02 alpha。要用這些 API 必須先升級本專案 Compose BOM 並用 alpha 版 Material3，風險大於收益。目錄內也沒有 ModalBottomSheet 範例。
- 結論：現階段不採用，等 Material3 Expressive 進入穩定版 BOM 再重新評估。「連動按鈕群組」概念可用現有的 `SingleChoiceSegmentedButtonRow` 取代（第一輪建議 3）。

### 18. compose-wheel-picker（MIT）／19. glucose-log（MIT）
- compose-wheel-picker：實際讀了 `WheelPicker.kt`，以 LazyColumn 吸附實作，但 Kotlin 1.9.25、Compose 1.7.5、最後 commit 2024-11；且滾輪逐項滑動不適合輸入 3 位數血糖。淘汰。
- glucose-log：`EntryForm.kt` 是 View 版，不能借程式。可借的是產品想法：數字欄位用 64sp 大字、時間與備註預設收合（「+ 時間、情境與備註」）、預設用「現在」時間、驗證錯誤顯示在數字欄位。

## R2-3. 針對各畫面的具體建議（價值排序；★ 為前 3 項）

### ★1. 新增紀錄改成 ModalBottomSheet + 大字數字輸入 + 「用上一筆」快速填入
- 現況：`AddRecordDialog.kt` 是 AlertDialog，輸入框、日期時間、情境、照片、OCR 都擠在一個對話框，需要捲動。
- 做法（全部用 Material3 內建，免加依賴；沒有 repo 程式需要複製）：
  1. `ModalBottomSheet` + `rememberModalBottomSheetState(skipPartiallyExpanded = true)` 取代 AlertDialog；頂部是 `displayMedium` 大字數字輸入（`KeyboardType.Decimal`），單位標籤放旁邊；概念參考 glucose-log 的 64sp 輸入。
  2. 時間預設「現在」，點一下才展開日期／時間（`DatePickerDialog`、`TimePicker` 既有，不動）。
  3. 情境 chip 維持橫向捲動 FilterChip 列。
  4. 「用上一筆」：數字欄位下方放一個 `AssistChip`，文字為「上一筆 xxx mg/dL」，點下去只填入數字與情境，時間仍用現在。需從現有 Room 查最近一筆同類型紀錄。
  5. 保留拍照與 OCR 區塊，移到面板下方的「照片」折疊區。
- 預計改的檔案：`ui/AddRecordDialog.kt`（主體）、`ui/HealthTrackerRoot.kt`／`OverviewScreen.kt`／`RecordsScreen.kt`（呼叫端若傳參數需要同步）、repository 加 `latestOf(type)`。
- 風險：中。面板內再開 DatePickerDialog／相機／相簿要實測；`discardPhoto()`、onDismiss 行為需保持（取消要刪暫存照片）；軟鍵盤與面板高度的 `imePadding` 要測；`ModalBottomSheet` 在 BOM 2024.10 對應的 Material3 1.3 已可用（`skipPartiallyExpanded` 可用），但仍需實機確認。
- 價值：最高。這是每天都會用的流程，步驟越少越好。

### ★2. 日曆熱度圖（只顯示「有沒有記錄」，不顯示好壞）
- 做法：改寫 heatmap-compose 的 Canvas、命中測試、階層計算；資料為 `Map<Long(epochDay), Int(當天筆數)>`；階層用固定 `maxCount`（例如 4 筆以上同色）；顏色用 `colorScheme.primary` 的 4 級透明度，沒記錄的格子用 `outlineVariant`；點某一天顯示該日筆數並可跳到紀錄頁。
- 放哪裡：總覽「趨勢」卡片下方新增「記錄天數」卡片（約 12 到 20 週）。標題不要寫「達標」「連續」之類會製造壓力的字。
- 預計改的檔案：新增 `ui/CalendarHeatmap.kt`（含純函式，如 `dayAt`、`levelFor`）、`ui/OverviewScreen.kt`、單元測試放 `app/src/test`、NOTICE。
- 風險：低到中。需要查最近約 140 天的每日筆數（Room 加 group by 日期查詢，或記憶體分組）；深色模式透明度對比要實測；TalkBack 只給摘要（例如「最近 20 週中有 N 天有記錄」），不要逐格朗讀；絕不可改成依數值著色。
- 價值：高，視覺差異明顯，且完全不涉及醫療判斷。

### ★3. 每日分布點圖（一天內各時段，每個點一筆）
- 做法：自繪 Canvas，x 軸 0 到 24 小時（概念借 JetLagged `TimeGraph.kt` 的「時間占一天的比例＝水平位置」），y 軸為最近 7 或 14 天各一列；每筆紀錄畫一個小圓點，顏色統一用 `recordAccent`，不依數值變色。若使用者有設定目標範圍，只在圖例用文字顯示「落在您設定範圍內：N／M 筆」，圖上不變色。點選圓點顯示數值與時間，可重用第一輪「點選氣泡」與最近點計算函式。
- 預計改的檔案：新增 `ui/DailyDistributionChart.kt`、`ui/OverviewScreen.kt`（或 RecordsScreen 頂部）；純函式（時間轉比例、最近點）放 `OverviewMath` 並加單元測試。
- 風險：中。資料多時圓點重疊（用半透明圓點）；時區要用 `ZoneId.systemDefault()`，與 `AddRecordDialog` 一致；要有 contentDescription 摘要。
- 價值：中高，補上折線圖看不到的「一天內哪個時段有記錄／數值分布」。

### 4. 區間統計小卡（只用使用者設定的範圍）
- 做法：總覽加一排小卡，內容為「平均、最高、最低、筆數」與（若使用者有設範圍）「範圍內／低於下限／高於上限的筆數」，全部以中性色（`surfaceVariant`、`onSurface`）顯示，不使用紅綠、不使用「正常／偏高」文字，標籤用「低於您設定的下限」這種中性描述。
- 預計改的檔案：`ui/OverviewScreen.kt`；統計純函式若已存在（設定頁說明提到「統計落在範圍內的比例」）就沿用，沒有就新增並測試。
- 風險：低；文案要審查避免暗示好壞。無需借 repo。

### 5. 數字進場／滾動動畫
- 做法：內建 `AnimatedContent` + `slideInVertically/slideOutVertically` 做 `GlucoseHero` 大數字滾動；折線圖用 `Animatable` 畫線進場（第一輪已列）。兩者都要在系統「移除動畫」時直接顯示最終值。共享元素（Jetsnack）本 App 沒有列表到詳細的導覽，不需要；骨架載入（shimmer）也不需要，載入是本機毫秒級，用 nowinandroid 式的 Loading／Success 狀態即可（見第一輪建議 2）。
- 預計改的檔案：`ui/OverviewScreen.kt`。風險：低。

### 6. 首次使用引導（輕量版）
- 做法：借 nowinandroid 的作法，不做多頁精靈，改在總覽最上方放一張一次性「歡迎」卡：一行說明「這個 App 不判斷數值好壞」、一個可略過的「設定您的目標範圍（選填）」按鈕、一個「記錄第一筆」按鈕；用 DataStore 存一個 `onboardingDismissed` 布林值，狀態用 `Loading/Shown/NotShown`，載入前不顯示，避免閃現。
- 預計改的檔案：`ui/OverviewScreen.kt`、`data/prefs/UserSettingsRepository`、可新增 `ui/WelcomeCard.kt`（或併入 `EmptyState.kt`）。風險：低；個人自用 App 是否需要由使用者決定。

### 7. 設定頁分組寫法
- 現況用橫向 FilterChip 切 7 組，一次只看一組，可用，但第一次使用不容易發現全部分組。
- 做法（借 nowinandroid 的 `SettingsDialogSectionTitle` 與整列 `selectable`）：個人資料內以 `titleMedium` 小標題分區（基本資料／體重與身高／血糖目標範圍），性別的 3 個 FilterChip 換成 `SingleChoiceSegmentedButtonRow`；其餘分組不變。
- 預計改的檔案：`ui/SettingsScreen.kt`。風險：低；不建議整頁重排成長列表，以免破壞現有使用習慣。

### 8. 不建議做的項目
- 採用 Material 3 Expressive（需 alpha BOM、minSdk 31）：等穩定版。
- 尺規／滾輪式血糖輸入：慢、不準，淘汰。
- 任何自動標「正常／偏高／偏低」的圖表或熱度圖：一律不採用；熱度圖只能用記錄筆數上色。
- 引入圖表或熱度圖函式庫：上述三項圖表都能約 100 到 200 行自繪，不加依賴。

## R2-4. 價值排序總表

| 排序 | 項目 | 價值 | 風險 | 借用來源 | 主要檔案 |
|---|---|---|---|---|---|
| ★1 | 新增紀錄改 ModalBottomSheet + 大數字 + 用上一筆 | 最高 | 中 | Material3 內建；想法參考 glucose-log（MIT） | AddRecordDialog.kt |
| ★2 | 日曆熱度圖（記錄筆數） | 高 | 低到中 | heatmap-compose（MIT） | 新增 CalendarHeatmap.kt、OverviewScreen.kt |
| ★3 | 每日分布點圖 | 中高 | 中 | 概念：JetLagged TimeGraph（Apache-2.0） | 新增 DailyDistributionChart.kt |
| 4 | 區間統計小卡 | 中 | 低 | 無 | OverviewScreen.kt |
| 5 | 數字滾動／進場動畫 | 中低 | 低 | Compose 內建 | OverviewScreen.kt |
| 6 | 輕量 onboarding 卡 | 低到中 | 低 | nowinandroid 概念 | OverviewScreen.kt、prefs |
| 7 | 設定頁小標題與分段按鈕 | 低 | 低 | nowinandroid 概念 | SettingsScreen.kt |

## R2-5. 建置與測試（第二輪）

- 建置與測試結果：**未修改程式，未執行建置**。
- 日後若採用，驗證方式同第一輪第 4 節，另加：熱度圖與點圖的純函式（命中測試、階層、時間換算）加 JUnit；`ModalBottomSheet` 在實機測 IME、相機／相簿回返、取消時暫存照片被刪；深淺色、字型 200%、TalkBack；`app/build.gradle.kts` 的 dependencies 不變；所有新增文字與顏色不含好壞判斷。
- 授權小結：本輪可借用的是 heatmap-compose（MIT）、nowinandroid 與 compose-samples（Apache-2.0）；RulerKit／compose-wheel-picker／glucose-log 雖為 MIT 但不建議借；SugrNote、GlucoTime 無 LICENSE 淘汰。借用處需在檔頭註明來源與授權並附 NOTICE。本報告不構成醫療或法律建議；授權判讀為技術參考，正式發布前建議再請專業人士確認。
