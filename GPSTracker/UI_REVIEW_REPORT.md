# GPS Tracker：開源 UI 資源搜尋與選用報告

日期：2026-10-20　專案：`GPSTracker/`（Android App，Jetpack Compose＋Material 3＋MapLibre）
範圍：只針對 GPS Tracker 的 UI。遵守限制：**未推送遠端、未部署、未發布 APK、未安裝到手機**。

---

## 1. 先看現有專案：缺什麼

專案現況（實際讀程式確認）：Compose BOM `2024.10.01`、Kotlin `2.0.21`、Material 3（淺色／深色主題都有）、`navigation-compose`、MapLibre `11.6.1`。畫面有首頁、記錄、歷史、詳情、設定、年度回顧、3D 飛越。

| # | 缺少的 UI 功能 | 依據 | 處理 |
|---|---|---|---|
| 1 | **詳情頁沒有海拔／速度剖面圖**（網站有，App 沒有） | `TrackDetailScreen` 只有地圖、顏色選項、亮點、分段，沒有任何折線圖 | **已實作**（見 §4） |
| 2 | 整個 UI 沒有動畫與轉場 | 在 `ui/` 搜尋 `AnimatedVisibility`、`animate*AsState`、`Crossfade` 皆無 | 建議，未實作 |
| 3 | 歷史清單沒有滑動刪除、下拉重新整理 | 搜尋 `SwipeToDismiss`、`PullToRefresh` 皆無 | 建議，未實作 |
| 4 | 年度回顧的圖表是用 `Box` 拼的長條，沒有數值提示 | `RecapScreen.ChartCard` | 建議，未實作 |

## 2. 實際檢查的 Repository

初始候選 6 個，加上為「圖表」缺口另外搜尋的 2 個。檢查方式：用 GitHub API 取得授權、維護狀態、是否封存；對重點項目再看檔案結構、版本需求或原始碼標頭。**沒有只憑 README 或 Stars 判斷。**

| Repository | LICENSE | 維護狀態 | 實際檢查了什麼 | 結論 |
|---|---|---|---|---|
| [android/compose-samples](https://github.com/android/compose-samples)（初始） | Apache-2.0 | 2026-10-19 仍在更新 | 列出 `JetLagged` 的圖表檔案（`TimeGraph.kt`、`HeartRateGraph.kt`、`SleepBar.kt`），確認原始檔有 Apache-2.0 標頭 | **不複製程式碼**；只當作「Canvas 手畫圖表」寫法的方向參考。自行實作 |
| [Gurupreet/ComposeCookBook](https://github.com/Gurupreet/ComposeCookBook)（初始） | MIT | 2026-10-03 仍在更新 | 列出檔案樹，有 `animations/canvas` 等動畫示範 | **未採用**：現有 Material 3 元件已足夠；動畫缺口（#2）用 Compose 內建 API 即可，不需要引入 |
| [Hiten24/Compose-Ui-Templates](https://github.com/Hiten24/Compose-Ui-Templates)（初始） | Apache-2.0 | **2022-03-30 後沒更新** | 只看授權與維護狀態 | **淘汰**：停更太久，與目前 Compose 版本相容性存疑 |
| [android/architecture-templates](https://github.com/android/architecture-templates)（初始） | Apache-2.0 | 2026-10-09 | 只看授權與說明 | **不適用**：是架構範本，不含 UI 元件 |
| [Trendyol/komposto](https://github.com/Trendyol/komposto)（初始） | Apache-2.0 | 2026-08-17 | 只看授權與說明（設計系統，20+ 元件） | **未採用**：等於另一套設計系統，會與現有 Material 3 主題重疊，違反「不整套替換 UI」 |
| [samadtalukder/Jetpack-Awesome-UI](https://github.com/samadtalukder/Jetpack-Awesome-UI)（初始） | **無 LICENSE 檔（API 回 404）** | 2025-05-21、5 顆星 | 確認沒有授權檔 | **淘汰**：授權不明，依規則不得整合 |
| [patrykandpatrick/vico](https://github.com/patrykandpatrick/vico)（另外搜尋） | Apache-2.0 | 2026-10-18，最新 `v3.3.1` | 讀它的 `libs.versions.toml`：3.x 需要 Kotlin `2.4.21`＋Compose `1.12.2`；最新穩定的 2.x（`v2.5.2`）需要 Kotlin `2.4.0`＋BOM `2026.06.00` | **淘汰**：我們是 Kotlin 2.0.21＋BOM 2024.10.01，引入會被迫升級整個專案的 Kotlin 與 Compose，等於為了一張圖大改建置，違反「不新增大型依賴」 |
| [codeandtheory/YCharts](https://github.com/codeandtheory/YCharts)（另外搜尋） | Apache-2.0 | **已封存**（2024-08-07） | 只看授權與封存狀態 | **淘汰**：已封存，不再維護 |

## 3. 選用結論

沒有一個候選資源同時滿足「授權清楚、與現有 Kotlin／Compose 版本相容、不用新增大型依賴」。因此：

- **不新增任何第三方相依套件。**
- 缺口 #1（剖面圖）用最小化的自行實作補上：一張約 140 行的 `Canvas` 折線＋面積圖，沿用現有 `RouteAnalysis` 的海拔／速度資料，不改任何核心功能。
- 缺口 #2～#4 影響較小、也不需要外部資源（Compose 內建就有對應 API），列為後續建議，這次不動。

## 4. 實際修改的檔案（只在本機，未推送）

| 檔案 | 變更 |
|---|---|
| `core/analysis/ProfileSeries.kt`（新增） | 純函式：把長資料縮成最多 240 個點（分段取平均）、手指位置轉資料索引、數值正規化。不依賴 Compose，可單元測試 |
| `ui/detail/ProfileChartCard.kt`（新增） | 剖面圖卡片：海拔／速度切換（沒有資料的不顯示，兩者都沒有整張卡片不出現）、折線＋漸層面積、格線、左右拖曳或按住讀取該處數值（只攔截水平拖曳，不擋頁面上下捲動） |
| `ui/detail/TrackDetailScreen.kt` | 在地圖與「路線顏色」之間插入 `ProfileChartCard`（有海拔或速度資料才顯示） |
| `res/values/strings.xml` | 新增 7 個字串（剖面圖標題、海拔、速度、數值格式、範圍、操作提示） |
| `test/.../ProfileSeriesTest.kt`（新增） | 5 項測試：短資料原樣、長資料分段平均、奇怪長度也每筆只算一次、手指索引夾限、正規化與平線置中 |

## 5. 建置與測試結果

- `./gradlew assembleDebug testDebugUnitTest`：**成功**。
- 單元測試：**183 項、0 失敗、0 略過**（含新增的 5 項）。
- **尚未在實機驗證剖面圖的外觀與拖曳手感**：依「不部署」的限制，我沒有安裝到手機。圖表是否好看、數值提示是否順手，需要你同意後安裝到手機實際看，或你自行安裝建置結果。

## 6. 後續建議（未實作，需要你決定）

1. **動畫（#2）**：用 Compose 內建的 `AnimatedVisibility` 讓橫幅（模擬位置警告、自動暫停等）淡入淡出，用 `animateFloatAsState` 讓進度與數字變化平順。不需要外部資源。
2. **歷史清單（#3）**：Material 3 內建 `SwipeToDismissBox` 做滑動刪除（要配合現有的刪除確認，避免誤刪）、`PullToRefreshBox` 做下拉重新整理。
3. **年度回顧圖表（#4）**：把現有 `Box` 長條圖改成同一種 `Canvas` 風格並加數值提示，與剖面圖一致。
4. 若之後 GPS Tracker 升級到 Kotlin 2.4／Compose 1.12 以上，再重新評估 Vico（授權與維護狀況都不錯，是唯一值得再看的圖表函式庫）。

## 7. 未檢查／限制

- 初始清單中的 Komposto、Hiten24、architecture-templates、ComposeCookBook 只檢查了授權、維護狀態與檔案結構，**沒有逐一閱讀元件原始碼**；結論建立在「用途不符或停更或會與現有主題重疊」，不是程式碼品質評比。
- compose-samples 只確認授權標頭與檔案結構，沒有複製或改寫其程式碼。
- 搜尋範圍有限（沒有大量下載 repo，依你的要求只針對「缺的功能」搜尋）；地圖、相機、運動追蹤專用 UI 沒有另外找，因為現有的 MapLibre 地圖與 CameraX 畫面沒有被判定為缺口。
