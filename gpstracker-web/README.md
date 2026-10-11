# GPS Tracker 網頁儀表板

純靜態（前端）儀表板，讀取本資料夾 `data/` 內的紀錄，顯示地圖、分析圖表、3D 回放與 GPX 匯出。
全程 **0 元、免金鑰**：圖資使用免費的 Esri 衛星影像與 AWS Terrarium DEM（MapLibre GL JS）。

## 網址
- 儀表板：`https://wahaha232.github.io/ANDROID-APP-DEV/gpstracker-web/`
- 3D 回放：`.../gpstracker-web/flythrough.html?track=<trackId>`
- 競賽中心：`.../gpstracker-web/challenges.html`（成就徽章、個人最佳、期間挑戰、里程王、虛擬環遊、歷史上的今天、攝影挑戰、距離賓果、軌跡藝術）

> 本 repo 的 GitHub Pages 已啟用並以「分支 main / 根目錄」發佈，因此本資料夾會自動被
> 發佈到 `/gpstracker-web/`。**不需要**額外設定 Pages（也移除了先前的 GitHub Actions Pages workflow，避免兩種來源互相覆蓋）。

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
