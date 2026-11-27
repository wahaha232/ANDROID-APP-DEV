# -*- coding: utf-8 -*-
"""
Windows Excel COM 自動修正模組 (win32com.client)
特點：
  - 直接透過本機 Excel COM 編輯並儲存，100% 保留 .xls / .xlsx 內嵌 PDF 物件與原有排版格式
  - 保留原始檔案不動：自動將修正前原檔備份至 backup 資料夾，修正後檔案輸出至 output 資料夾
  - 依修正值型別寫入（日期、數值、文字），並明確設定 NumberFormat 避免格式殘留
  - 所有修正過的儲存格套用紅色字體 RGB(255, 0, 0)
  - 支援 ExcelComSession 共用同一個 Excel 處理程序，加速批次修正並確保釋放 COM 物件
"""

from __future__ import annotations

import datetime as dt
import os
import re
import shutil
from dataclasses import dataclass, field
from typing import Any, Callable, List, Optional, Tuple

from excel_checker import CheckIssue, extract_product_identity, make_output_group_folder_name

# Excel VBA/COM 的 RGB(r, g, b) = r + g*256 + b*65536，因此紅色 RGB(255, 0, 0) = 255
EXCEL_COLOR_RED = 255

_DATE_TEXT_RE = re.compile(r"^(\d{4})/(\d{1,2})/(\d{1,2})$")
_INT_RE = re.compile(r"^-?\d+$")
_FLOAT_RE = re.compile(r"^-?\d+\.\d+$")


@dataclass
class FixResult:
    saved_path: str
    used_fallback: bool
    fixed_count: int
    skipped_items: List[Tuple[str, str]] = field(default_factory=list)
    backup_path: str = ""
    overwrote_existing: bool = False


def _norm_identity(identity: Tuple[str, str]) -> Tuple[str, str]:
    fox, sup = identity
    return (
        re.sub(r"\s+", "", (fox or "").strip()).upper(),
        re.sub(r"\s+", "", (sup or "").strip()).upper(),
    )


def _identities_match(id1: Tuple[str, str], id2: Tuple[str, str]) -> bool:
    n1 = _norm_identity(id1)
    n2 = _norm_identity(id2)
    if not any(n1) or not any(n2):
        return False
    return n1 == n2


def _make_numbered_path(excel_path: str, suffix: str, target_dir: Optional[str] = None) -> str:
    stem, ext = os.path.splitext(os.path.basename(excel_path))
    parent = os.path.abspath(target_dir) if target_dir else os.path.dirname(os.path.abspath(excel_path))
    candidate = os.path.join(parent, f"{stem}_{suffix}{ext}")
    if not os.path.exists(candidate):
        return candidate
    idx = 2
    while True:
        cand = os.path.join(parent, f"{stem}_{suffix}_{idx}{ext}")
        if not os.path.exists(cand):
            return cand
        idx += 1


def _resolve_grouped_output_dir(
    excel_path: str,
    output_dir: Optional[str] = None,
    source_identity: Optional[Tuple[str, str]] = None,
) -> Tuple[str, Tuple[str, str]]:
    """
    依檔案的 (Foxlink產品料號, 供應商產品料號) 決定 output 內的自動歸類資料夾：
    例如 output\\07C6-105M-43AD_CL03A105MQ3CSNH\\；若無法解析雙料號則回傳 output_dir 本身。
    """
    base_dir = os.path.abspath(output_dir) if output_dir else os.path.dirname(os.path.abspath(excel_path))
    src_id = source_identity if source_identity is not None else extract_product_identity(excel_path)
    group_name = make_output_group_folder_name(src_id)
    if group_name:
        return os.path.join(base_dir, group_name), src_id
    return base_dir, src_id


def _resolve_output_path(
    excel_path: str,
    output_dir: Optional[str] = None,
    source_identity: Optional[Tuple[str, str]] = None,
    suffix: Optional[str] = "已修正",
) -> Tuple[str, bool]:
    """
    決定檔案於 output 歸類資料夾中的輸出路徑：
    - 若目標檔名尚未存在，直接使用「原檔名_已修正.<ext>」（或 suffix=None 時使用原檔名）。
    - 若目標檔名已存在，比對 [Foxlink產品料號 + 供應商產品料號]：
      * 相同（且檔案未被鎖定）→ 直接覆蓋舊版本檔案，回傳 (path, True)。
      * 不同（或無法比對）→ 依序尋找 _2、_3... 直到找到同料號舊檔或空位。
    """
    stem, ext = os.path.splitext(os.path.basename(excel_path))
    parent, src_id = _resolve_grouped_output_dir(
        excel_path, output_dir=output_dir, source_identity=source_identity
    )

    idx = 1
    while True:
        if suffix:
            name = f"{stem}_{suffix}{ext}" if idx == 1 else f"{stem}_{suffix}_{idx}{ext}"
        else:
            name = f"{stem}{ext}" if idx == 1 else f"{stem}_{idx}{ext}"
        cand = os.path.join(parent, name)
        if not os.path.exists(cand):
            return cand, False
        if any(_norm_identity(src_id)):
            existing_id = extract_product_identity(cand)
            if _identities_match(src_id, existing_id) and not _is_file_locked(cand):
                return cand, True
        idx += 1


def archive_verified_file_to_output(
    excel_path: str,
    output_dir: Optional[str] = None,
    move_source: bool = False,
) -> Tuple[str, bool]:
    """
    將比對確認無錯誤的檔案（例如 O-202AH 或無異常 O-193D）歸檔至 output 對應的料號資料夾。
    若同料號檔案已存在則直接覆蓋舊版。
    當 move_source=True 時，直接將原始檔案搬移過去（跨磁碟自動退回 copy + remove）。
    回傳 (archived_path, overwrote_existing)。
    """
    abs_path = os.path.abspath(excel_path)
    script_dir = os.path.dirname(os.path.abspath(__file__))
    resolved_output_dir = os.path.abspath(output_dir) if output_dir else os.path.join(script_dir, "output")
    dest_path, overwrote = _resolve_output_path(
        abs_path, output_dir=resolved_output_dir, suffix=None
    )
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    if os.path.normcase(abs_path) == os.path.normcase(os.path.abspath(dest_path)):
        return dest_path, False
    if move_source:
        shutil.move(abs_path, dest_path)
    else:
        shutil.copy2(abs_path, dest_path)
    return dest_path, overwrote


def move_source_file_to_backup(
    excel_path: str,
    backup_dir: Optional[str] = None,
) -> str:
    """
    將已完成修正的原始檔案從原位置搬移至 backup 資料夾（直接覆蓋前一版本）。
    回傳最終備份路徑。
    """
    abs_path = os.path.abspath(excel_path)
    script_dir = os.path.dirname(os.path.abspath(__file__))
    resolved_backup_dir = os.path.abspath(backup_dir) if backup_dir else os.path.join(script_dir, "backup")
    os.makedirs(resolved_backup_dir, exist_ok=True)
    backup_path = _make_backup_path(abs_path, backup_dir=resolved_backup_dir)
    if os.path.normcase(abs_path) == os.path.normcase(os.path.abspath(backup_path)):
        return backup_path
    shutil.move(abs_path, backup_path)
    return backup_path


def _make_output_path(excel_path: str, output_dir: Optional[str] = None) -> str:
    path, _ = _resolve_output_path(excel_path, output_dir=output_dir)
    return path


def _make_fallback_path(excel_path: str, output_dir: Optional[str] = None) -> str:
    return _make_output_path(excel_path, output_dir=output_dir)


def _make_backup_path(excel_path: str, backup_dir: Optional[str] = None) -> str:
    """
    決定修正前備份路徑：
    預設直接覆蓋前一版本「原檔名_修正前備份.<ext>」；
    若舊備份正被其他程式鎖定無法覆寫，才退回自動加編號。
    """
    stem, ext = os.path.splitext(os.path.basename(excel_path))
    parent = os.path.abspath(backup_dir) if backup_dir else os.path.dirname(os.path.abspath(excel_path))
    candidate = os.path.join(parent, f"{stem}_修正前備份{ext}")
    if not os.path.exists(candidate) or not _is_file_locked(candidate):
        return candidate
    return _make_numbered_path(excel_path, "修正前備份", target_dir=backup_dir)


def _excel_file_format(path: str) -> int:
    """SaveAs 時明確指定格式，避免副檔名與實際格式不一致。"""
    ext = os.path.splitext(path)[1].lower()
    if ext == ".xls":
        return 56
    if ext == ".xlsm":
        return 52
    return 51


def _is_file_locked(path: str) -> bool:
    """快速檢查檔案是否正被其他程式獨佔鎖定。"""
    if not os.path.exists(path):
        return False
    try:
        with open(path, "r+b"):
            return False
    except OSError:
        return True


def _coerce_fix_value(value: Any) -> Tuple[Any, str]:
    """依內容決定寫入型別：(值, 'number' | 'date' | 'text')。"""
    if isinstance(value, dt.datetime):
        return value, "date"
    if isinstance(value, dt.date):
        return dt.datetime(value.year, value.month, value.day), "date"
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return value, "number"

    s = str(value).strip()
    if _INT_RE.match(s):
        return int(s), "number"
    if _FLOAT_RE.match(s):
        return float(s), "number"
    m = _DATE_TEXT_RE.match(s)
    if m:
        try:
            return dt.datetime(int(m.group(1)), int(m.group(2)), int(m.group(3))), "date"
        except ValueError:
            pass
    return s, "text"


def _try_set_number_format(cell, candidates: Tuple[str, ...]) -> None:
    """
    安全設定 Excel 儲存格 NumberFormat（相容繁中／簡中／英文等不同語系 Excel）。
    部分語系 Excel 對英文 'General' 會拋出 COM 例外，須依序嘗試本地化格式，
    且絕不讓格式設定失敗阻擋儲存格數值寫入。
    """
    for fmt in candidates:
        try:
            cell.NumberFormat = fmt
            return
        except Exception:
            continue


def _read_number_format(cell) -> str:
    """安全讀取儲存格目前的 NumberFormat（COM 失敗時視為空字串）。"""
    try:
        fmt = cell.NumberFormat
    except Exception:
        return ""
    return fmt if isinstance(fmt, str) else ""


def _format_conflicts_with_number(fmt: str) -> bool:
    """判斷現有格式是否會讓寫入的數值顯示錯誤（文字、百分比、日期／時間）。"""
    f = (fmt or "").strip().lower()
    if not f:
        return False
    if "@" in f or "%" in f:
        return True
    if any(tok in f for tok in ("yy", "dd", "年", "月", "日", "上午", "下午", "am/pm")):
        return True
    return bool(re.search(r"(?:^|[^a-z])(?:h{1,2}|s{1,2}):", f))


def _write_cell(cell, value: Any) -> None:
    coerced, kind = _coerce_fix_value(value)
    if kind == "date":
        _try_set_number_format(cell, ("yyyy/mm/dd", "yyyy-mm-dd"))
        cell.Value = coerced
    elif kind == "number":
        # 只在文字／百分比／日期等會讓數值顯示錯誤的格式才重設，
        # 其餘（例如 0.000）保留原格式，避免不必要的顯示變更。
        if _format_conflicts_with_number(_read_number_format(cell)):
            _try_set_number_format(cell, ("General", "G/通用格式", "0.########"))
        cell.Value = coerced
    else:
        # 含 <、單位或特殊符號的值一律以文字寫入，避免 Excel 自動轉型
        _try_set_number_format(cell, ("@",))
        cell.Value = coerced
    cell.Font.Color = EXCEL_COLOR_RED


class ExcelComSession:
    """
    管理單一獨立 Excel COM 執行個體（可供批次修正共用，避免重複啟閉 Excel），
    並在結束時顯式釋放 COM 參考，降低背景 EXCEL.EXE 殘留風險。
    """

    def __init__(self) -> None:
        self.excel = None
        self._pythoncom = None

    def __enter__(self):
        import pythoncom
        import win32com.client as win32

        self._pythoncom = pythoncom
        pythoncom.CoInitialize()
        try:
            excel = win32.DispatchEx("Excel.Application")
            excel.Visible = False
            excel.DisplayAlerts = False
            excel.ScreenUpdating = False
            excel.AskToUpdateLinks = False
            excel.EnableEvents = False
            # msoAutomationSecurityForceDisable：禁止未信任活頁簿的巨集在開啟時執行
            excel.AutomationSecurity = 3
            self.excel = excel
        except Exception:
            self.excel = None
            pythoncom.CoUninitialize()
            raise
        return self.excel

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        if self.excel is not None:
            try:
                self.excel.Quit()
            except Exception:
                pass
            self.excel = None
        if self._pythoncom is not None:
            try:
                self._pythoncom.CoUninitialize()
            except Exception:
                pass
            self._pythoncom = None


def apply_fixes_via_excel_com(
    excel_path: str,
    issues: List[CheckIssue],
    excel_app: Any = None,
    output_dir: Optional[str] = None,
    backup_dir: Optional[str] = None,
    in_place_target: Optional[str] = None,
    create_backup: bool = True,
    progress_cb: Optional[Callable[[int, int, str], None]] = None,
) -> FixResult:
    """
    透過 Windows Excel COM 編輯並輸出修正後的檔案：
    - 保留原始檔案不動
    - 將原始檔案備份至 backup_dir（預設為工具目錄下 backup/；多輪繼續修正時可設 create_backup=False 避免重複備份）
    - 將修正後檔案輸出至 output_dir（預設為工具目錄下 output/）：
      * 若 output_dir 內已有同名檔案且 [Foxlink產品料號 + 供應商產品料號] 相同，直接覆蓋舊版本
      * 若指定 in_place_target（例如第二輪繼續修正上一輪的輸出檔），直接編輯該輸出檔
    - 寫入所有 fixable 項目並標記紅字 RGB(255, 0, 0)
    若傳入 excel_app（來自 ExcelComSession），會沿用該執行個體而不重複啟動 Excel。
    """
    fixable = [i for i in issues if i.fixable and i.fix_value is not None]
    if not fixable:
        return FixResult(saved_path=in_place_target or excel_path, used_fallback=False, fixed_count=0)

    abs_path = os.path.abspath(excel_path)
    script_dir = os.path.dirname(os.path.abspath(__file__))
    resolved_output_dir = os.path.abspath(output_dir) if output_dir else os.path.join(script_dir, "output")
    resolved_backup_dir = os.path.abspath(backup_dir) if backup_dir else os.path.join(script_dir, "backup")

    os.makedirs(resolved_output_dir, exist_ok=True)
    os.makedirs(resolved_backup_dir, exist_ok=True)

    backup_path = ""
    if create_backup:
        backup_path = _make_backup_path(abs_path, backup_dir=resolved_backup_dir)
        try:
            shutil.copy2(abs_path, backup_path)
        except OSError as exc:
            raise RuntimeError(
                f"無法建立修正前備份至 {resolved_backup_dir}（{exc}），已中止自動修正"
            ) from exc

    overwrote_existing = False
    if in_place_target and os.path.isfile(in_place_target):
        output_path = os.path.abspath(in_place_target)
        overwrote_existing = True
    else:
        output_path, overwrote_existing = _resolve_output_path(abs_path, output_dir=resolved_output_dir)
        try:
            os.makedirs(os.path.dirname(output_path), exist_ok=True)
            shutil.copy2(abs_path, output_path)
        except OSError as exc:
            raise RuntimeError(
                f"無法建立輸出檔案至 {os.path.dirname(output_path)}（{exc}），已中止自動修正"
            ) from exc

    target_open_path = output_path
    saved_path = output_path
    used_fallback = True

    def _run_with_excel(excel) -> FixResult:
        nonlocal saved_path
        wb = None
        ws = None
        cell = None
        fixed_count = 0
        skipped: List[Tuple[str, str]] = []

        total_items = len(fixable)
        processed_items = 0

        def _notify(label: str) -> None:
            if progress_cb is not None:
                try:
                    progress_cb(processed_items, total_items, label)
                except Exception:
                    pass

        try:
            _notify("開啟檔案...")
            wb = excel.Workbooks.Open(
                Filename=target_open_path,
                UpdateLinks=0,
                ReadOnly=False,
                IgnoreReadOnlyRecommended=True,
            )

            by_sheet: dict = {}
            for item in fixable:
                by_sheet.setdefault(item.sheet_name, []).append(item)

            for sheet_name, sheet_issues in by_sheet.items():
                try:
                    ws = wb.Worksheets(sheet_name)
                except Exception as exc:
                    for it in sheet_issues:
                        skipped.append((f"{sheet_name}!{it.cell_address}", f"找不到工作表: {exc}"))
                        processed_items += 1
                        _notify(f"{it.cell_address} (略過)")
                    continue

                for it in sheet_issues:
                    try:
                        cell = ws.Range(it.cell_address)
                        _write_cell(cell, it.fix_value)
                        fixed_count += 1
                    except Exception as exc:
                        skipped.append((f"{sheet_name}!{it.cell_address}", str(exc)))
                    finally:
                        cell = None
                        processed_items += 1
                        _notify(f"{it.cell_address}")
                ws = None

            _notify("儲存檔案...")
            wb.Save()
            saved_path = target_open_path
            _notify("完成")
        finally:
            cell = None
            ws = None
            if wb is not None:
                try:
                    wb.Close(SaveChanges=False)
                except Exception:
                    pass
                wb = None

        return FixResult(
            saved_path=saved_path,
            used_fallback=used_fallback,
            fixed_count=fixed_count,
            skipped_items=skipped,
            backup_path=backup_path,
            overwrote_existing=overwrote_existing,
        )

    if excel_app is not None:
        return _run_with_excel(excel_app)

    with ExcelComSession() as own_excel:
        return _run_with_excel(own_excel)
