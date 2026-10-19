# HealthTracker（個人健康紀錄 App）

自用 Android App（之後搭配網站）。紀錄體重、體脂、血糖、血壓。

## 已確認的前提（2026-10-16 使用者回覆）
- 體脂計：小米體脂計 S400（米家／Xiaomi Home 資料來源，第 5 天驗證方案）
- 使用範圍：僅限本人自用（單人、無多使用者／分享需求；雲端帳號只需單一使用者）
- 血糖機型號：**待使用者補充**（第 1 天三題之一，輸入方式目前只有手動；型號決定是否能用 OCR／藍牙）

> 注意：先前排程的第 1 天（Phase 0 稽核、資料模型、API 契約、米家驗證方案）只送出過提醒，
> 沒有留下產出。第 2 天依本檔的資料模型直接開工，Phase 0 文件待補。

## 第 2 天進度（Phase 1 前半：骨架、本機資料庫、個人設定）
- Kotlin 2.0.21 / AGP 8.6.1 / Compose BOM 2024.10.01（與 GPSTracker 同版本）
- 套件：`com.startinsnow.healthtracker`（debug 加 `.debug` 後綴）
- Room：`health_records`（UUID 主鍵、軟刪除、`syncState` 供日後同步），schema 匯出至 `app/schemas`
- DataStore：個人設定（名稱、性別、出生年、身高、目標體重）
- UI：底部導覽（紀錄／設定）、手動新增紀錄（含範圍檢查）、BMI 顯示
- 隱私：`allowBackup=false`，資料庫與設定不進系統雲端備份／裝置轉移

## 驗證（2026-10-19）
- `:app:testDebugUnitTest :app:assembleDebug` 建置成功；單元測試 8 項全過；Room schema 1.json 已產生
- debug APK：app/build/outputs/apk/debug/app-debug.apk，已安裝到 POCO X6 Pro 實測（MIUI 需開「透過 USB 安裝」）
- 實測通過：新增體重（705 被擋、70.5 存入）、重啟後紀錄仍在、身高 17 被擋／170 存入、BMI 24.4 正確、設定重啟後保留
- 實測發現並已修：數字欄位原本跳出注音鍵盤 → 改數字鍵盤；「血壓」選項被擠成直排 → 改 FlowRow 換行
- 測試資料已用 pm clear 清除
- 未測：血糖／血壓／體脂率的新增畫面、刪除按鈕

## 尚未做（後續天數）
- 第 3 天：拍照、OCR、人工確認　第 4 天：雲端登入與基本同步　第 5 天：體重資料整合（S400）

## 建置
```
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```
需要 JDK 17（JAVA_HOME）、`local.properties` 的 `sdk.dir=D:/Claude/android-sdk`（不進版控）。
