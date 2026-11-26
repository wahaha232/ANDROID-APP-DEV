# -*- coding: utf-8 -*-
"""
Windows Excel COM 自動修正模組 (win32com.client)
特點：
  - 直接透過本機 Excel COM 編輯並儲存，100% 保留 .xls / .xlsx 內嵌 PDF 物件與原有排版格式
  - 覆寫原檔前自動建立「原檔名_修正前備份.xls(x)」；無法備份時改存副本，不冒險覆寫
  - 依修正值型別寫入（日期、數值、文字），避免結果被寫成文字或日期變序號
  - 所有修正過的儲存格套用紅色字體 RGB(255, 0, 0)
  - 若原檔案被其他程式開啟鎖定無法覆蓋，自動另存為「原檔名_已修正.xls(x)」而不中斷報錯
"""

from __future__ import annotations

import datetime as dt
import os
import re
import shutil
from dataclasses import dataclass, field
from typing import Any, List, Optional, Tuple

from excel_checker import CheckIssue

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


def _make_numbered_path(excel_path: str, suffix: str) -> str:
    base, ext = os.path.splitext(excel_path)
    candidate = f"{base}_{suffix}{ext}"
    if not os.path.exists(candidate):
        return candidate
    idx = 2
    while True:
        cand = f"{base}_{suffix}_{idx}{ext}"
        if not os.path.exists(cand):
            return cand
        idx += 1


def _make_fallback_path(excel_path: str) -> str:
    return _make_numbered_path(excel_path, "已修正")


def _make_backup_path(excel_path: str) -> str:
    return _make_numbered_path(excel_path, "修正前備份")


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


def _write_cell(cell, value: Any) -> None:
    coerced, kind = _coerce_fix_value(value)
    if kind == "date":
        cell.Value = coerced
        cell.NumberFormat = "yyyy/mm/dd"
    elif kind == "number":
        cell.Value = coerced
    else:
        # 含 <、單位或特殊符號的值一律以文字寫入，避免 Excel 自動轉型
        cell.NumberFormat = "@"
        cell.Value = coerced
    cell.Font.Color = EXCEL_COLOR_RED


def apply_fixes_via_excel_com(excel_path: str, issues: List[CheckIssue]) -> FixResult:
    """
    透過 Windows Excel COM 開啟檔案，寫入所有 fixable 項目並標記紅字。
    """
    fixable = [i for i in issues if i.fixable and i.fix_value is not None]
    if not fixable:
        return FixResult(saved_path=excel_path, used_fallback=False, fixed_count=0)

    import pythoncom
    import win32com.client as win32

    abs_path = os.path.abspath(excel_path)
    file_locked = _is_file_locked(abs_path)

    target_open_path = abs_path
    saved_path = abs_path
    used_fallback = False
    backup_path = ""

    # 若原檔正被其他程式開啟鎖定，先完整複製為「原檔名_已修正.xls(x)」再以 Excel COM 編輯
    # 可 100% 保留內嵌 PDF 物件並避免觸發原檔的共用鎖定錯誤
    if file_locked:
        fallback = _make_fallback_path(abs_path)
        try:
            shutil.copy2(abs_path, fallback)
            target_open_path = fallback
            saved_path = fallback
            used_fallback = True
        except OSError:
            # 若連複製都被拒絕，仍嘗試以唯讀開啟原檔並另存（原檔不會被覆寫）
            target_open_path = abs_path
    else:
        # 覆寫原檔前先備份；備份失敗則改存副本，不冒險覆寫
        backup = _make_backup_path(abs_path)
        try:
            shutil.copy2(abs_path, backup)
            backup_path = backup
        except OSError:
            fallback = _make_fallback_path(abs_path)
            try:
                shutil.copy2(abs_path, fallback)
                target_open_path = fallback
                saved_path = fallback
                used_fallback = True
            except OSError as exc:
                raise RuntimeError(
                    f"無法建立備份或副本（{exc}），為避免損壞原檔已中止自動修正"
                ) from exc

    pythoncom.CoInitialize()
    excel = None
    wb = None
    fixed_count = 0
    skipped: List[Tuple[str, str]] = []

    try:
        # 使用 DispatchEx 建立獨立 Excel 處理程序，避免干擾使用者目前開啟中的 Excel 視窗
        excel = win32.DispatchEx("Excel.Application")
        excel.Visible = False
        excel.DisplayAlerts = False
        excel.ScreenUpdating = False
        excel.AskToUpdateLinks = False
        excel.EnableEvents = False
        # msoAutomationSecurityForceDisable：禁止未信任活頁簿的巨集在開啟時執行
        excel.AutomationSecurity = 3

        wb = excel.Workbooks.Open(
            Filename=target_open_path,
            UpdateLinks=0,
            ReadOnly=(file_locked and not used_fallback),
            IgnoreReadOnlyRecommended=True,
        )

        # 依工作表分組套用修正
        by_sheet: dict = {}
        for item in fixable:
            by_sheet.setdefault(item.sheet_name, []).append(item)

        for sheet_name, sheet_issues in by_sheet.items():
            try:
                ws = wb.Worksheets(sheet_name)
            except Exception as exc:
                for it in sheet_issues:
                    skipped.append((f"{sheet_name}!{it.cell_address}", f"找不到工作表: {exc}"))
                continue

            for it in sheet_issues:
                try:
                    cell = ws.Range(it.cell_address)
                    _write_cell(cell, it.fix_value)
                    fixed_count += 1
                except Exception as exc:
                    skipped.append((f"{sheet_name}!{it.cell_address}", str(exc)))

        # 儲存邏輯：
        # 1. 若已切換至 fallback 副本，直接 Save()
        # 2. 若編輯原檔且非 ReadOnly，嘗試 Save()，失敗時自動 SaveAs 另存
        if used_fallback:
            wb.Save()
            saved_path = target_open_path
        elif not getattr(wb, "ReadOnly", False):
            try:
                wb.Save()
                saved_path = abs_path
            except Exception:
                fallback = _make_fallback_path(abs_path)
                wb.SaveAs(Filename=fallback, FileFormat=_excel_file_format(fallback))
                saved_path = fallback
                used_fallback = True
        else:
            fallback = _make_fallback_path(abs_path)
            wb.SaveAs(Filename=fallback, FileFormat=_excel_file_format(fallback))
            saved_path = fallback
            used_fallback = True

    finally:
        if wb is not None:
            try:
                wb.Close(SaveChanges=False)
            except Exception:
                pass
        if excel is not None:
            try:
                excel.Quit()
            except Exception:
                pass
        pythoncom.CoUninitialize()

    return FixResult(
        saved_path=saved_path,
        used_fallback=used_fallback,
        fixed_count=fixed_count,
        skipped_items=skipped,
        backup_path=backup_path,
    )
