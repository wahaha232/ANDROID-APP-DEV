# PTT News Auto Poster

PTT 新聞自動 PO 文工具的 **Phase 2A：本機核心模組**。

本階段只實作 **不依賴 PTT 連線** 的功能：新聞解析、文章格式化、SQLite 資料管理、排程管理、
防重複與狀態管理，以及 CLI。**不登入 PTT、不發文、不回文**，也不進行任何 PTT 自動化。

Phase 1 唯讀技術驗證結果見 `reports/PTT_READONLY_FEASIBILITY.md`（結論 PARTIAL）。

## 環境需求

- Python 3.10 以上
- **僅使用標準函式庫**（`html.parser`、`urllib`、`sqlite3`、`unittest`、`hashlib`、`argparse` 等）
- 無需安裝任何第三方套件

## 目錄結構

```
src/
  models.py              狀態列舉、狀態轉移表、時間工具、資料類別
  news/                  新聞解析（urlutil / fetcher / parser）
  formatter/             固定格式文章組合、簽名檔、SHA-256 指紋
  storage/               SQLite schema 與 DAO（交易、併發、快照、復原）
  scheduler/             排程模式、核准、到期事件（絕不發文）
  cli/                   argparse CLI
tests/
  fixtures/              本機測試新聞頁（UTF-8 / Big5 / JSON-LD / 缺欄位 / 付費牆）
  unit/                  單元測試
  integration/           整合測試
config/default.json      預設設定（資料庫路徑、抓取限制）
data/                    執行時的 SQLite 資料庫（已 gitignore）
logs/                    執行／測試日誌
reports/                 交付報告
```

## 安裝

不需安裝。於專案根目錄直接以 `python` 執行即可。

## 執行（CLI）

```powershell
# 初始化資料庫（預設 data/pttposter.db，可用 --db 指定）
python -m src.cli.main init

# 簽名檔
python -m src.cli.main signature add main "-- `nPTTNewsBot" --default
python -m src.cli.main signature list

# 看板資料（僅本機，不驗證 PTT 看板是否存在）
python -m src.cli.main board add Gossiping --description "八卦板"
python -m src.cli.main board list --keyword Gossip

# 新聞解析（--fixture 讀本機檔案；不給 --fixture 時以 --url 連線抓取並受 SSRF 防護限制）
python -m src.cli.main news parse --fixture tests/fixtures/normal_utf8.html --url https://www.example.test/a

# 建立草稿、預覽、組合並存快照
python -m src.cli.main draft create --fixture tests/fixtures/normal_utf8.html --url https://www.example.test/a
python -m src.cli.main draft preview <draft_id>
python -m src.cli.main draft format <draft_id> --save

# 排程（到期只產生本機待處理事件，不會呼叫任何 PTT 發文功能）
python -m src.cli.main schedule add --draft-id <draft_id> --mode IMMEDIATE
python -m src.cli.main schedule add --draft-id <draft_id> --mode AT_DATETIME --at 2026-10-10T00:00:00+08:00
python -m src.cli.main schedule pending
python -m src.cli.main schedule approve <task_id>
python -m src.cli.main schedule tick
python -m src.cli.main schedule cancel <task_id>

# 恢復重啟前未完成的本機任務
python -m src.cli.main recover
```

排程模式：`IMMEDIATE`、`AT_DATETIME`、`SCHEDULED_MANUAL_CONFIRM`、`SCHEDULED_AUTO_SEND`。
時間一律以 **UTC** 儲存，CLI 顯示時轉為 **台灣時間**。

## 測試

```powershell
# 執行全部測試（110 個）
python -m unittest discover -s tests -t . -v

# 執行單一測試檔
python -m unittest discover -s tests -t . -p "test_storage.py"
```

所有外部網路行為（HTTP 抓取、DNS 解析）在測試中以注入的 `FakeFetcher` / resolver 取代，
不會對真實新聞網站發出請求。

## 已知限制（本階段）

- 看板資料僅為本機紀錄，未驗證 PTT 看板是否存在。
- 新聞本文預設只使用摘要（metadata/JSON-LD description），不轉載第三方全文。
- 排程到期只產生 `events` 待處理事件；**Phase 2A 沒有任何 PTT 連線或發文程式碼**。
- 未實作 PTT 登入、WebSocket 按鍵操作或文章送出（依指示等待下一階段授權）。
