# PTT News Auto Poster — Phase 2A 報告（本機核心模組）

> 本階段只實作不依賴 PTT 連線的功能。**未登入 PTT、未發文、未回文、未寄信、未修改 PTT 資料**，
> 也未實作任何 PTT 自動化。未修改 Phase 1 報告。

---

## 1. 測試環境與時間

| 項目 | 值 |
| --- | --- |
| 執行時間 | 2026-10-09（UTC） |
| 作業系統 | Microsoft Windows 10 專業版 (10.0.19045) |
| Shell | Windows PowerShell 5.1.19041.7725 |
| Python | 3.10.6（sqlite3 3.37.2） |
| 第三方套件 | **無**（僅標準函式庫；未安裝任何套件） |
| 測試框架 | `unittest`（標準函式庫） |
| 日誌 | `logs/test_run.log`、`logs/cli_demo.log` |

## 2. 實際新增／修改的檔案

| 檔案 | 說明 |
| --- | --- |
| `src/models.py` | 狀態／排程模式／解析狀態列舉、狀態轉移表、UTC↔台北時間工具、dataclasses、任務 ID |
| `src/news/urlutil.py` | URL 正規化（去追蹤參數）、SSRF 驗證（localhost／私有 IP／link-local／雲端 metadata／非 http(s)／DNS 解析） |
| `src/news/fetcher.py` | 可注入的 `SafeFetcher`：逾時、回應大小上限、允許協定、逐跳驗證重新導向、charset 偵測 |
| `src/news/parser.py` | metadata／JSON-LD／結構化新聞解析、欄位解析順序、站別 parser registry、付費牆/登入/CAPTCHA 偵測 |
| `src/formatter/article.py` | 固定格式組合、多簽名檔選擇、SHA-256 內容指紋、草稿欄位獨立編輯 |
| `src/storage/database.py` | SQLite schema 與 DAO：boards／signatures／drafts／snapshots／tasks／events；交易、併發、復原 |
| `src/scheduler/scheduling.py` | 4 種排程模式、核准與失效、到期只產生本機事件、取消／暫停／恢復／查詢 |
| `src/cli/main.py` | argparse CLI（init／news／draft／board／signature／schedule／recover） |
| `src/**/__init__.py` | 匯出 |
| `tests/_support.py` | FakeFetcher／FakeOpener／FakeResponse 與 fixture 讀取 |
| `tests/fixtures/*.html` | normal_utf8／normal_big5／jsonld_only／missing_fields／no_title／paywall |
| `tests/unit/test_*.py` | urlutil／news_parser／fetcher／formatter／storage／scheduler／cli 單元測試 |
| `tests/integration/test_pipeline.py` | 端到端本機流程整合測試 |
| `config/default.json` | 預設資料庫路徑與抓取限制 |
| `.gitignore` | 忽略 `data/*.db`、`logs/*.log`、`__pycache__` |
| `README.md` | 安裝、設定、執行、測試說明 |
| `reports/PHASE2A_REPORT.md` | 本報告 |

未修改 `reports/PTT_READONLY_FEASIBILITY.md`。

## 3. 測試結果

```
python -m unittest discover -s tests -t . -v
Ran 120 tests in ~1.8s
OK
```

| 測試檔 | 數量 | 結果 |
| --- | --- | --- |
| `tests/unit/test_urlutil.py` | 18 | PASS |
| `tests/unit/test_news_parser.py` | 16 | PASS |
| `tests/unit/test_fetcher.py` | 18 | PASS |
| `tests/unit/test_formatter.py` | 17 | PASS |
| `tests/unit/test_storage.py` | 20 | PASS |
| `tests/unit/test_scheduler.py` | 21 | PASS |
| `tests/unit/test_cli.py` | 7 | PASS |
| `tests/integration/test_pipeline.py` | 3 | PASS |
| **合計** | **120** | **全部 PASS** |

### 3.1 對照任務要求的測試涵蓋

| # | 要求 | 狀態 | 對應測試（節錄） |
| --- | --- | --- | --- |
| 1 | 正常新聞 metadata 解析 | PASS | `test_normal_metadata`、`test_jsonld_only` |
| 2 | 缺少標題／來源／記者 | PASS | `test_missing_fields_marks_partial_and_reporter_not_provided`、`test_no_title_is_error`、`test_reporter_never_guessed_from_body` |
| 3 | Big5／UTF-8／繁中 | PASS | `test_big5_content`、`test_utf8_traditional_chinese`、`DetectCharsetTests` |
| 4 | 無效 URL／逾時／過大／重新導向 | PASS | `test_invalid_url_short_circuits`、`test_timeout_classified`、`test_response_too_large`、`RedirectSsrfTests` |
| 5 | SSRF 防護 | PASS | `SsrfValidationTests`（10 例）、`test_blocks_private_target_before_request` |
| 6 | 文章格式與簽名檔 | PASS | `FormatTests`、`SignatureTests`、`FingerprintTests` |
| 7 | URL 與內容重複檢查 | PASS | `test_same_normalized`、`test_duplicate_normalized_url_blocked`、`test_duplicate_news_blocked_in_pipeline` |
| 8 | 排程時區與過期任務 | PASS | `test_utc_stored_and_taipei_display`、`TickTests` |
| 9 | 核准失效與取消排程 | PASS | `ApprovalInvalidationTests`、`test_cancel_pause_resume_pending` |
| 10 | SQLite 寫入失敗與重啟恢復 | PASS | `test_invalid_status_transition_rejected_and_rolled_back`、`PersistenceTests`、`test_recover_incomplete_tasks`、`test_restart_recovery` |
| 11 | 多工作程序競爭同一任務 | PASS | `test_two_connections_claim_same_task`、`test_two_schedulers_tick_single_event` |
| 12 | 排程到期絕不呼叫 PTT 發文 | PASS | `test_no_network_imports_in_scheduler`、`test_due_immediate_creates_event_and_no_send`（事件 payload 標註「不呼叫任何 PTT 發文功能」） |

> 測試全部使用本機 fixture 與注入的 mock（`FakeFetcher` / resolver / `FakeOpener`），
> 不對真實新聞網站發出請求。

## 3.2 獨立程式碼審查後修正（Review-driven fixes）

本階段另執行一次獨立審查，逐項以腳本重現驗證後修正；修正後全套 **120** 測試仍 PASS。

| 編號 | 嚴重度 | 修正內容 | 對應檔案 |
| --- | --- | --- | --- |
| F1 | CRITICAL | 補強 SSRF：封鎖 IPv6 site-local（`fec0::/10`）、6to4（`2002::/16`）、Teredo（`2001::/32`） | `src/news/urlutil.py` |
| F2 | CRITICAL | 連線釘選已驗證 IP（避免 DNS rebinding TOCTOU），連線後再複驗對端 IP | `src/news/fetcher.py` |
| F3 | WARNING | `revalidate` 於同狀態／終端狀態不再丟 `InvalidStateTransition`；`cancel` 會清除核准 | `scheduler/scheduling.py`、`storage/database.py` |
| F4 | WARNING | `approve` 重設 `due_fired`，使過期後才核准的自動送出任務仍會產生事件 | `scheduler/scheduling.py` |
| F5 | WARNING | 核准主體改以目前草稿內容 digest 計算；改內容須同時提供 fingerprint，否則拒絕 | `scheduler/scheduling.py`、`storage/database.py` |
| F6 | WARNING | `recover_incomplete_tasks` 改於單一交易內、僅釋放實際列出的 claim | `storage/database.py` |
| F7 | WARNING | `tick` 以 `fire_due_task` 在單一交易內原子完成「標記 `due_fired`＋寫 event＋改狀態」 | `storage/database.py`、`scheduler/scheduling.py` |
| F8 | WARNING | `delete_draft` 於仍有未取消任務時拒絕（需 `force`） | `storage/database.py` |
| F9 | WARNING | `_write` 於 `BaseException`／`COMMIT` 失敗時確實 `ROLLBACK` | `storage/database.py` |
| F10 | WARNING | `tasks.signature_id` 補上 FK（`ON DELETE SET NULL`），避免髒參照 | `storage/database.py` |
| F11 | WARNING | 讀取方法改取得 `self._lock`，與共用連線的寫入互斥 | `storage/database.py` |
| F12 | SUGGESTION | `tick` 避免重複查詢（重用已載入列、傳入 digest） | `scheduler/scheduling.py` |
| F13 | SUGGESTION | 移除死程式碼與未用 import（`POSTED_STATUSES`、`REQUIRED_TASK_FIELDS`、`DraftContent.missing_fields`、`split_url`、`parse_news`、`validate_news_url`、`format_taipei`/`to_taipei`） | `models.py` 等 |
| F14 | SUGGESTION | 集中重複的到期任務 SQL 條件與 `NOT_PROVIDED` 常數 | `storage/database.py`、`models.py` |

實測驗證（修正後）：
- `http://[fec0::1]/`、`http://[2002:a9fe:a9fe::]/` → 已封鎖（`UnsafeURLError`）。
- opener handler 僅含 `_PinnedHTTPHandler`／`_PinnedHTTPSHandler`（無預設 HTTP/HTTPS handler）。
- `revalidate`（AWAITING、CANCELLED）不再拋錯；`approve` 後過期任務 `tick` 產生 `AUTO_SEND_DUE`。
- 內容變更未帶 fingerprint → `ValueError`；帶新 fingerprint → 核准失效。
- 有未取消任務時 `delete_draft` → `DatabaseError`。

新增測試：SSRF IPv6、連線釘選／對端複驗、`revalidate` 不崩潰、過期後核准觸發、內容變更失效、寫入失敗回滾後連線可用、刪簽名檔 FK 設 NULL。

## 4. 已實作能力摘要
- **新聞解析**：原始／正規化 URL、標題、來源、記者、摘要、發布時間、解析狀態與錯誤原因；
  欄位缺失時記者標記「未提供」，不猜測；JSON-LD／OpenGraph／meta／`<time>` 依序解析；
  站別 parser 可透過 `register_site_parser()` 擴充。
- **SSRF 防護**：僅允許 http/https；封鎖 localhost、私有／loopback／link-local／保留位址、
  `169.254.169.254` 等雲端 metadata 及 `metadata.google.internal`；DNS 解析後逐一檢查；
  重新導向每一跳都重新驗證。
- **文章格式化**：固定格式；標題／來源／記者／本文／簽名檔可獨立編輯；支援多簽名檔與預設選擇；
  預覽與重新組合；組合後以 SHA-256 計算指紋，內容變更指紋即改變；缺欄位呈現「未提供」而不編造。
- **資料管理（SQLite）**：看板新增／刪除／搜尋／書籤；簽名檔新增／修改／刪除／預設；
  草稿與文章快照；唯一任務 ID（uuid4 hex）；UTC 建立／更新時間。
- **排程管理**：4 種模式；UTC 儲存、台北時間顯示；取消／暫停／恢復／查詢待處理；
  自動送出需逐篇明確核准；文章／看板／時間變更即使原核准失效；到期只寫入 `events` 待處理事件。
- **防重複與狀態**：URL 正規化＋`normalized_url` 唯一索引；內容 SHA-256 指紋；
  狀態僅限 DRAFT／READY／SCHEDULED／AWAITING_CONFIRMATION／CANCELLED／FAILED／NEEDS_REVIEW；
  明確狀態轉移驗證；`BEGIN IMMEDIATE` 交易；重啟後 `recover` 清除未完成 claim。

## 5. 已知限制

- 看板資料僅為本機紀錄，**不驗證 PTT 看板是否存在**（符合本階段範圍）。
- 新聞本文預設只採用 metadata／JSON-LD 的摘要，不抓取或轉載第三方全文。
- 付費牆／登入／CAPTCHA 偵測為**關鍵字＋meta 標記啟發式**，可能遺漏或誤判；
  遇到阻擋會停止（`BLOCKED`），不嘗試繞過。
- `SafeFetcher` 使用 `urllib`；TLS 憑證驗證沿用系統預設，未自訂憑證釘選。
- 排程到期僅產生本機事件；狀態機中刻意**沒有**「已發文」狀態，避免誤判。

## 6. NOT RUN（明確標記，未虛報）

| 項目 | 原因 |
| --- | --- |
| 真實新聞網站線上抓取 | 依規則不對真實新聞網站發出大量請求；改以 fixture／mock 驗證 |
| PTT 登入、WebSocket 按鍵操作 | 超出 Phase 2A 範圍，依指示不實作 |
| 文章送出／自動發文 | 明確禁止；程式中無任何發文程式碼 |
| PTT 看板存在性驗證 | 本階段只管理本機資料 |
| 站別 parser 於真實網站之準確度 | 僅驗證 registry 擴充機制，未逐一實作各站 parser |

## 7. 下一階段建議（僅建議，本階段不執行）

1. 針對實際目標新聞網站補齊站別 parser（記者、來源、發布時間）並以離線存檔頁面回歸測試。
2. 加入 `--json` 輸出與更完整的 CLI 錯誤碼，方便自動化與後續整合。
3. 依 Phase 1 結果，於獲得明確授權後再評估 PTT 端（guest 名額或本人帳號）之唯讀看板／文章讀取，
   仍不進行發文。
4. 排程可加入「人工確認工作佇列」與事件消費流程（仍在發文之前停止）。
5. 為 SQLite 加入 schema 版本遷移機制（目前 `schema_meta` 已預留 `schema_version=1`）。

## 8. 結論

**Phase 2A：PASS（本機核心模組）**

- 5 大模組（news／formatter／storage／scheduler／cli）與 models 均已實作並通過測試。
- 交付可執行 CLI、SQLite schema 與初始化、單元＋整合測試（120 個全數 PASS）、README。
- 未虛報：所有測試皆實際執行；未執行項目已於第 6 節明確標記。
- 依停止條件，本階段完成即停止，不進入 PTT 登入、WebSocket 按鍵操作、文章送出或自動發文驗證。
