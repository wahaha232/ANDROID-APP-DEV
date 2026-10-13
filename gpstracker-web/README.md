# GPS Tracker 網頁儀表板

純靜態（前端）儀表板，讀取本資料夾 `data/` 內的紀錄，顯示地圖、分析圖表、3D 回放與 GPX 匯出。
全程 **0 元、免金鑰**：圖資使用免費的 Esri 衛星影像與 AWS Terrarium DEM（MapLibre GL JS）。

## 網址
- 儀表板（首頁）：`https://wahaha232.github.io/ANDROID-APP-DEV/gpstracker-web/`（累積活動摘要與資料更新時間 → 五大挑戰進度卡 → 最近活動；五大挑戰＝太陽系、地球、台灣、NASA Voyager、共享成就中心，彼此獨立、同時累積）
- 3D 回放：`.../gpstracker-web/flythrough.html?track=<trackId>`
- 競賽中心：`.../gpstracker-web/challenges.html`（成就徽章、個人最佳、期間挑戰、步數統計（總覽／每日週月趨勢／達標／來源）、里程王、虛擬環遊、歷史上的今天、攝影挑戰、距離賓果、軌跡藝術）
- 太陽系征服計畫：`.../gpstracker-web/solar.html`（九大天體關卡 + 隱藏冥王星、航海家追逐；關卡門檻集中於 `solar-levels.js`，航海家資料由 `.github/workflows/voyager-sync.yml` 每日同步 NASA/JPL Horizons 至 `data/voyager.json`）
- 地球征服計畫：`.../gpstracker-web/earth.html`（子挑戰：全球里程征服、世界探索任務、地球垂直極限挑戰、地球軌道與逃逸任務；門檻集中於 `earth-levels.js`，里程規則重用 `solar-core.js`）
- 紀錄管理：`.../gpstracker-web/records.html`（瀏覽全部紀錄、下載 JSON/GPX、編輯備註、刪除；編輯/刪除需在頁面設定 GitHub Token，只存於瀏覽器 localStorage）
- 台灣征服計畫：`.../gpstracker-web/taiwan.html`（台灣里程征服、環島挑戰、地理探索、縣市制霸、高山與垂直挑戰、四極點；門檻/概略邊界集中於 `taiwan-levels.js`，里程規則沿用 `solar-core.js`/`earth-core.js`）
- 全挑戰同步進度：`.../gpstracker-web/progress.html`（彙整台灣／地球／太陽系／航海家所有目標，同時顯示目前累計、目標、完成比例、剩餘與超越距離；里程全站只計一次）

> 本 repo 的 GitHub Pages 已啟用並以「分支 main / 根目錄」發佈，因此本資料夾會自動被
> 發佈到 `/gpstracker-web/`。**不需要**額外設定 Pages（也移除了先前的 GitHub Actions Pages workflow，避免兩種來源互相覆蓋）。

## 挑戰進度與資料狀態
- 里程只計一次：全站唯一的「有效活動」定義是 `SOLAR_CORE.validTracks`（去重、距離與時間皆 > 0、排除均速 > 1200 km/h 的 GPS 異常）；首頁、競賽中心、各征服計畫與全挑戰進度都用它。
- 五大挑戰的共用邏輯與卡片在 `gt-challenges.js`（`GTCH`），首頁與探索挑戰共用；百分比可超過 100%，只有進度條填滿寬度封頂。
- NASA Voyager 參考資料（`data/voyager.json`，每日由 `voyager-sync.yml` 更新）：讀取失敗或內容不合理時，網頁沿用瀏覽器保存的最後一次有效值並標示「更新失敗／資料過期（超過 2 天）」與最後成功時間，不會歸零；排程端的合理性檢查不通過時不會覆寫檔案。
- 自動化測試：`node --test gpstracker-web/tests/*.test.js`（Node 24 請逐一列出檔案，不能直接傳資料夾）。

## 一、建立上傳用的 Token（一次性）
1. GitHub → **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**。
2. **Repository access**：只選 `ANDROID-APP-DEV`。
3. **Permissions → Repository permissions → Contents: Read and write**。
4. 產生後複製 token，貼到 App：**設定 → 網頁儀表板 → GitHub Token**。

## 二、App 內設定
- 啟用「自動上傳到網頁」
- 儲存庫：`wahaha232/ANDROID-APP-DEV`
- 分支：`main`
- 資料夾：`gpstracker-web/data`
- GitHub Token：（貼上 PAT）
- 僅 Wi-Fi 上傳（建議開啟）

記錄結束後，App 會自動把該筆紀錄寫成 `data/<trackId>.json` 並更新 `data/manifest.json`。

## 三、手動測試
- 在任一趟紀錄的明細頁按「上傳到網頁儀表板」。
- 成功後重新整理儀表板即可看到。

## 注意
- 資料以 JSON 形式存在 repo（公開）。若不想公開，請改用需要後端的方案（例如 Cloudflare Worker）。
- 儀表板以「同源相對路徑」`data/` 讀取資料（由 GitHub Pages CDN 提供，較快且免跨網域）；只有指定 `?repo=owner/name` 時才改用 `raw.githubusercontent.com`。
