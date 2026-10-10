# GPS Tracker 網頁儀表板

純靜態（前端）儀表板，會讀取本資料夾 `data/` 內的紀錄並顯示地圖、分析與圖表。
全程 **0 元、免金鑰**：圖資使用免費的 Esri 衛星影像與 AWS Terrarium DEM（MapLibre GL JS）。

## 一、啟用 GitHub Pages（一次性）
1. 到 GitHub repo → **Settings → Pages**。
2. **Build and deployment → Source** 選 **GitHub Actions**。
3. 推送後（或手動執行 workflow「Deploy GPS Tracker Web」）即會部署。
4. 網址：`https://<你的帳號>.github.io/<repo>/`（本專案為 `https://wahaha232.github.io/ANDROID-APP-DEV/`）。

## 二、建立上傳用的 Token（一次性）
1. GitHub → **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**。
2. **Repository access**：只選 `ANDROID-APP-DEV`（本 repo）。
3. **Permissions → Repository permissions → Contents: Read and write**。
4. 產生後複製 token，貼到 App：**設定 → 網頁儀表板 → GitHub Token**。

## 三、App 內設定
- 啟用「自動上傳到網頁」
- 儲存庫：`wahaha232/ANDROID-APP-DEV`
- 分支：`main`
- 資料夾：`gpstracker-web/data`
- GitHub Token：（貼上 PAT）
- 僅 Wi-Fi 上傳（建議開啟）

記錄結束後，App 會自動把該筆紀錄寫成 `data/<trackId>.json` 並更新 `data/manifest.json`。

## 四、手動測試
- 在任一趟紀錄的明細頁按「上傳到網頁」。
- 成功後重新整理儀表板即可看到。

## 注意
- 資料會以 JSON 形式存在 repo（公開）。若不想公開，請改用需要後端的方案（例如 Cloudflare Worker）。
- 儀表板以 `raw.githubusercontent.com` 讀取資料，最即時；Pages 只負責放前端程式。
