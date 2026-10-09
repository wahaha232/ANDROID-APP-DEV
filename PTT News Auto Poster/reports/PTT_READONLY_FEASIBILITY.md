# PTT 新聞自動 PO 文工具 — Phase 1 唯讀技術驗證報告

> 本階段僅執行 READ / ANALYZE / TEST。未發文、未回文、未寄信、未修改任何 PTT 資料。
> 未送出任何帳號或密碼，未嘗試繞過 CAPTCHA、登入限制或站方防護。
> 專案目錄在本階段前為空；本報告是本階段唯一新增到專案的檔案（其餘測試腳本與日誌置於系統暫存目錄，未寫入專案）。

---

## 1. 測試環境與時間

| 項目 | 值 |
| --- | --- |
| 作業系統 | Microsoft Windows 10 專業版 (10.0.19045) |
| Shell | Windows PowerShell 5.1.19041.7725 |
| Node.js | v24.19.0（內建 WHATWG `WebSocket`，用於 WebSocket 實測） |
| curl | Windows 系統內建 curl.exe |
| SSH client | C:\Windows\System32\OpenSSH\ssh.exe（僅用於能力確認，未登入） |
| 測試時間 | 2026-10-09 16:36–16:44 UTC |
| 工作目錄 | `D:\VS CODE\PROJECT\PTT News Auto Poster`（測試前為空目錄） |

測試腳本與日誌存放於 `%LOCALAPPDATA%\Temp\kilo\ptt_phase1\`，未觸及專案檔案。日誌僅含連線位元組與錯誤訊息，**不含任何帳密、Cookie 或 Session Token**。

---

## 2. 查核過的官方資料及網址

全部為**官方**來源（未採用未經官方確認的第三方文件）：

| 來源 | 內容 | 結果 |
| --- | --- | --- |
| https://www.ptt.cc/index.html | 「第三方程式連線」卡片 | 官方明列 SSH 與 WebSocket 連線 |
| https://www.ptt.cc/index.html（頁內 JS） | 連線字串 | `ssh bbs@ptt.cc`（Big5）、`ssh bbsu@ptt.cc`（UTF-8）`(Port 22 / 8888)`；`WebSocket 連線：wss://ws.ptt.cc/bbs` |
| https://term.ptt.cc/ | 官方網頁終端機 | 官方卡片描述為「基於網頁 WebSocket 技術開發的 BBS 終端機介面，支援 ANSI 全彩顯示」（頁面本身僅為 JS app shell） |
| https://github.com/ptt/pttbbs | 官方 BBS 原始碼 | `mbbsd/` 含登入、看板、閱讀、發文模組 |
| https://raw.githubusercontent.com/ptt/pttbbs/master/daemon/wsproxy/README.md | WS→telnet 代理說明 | 官方；README 標示實驗性；sample nginx 設定 |
| https://raw.githubusercontent.com/ptt/pttbbs/master/daemon/wsproxy/wsproxy.lua | WS 代理實作 | **關鍵**：只轉送 `binary` 訊框到 BBS，`text` 訊框被丟棄 |
| https://github.com/ptt/pttbbs/tree/master/daemon/boardd | 看板文章服務 | gRPC `pttbbs.api.BoardService` |
| https://raw.githubusercontent.com/ptt/pttbbs/master/daemon/boardd/board.proto | 服務定義 | 僅 `Board / List / Hotboard / Content / Search` 等**讀取** RPC |
| https://github.com/ptt/pttbbs/wiki（SSH、wsproxy、logind） | 官方 wiki | 伺服器端設定說明 |

### 2.1 官方是否提供發文介面（重要結論）

- 官方 gRPC 服務 `pttbbs.api.BoardService`（`daemon/boardd`）**只有唯讀 RPC**：`Board`、`List`、`Hotboard`、`Content`、`Search`，**沒有** Post / Write / Push / Create 等寫入 RPC。`boardd` 描述為「看板文章服務 (for Web)」，供唯讀前端 pttweb 使用。
- 官方文件中**找不到**任何可供第三方合法使用的「發文 API」。發文路徑只存在於互動式終端程式 `mbbsd/post.c`（即 SSH／Telnet／WebSocket 的互動工作階段內）。
- 因此：**「可合法使用的公開發文 API」= 不存在（依官方文件確認）**。不得因存在 WebSocket 就假設有發文 API。

---

## 3. SSH 與 WebSocket 的實測結果

### 3.1 DNS 與 TCP/TLS

| 目標 | 結果 |
| --- | --- |
| DNS `ptt.cc` | 6 筆 A：140.112.172.1–5、140.112.172.11 |
| DNS `ws.ptt.cc` | 同一組 140.112.172.x（**由 PTT 自架**，非第三方） |
| DNS `term.ptt.cc` | 104.21.30.106、172.67.172.193（Cloudflare） |
| TCP `ptt.cc:22` | **成功**（RemoteAddress 140.112.172.3） |
| TCP `ptt.cc:443` | **成功** |
| TCP `ws.ptt.cc:443` | **成功** |
| TCP `ptt.cc:8888` | **失敗/逾時**（本網路環境） |
| TLS `ws.ptt.cc:443` | 憑證驗證通過（`ssl_verify_result=0`）；純 GET 回 403（WS 專用端點，預期） |
| HTTPS `term.ptt.cc` | HTTP 200 |

### 3.2 WebSocket 握手（`wss://ws.ptt.cc/bbs`）

以原始 HTTP Upgrade 與內建 WebSocket 兩種方式實測，結果一致：

| Request `Origin` | 結果 |
| --- | --- |
| `https://term.ptt.cc` | **101 Switching Protocols**（成功；`sec-websocket-accept` 正常） |
| 無 Origin | 403 Forbidden |
| `https://www.ptt.cc` | 403 Forbidden |
| `https://evil.example` | 403 Forbidden |

- 只有 `Origin: https://term.ptt.cc` 可通過。**注意**：官方 wsproxy README 的範例白名單為 `http(s)://www.ptt.cc`、`app://`、`127.*`，與實際生產環境不同 → 證明**必須以實測為準，不能照抄範例**。

### 3.3 未登入時可讀取的公開資訊（成功）

連線成功後，伺服器以 **binary** 訊框推送初始畫面。前兩個訊框合計 4,397 bytes，經 telnet IAC 與 ANSI 去除後以 **Big5** 解碼，確認為官方歡迎畫面：

- 站名橫幅「批踢踢實業坊 / ptt.cc / 140.112.172.11」
- 即時公開資訊：「目前有【24560】位訪客在 批踢踢實業坊…」（多次連線數值變動，確為即時資料）
- 登入提示：「請輸入代號，或以 guest 參觀，或以 new 註冊:」

> 註：初始 binary 訊框開頭夾帶 `HTTP/1.1 200 OK\r\n\r\n` 前導字串（wsproxy 行為），後續即為正常 BBS 畫面資料。

### 3.4 輸入協定（關鍵實測）

對照 `wsproxy.lua` 並實測驗證：

- 送出 **binary** 訊框 `"a"` → 伺服器 1 byte 回顯（**輸入有送達**）。
- 送出 **text** 訊框 `"b"` → **無任何回顯**（被代理丟棄）。

→ **結論：WebSocket 用戶端必須使用 binary 訊框傳送按鍵；text 訊框會被 wsproxy 靜默丟棄。** 這是本次驗證最重要的協定發現。

### 3.5 SSH 連線

- `ptt.cc:22` 讀取到 SSH 伺服器 banner：`SSH-2.0-bbs-sshd`（讀 banner 後即關閉，未進行認證）。
- SSH 屬需認證的互動工作階段，**無匿名存取**；本階段未輸入任何帳密、未嘗試登入（依規則 B5 停止）。
- `ptt.cc:8888` 由本網路無法連線（逾時）；官方頁面標示 SSH 埠為 22/8888。

---

## 4. 登入需求與站方限制

1. **看板瀏覽需工作階段**：登入提示提供三種入口：輸入代號（需本人帳號）、`guest`（免帳號訪客）、`new`（註冊）。
2. **guest 實測結果（失敗，站方限制）**：以 binary 送出 `guest` 後，伺服器回覆：
   > `guest正在檢查帳號與密碼... 抱歉，目前已有太多 guest 在站上。`
   
   即 **guest 並行上線數已達上限**，站方拒絕新的訪客工作階段。這是站方容量限制，非用戶端錯誤。
3. 因 guest 被拒、且無本人帳號授權，**本階段在看板列表之前停止**（符合規則 B5：需要帳號登入時先停止並報告）。
4. 未測試批次登入、未連續重試、未製造大量請求。
5. 官方 `docs/register.md` 顯示註冊需通過 `manual` / `email` / `sms` 驗證；原始碼有 `mbbsd/captcha.c`，但**官方未公布**明確的登入／請求頻率上限數字 → 標記為未驗證。

---

## 5. 看板及文章唯讀能力

| 能力 | 狀態 | 說明 |
| --- | --- | --- |
| 未登入讀取「登入前公開畫面」 | **通過** | 歡迎畫面與即時訪客數可讀 |
| guest 訪客工作階段 | **失敗（站方限制）** | 「目前已有太多 guest 在站上」 |
| 讀取看板列表 | **未驗證** | 卡在 guest 上限 / 需本人帳號登入；依規則停止 |
| 讀取文章標題／內容 | **未驗證** | 同上，未進入任何看板 |
| 發文介面 | **不存在（官方確認）** | 唯讀 gRPC 無寫入 RPC；發文僅存在於互動終端 |

---

## 6. 可取得的文章識別資訊

- **本階段未驗證**：因未能成功讀取任何看板／文章，無法實際取得文章識別資訊（標題、作者、日期、文章代碼、`M.xxxxxxxxxx.A.xxx` 等）。
- 依官方原始碼可推論（**僅為推論，未實測**）：唯讀資料可由 `daemon/boardd` 的 `Content` / `List` / `Search` RPC 提供，對應 Web 版 pttweb 之文章頁。此推論**未經實測**，不得視為 Phase 1 通過項目。

---

## 7. 故障與安全測試（模擬，未對 PTT 大量請求）

| 情境 | 模擬方式 | 明確錯誤狀態 |
| --- | --- | --- |
| DNS 失敗 | 查詢 `.invalid` 網域 | `DNS name does not exist`（NXDOMAIN） |
| 連線逾時 | 連 `192.0.2.1:22`（TEST-NET-1） | TIMEOUT（約 4.1s 後失敗） |
| TCP/TLS 連線失敗 | 連 `127.0.0.1:9`（關閉埠） | curl exit 7「Could not connect」 |
| WS 握手被拒 | 錯誤 Origin | HTTP 403 |
| WS 目標不存在 | 不存在主機 / 關閉埠 | `ERROR` 事件（無假成功） |
| WS 連線中斷 | 伺服器主動關閉 | close code 1000 / 異常 1006 |
| 未登入 / 登入失效 | guest 被拒 | 伺服器明確訊息「目前已有太多 guest 在站上」 |
| 畫面內容不符預期 | 對解析器餵 200 組隨機位元組 + HTML 錯誤頁 | 0 次崩潰（200/200）；HTML 原樣輸出、不誤判為看板 |
| 文章列表暫時無法讀取 | guest 上限 | 明確失敗，不會進入發文流程 |

→ 所有情境皆能辨識為明確錯誤狀態；無任一情境進入發文流程。

---

## 8. 未驗證項目及阻礙原因

1. **看板列表／文章標題／文章內容讀取**：阻礙＝guest 並行上限已滿，且本階段未取得本人帳號授權，依規則 B5 停止。
2. **文章識別資訊格式**：阻礙＝未成功讀取任何看板／文章。
3. **SSH 埠 8888**：本網路環境無法連線（逾時），僅 22 埠可達。
4. **站方登入／請求頻率限制的具體數值**：官方文件未公布。
5. **發文流程**：官方無公開發文 API；互動式發文未在任何階段測試（依限制不進行）。

---

## 9. 下一階段建議（僅建議，本階段不執行）

1. **仍以 WebSocket 為主要技術路線**：已證實可連線、握手、讀取公開畫面；且必須使用 **binary 訊框** 傳送按鍵。
2. **看板／文章唯讀驗證需有可用工作階段**：建議在明確授權下，使用本人正常帳號登入（或於 guest 名額可用時），再接續驗證看板列表與文章讀取；並加入登入逾時、session 失效與 ANSI 畫面解析的處理。
3. **勿假設有發文 API**：若要自動發文，只能走互動式終端（模擬按鍵）路線，風險高且須嚴格遵守站方規範；應先確認站方對自動化發文的政策。
4. **SHELL 端（SSH）與 WS 二選一**：SSH（`bbsu@ptt.cc` UTF-8）為官方支援路徑，可作為 WS 的備援；但同樣需登入。
5. **解析器**：新增 Big5 解碼、IAC/ANSI 去除與畫面狀態機；對非預期內容需具備明確錯誤分類（本階段已初步驗證健壯性）。
6. **合規**：維持單一連線、低頻率、不重試風暴、不繞過防護，並記錄站方限制訊息。

---

## 10. 明確結論

### 結論：**PARTIAL（部分通過）**

- **通過**：DNS／TCP／TLS 連線；WebSocket 握手（`Origin: https://term.ptt.cc` 專屬）；SSH banner 可達；未登入讀取登入前公開畫面；binary-only 輸入協定確認；故障情境錯誤分類。
- **未驗證**：看板列表、文章標題／內容、文章識別資訊（受阻於 guest 並行上限與無帳號授權，依規則停在登入需求處）。
- **官方確認之否定事實**：不存在可供第三方合法使用的公開發文 API；發文僅存在於互動式終端工作階段。

**未發文、未登入、未修改任何 PTT 資料。** 依指示於此停止，不進入 Phase 2、不開始開發發文功能。
