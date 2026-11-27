# -*- coding: utf-8 -*-
"""
O-193D 環境管理物質部件展開表 — 自動核對與修正主程式

用法：
  python o193d_checker.py <Excel檔案路徑或資料夾...> [--save-report] [--fix | --interactive-fix] [--sheet 工作表名] [--recursive] [--verbose]

結束代碼 (Exit Code)：
  0 : 核對完成且無異常（或 --fix 後複驗無異常）
  1 : 執行錯誤（檔案不存在、缺少套件等）
  2 : 發現異常且有可自動修正項目
  3 : 發現異常但皆為人工確認項（無可自動修正項目）
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import re
import sys
import unicodedata
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, List, Optional, Tuple

from embed_extractor import extract_embedded_pdfs
from excel_checker import CheckSummary, check_sheet, load_workbook_sheets
from excel_fixer import ExcelComSession, apply_fixes_via_excel_com
from fmd_matcher import DEFAULT_SAMSUNG_FMD_DIR, FMDRecord, load_fmd_index
from pdf_parser import MSDSData, TestReportData, parse_msds_pdf, parse_test_report_pdf
from svhc_checker import check_o202_workbook, is_o202_svhc_workbook


# ---------------------------------------------------------------------------
# 中英文對齊表格排版工具
# ---------------------------------------------------------------------------

def _display_width(text: str) -> int:
    w = 0
    for ch in text:
        w += 2 if unicodedata.east_asian_width(ch) in ("F", "W") else 1
    return w


def _format_progress_bar(
    current: int,
    total: int,
    prefix: str = "修正進度",
    detail: str = "",
    width: int = 28,
) -> str:
    """產生終端機進度條與百分比字串。"""
    safe_total = max(total, 1)
    clamped = max(0, min(current, safe_total))
    pct = (clamped / safe_total) * 100.0
    filled = int(round((clamped / safe_total) * width))
    bar = ("█" * filled) + ("░" * (width - filled))
    suffix = f" {detail}" if detail else ""
    return f"  {prefix} [{bar}] {pct:5.1f}% ({clamped}/{safe_total}){suffix}"


def _make_fix_progress_callback(prefix: str = "修正進度"):
    """建立可原地刷新的終端機進度條回呼，完成時自動換行。"""
    state = {"last_len": 0, "done_newline": False}

    def _callback(current: int, total: int, detail: str = "") -> None:
        line = _format_progress_bar(current, total, prefix=prefix, detail=detail)
        pad = max(0, state["last_len"] - _display_width(line))
        state["last_len"] = _display_width(line)
        is_final = detail == "完成" and current >= total
        end = "\n" if is_final else ""
        if is_final:
            state["done_newline"] = True
        print(f"\r{line}{' ' * pad}", end=end, flush=True)

    return _callback, state


def _pad_right(text: str, target_width: int) -> str:
    cur = _display_width(text)
    if cur >= target_width:
        return text
    return text + (" " * (target_width - cur))


def _truncate(text: str, max_width: int) -> str:
    if _display_width(text) <= max_width:
        return text
    out = []
    w = 0
    for ch in text:
        cw = 2 if unicodedata.east_asian_width(ch) in ("F", "W") else 1
        if w + cw > max_width - 1:
            break
        out.append(ch)
        w += cw
    return "".join(out) + "…"


def _wrap_text(text: str, max_width: int) -> List[str]:
    """依終端顯示寬度將長文字折行。"""
    if max_width <= 0:
        return [text]
    lines: List[str] = []
    cur = ""
    cur_w = 0
    for ch in text:
        cw = 2 if unicodedata.east_asian_width(ch) in ("F", "W") else 1
        if cur_w + cw > max_width and cur:
            lines.append(cur)
            cur = ""
            cur_w = 0
        cur += ch
        cur_w += cw
    if cur:
        lines.append(cur)
    return lines or [""]


def format_report_text(
    summary: CheckSummary,
    test_reports: List[TestReportData],
    msds_list: List[MSDSData],
    verbose: bool = False,
) -> str:
    headers = [
        ("等級", 6),
        ("工作表!位址", 18),
        ("項目名稱", 14),
        ("原填寫值", 16),
        ("報告正確值", 18),
        ("報告編號", 18),
        ("頁碼", 6),
        ("依據", 44),
    ]
    total_width = sum(w for _, w in headers) + 3 * (len(headers) - 1)
    sep = "=" * total_width
    thin = "-" * total_width

    lines: List[str] = []
    lines.append(sep)
    lines.append("O-193D 環境管理物質部件展開表 — 自動核對報告")
    lines.append(sep)
    lines.append(f"核對檔案：{os.path.abspath(summary.excel_path)}")
    lines.append(f"核對時間：{datetime.now().strftime('%Y/%m/%d %H:%M:%S')}")
    lines.append(
        f"內嵌 PDF：共 {summary.total_pdfs} 份"
        f"（第三方測試報告 {summary.test_report_count} 份、MSDS/成份表 {summary.msds_count} 份）"
    )
    lines.append(
        f"工作表：掃描 {len(summary.sheets_scanned)} 個、實際核對 {len(summary.sheets_checked)} 個"
    )
    lines.append(
        f"核對結果：錯誤 {summary.error_count} 項、警告 {summary.warning_count} 項"
        f"（可自動修正 {summary.fixable_count} 項）"
    )

    if summary.notes:
        lines.append(thin)
        for n in summary.notes:
            lines.append(f"  [資訊] {n}")

    if verbose and test_reports:
        lines.append(thin)
        lines.append("已解析第三方測試報告清單：")
        for r in test_reports:
            skip_info = f"，略過頁={r.pages_skipped}" if r.pages_skipped else ""
            lines.append(
                f"  - {r.filename} | 編號={r.report_no or '(未辨識)'} | 機構={r.agency or '-'} "
                f"| 日期={r.report_date or '-'} | 物質數={len(r.results)}{skip_info}"
            )

    if verbose and msds_list:
        lines.append(thin)
        lines.append("已解析 MSDS / 成份表清單：")
        for m in msds_list:
            lines.append(f"  - {m.filename} | 提取 CAS 數={len(m.cas_set)}")

    lines.append(sep)

    if not summary.issues:
        lines.append("✔ 所有已核對項目皆與內嵌 PDF 報告一致，未發現異常。")
        lines.append(sep)
        return "\n".join(lines)

    header_line = " | ".join(_pad_right(title, w) for title, w in headers)
    lines.append(header_line)
    lines.append(thin)

    evidence_width = headers[-1][1]
    evidence_indent = " " * (sum(w for _, w in headers[:-1]) + 3 * (len(headers) - 1))

    for iss in summary.issues:
        lvl = "錯誤" if iss.severity == "ERROR" else "警告"
        loc = (
            f"{iss.sheet_name}!{iss.cell_address}"
            if len(summary.sheets_checked) > 1
            else iss.cell_address
        )
        evidence = iss.evidence or "-"
        # 先折行，主列放第一行，後續行縮排顯示，避免截斷後重複輸出
        ev_chunks = _wrap_text(evidence, evidence_width)
        row_vals = [
            _truncate(lvl, headers[0][1]),
            _truncate(loc, headers[1][1]),
            _truncate(iss.item_name, headers[2][1]),
            _truncate(iss.actual_value, headers[3][1]),
            _truncate(iss.expected_value, headers[4][1]),
            _truncate(iss.report_no or "-", headers[5][1]),
            _truncate(iss.pdf_page or "-", headers[6][1]),
            ev_chunks[0],
        ]
        cells = [_pad_right(v, w) for v, w in zip(row_vals, (w for _, w in headers))]
        lines.append(" | ".join(cells))

        for chunk in ev_chunks[1:]:
            lines.append(evidence_indent + chunk)

    lines.append(sep)
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# 套件檢查
# ---------------------------------------------------------------------------

_WARNED_DEFUSEDXML = False


def _check_dependencies(excel_path: str, need_fix: bool) -> List[str]:
    global _WARNED_DEFUSEDXML
    missing: List[str] = []
    ext = os.path.splitext(excel_path)[1].lower()
    checks: List[Tuple[str, str]] = [
        ("olefile", "olefile"),
    ]
    if importlib.util.find_spec("pymupdf") is None and importlib.util.find_spec("fitz") is None:
        missing.append("PyMuPDF")
    if ext == ".xls":
        checks.append(("xlrd", "xlrd"))
    else:
        checks.append(("openpyxl", "openpyxl"))
        if not _WARNED_DEFUSEDXML and importlib.util.find_spec("defusedxml") is None:
            _WARNED_DEFUSEDXML = True
            print(
                "[警告] 未安裝 defusedxml，解析外部 .xlsx/.xlsm 時將缺少 XML 安全防護；"
                "建議執行 pip install defusedxml。",
                file=sys.stderr,
            )
    if need_fix:
        checks.append(("win32com", "pywin32"))
        checks.append(("pythoncom", "pywin32"))

    for mod_name, pip_name in checks:
        if importlib.util.find_spec(mod_name) is None and pip_name not in missing:
            missing.append(pip_name)
    return missing


# ---------------------------------------------------------------------------
# 主程式
# ---------------------------------------------------------------------------

_EXCEL_EXTS = (".xls", ".xlsx", ".xlsm")
_SKIP_SHEET_NAME_RE = re.compile(r"範例|範本|Example|Template", re.IGNORECASE)

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUTPUT_DIR = os.path.join(SCRIPT_DIR, "output")
DEFAULT_REPORT_DIR = os.path.join(SCRIPT_DIR, "reports")
DEFAULT_BACKUP_DIR = os.path.join(SCRIPT_DIR, "backup")


def _resolve_dir(raw: Optional[str], default_dir: str) -> str:
    return os.path.abspath(os.path.expanduser(raw)) if raw else os.path.abspath(default_dir)


def _make_report_path(
    excel_path: str,
    report_dir: str,
    suffix: str,
    used_paths: Optional[set] = None,
) -> str:
    os.makedirs(report_dir, exist_ok=True)
    stem = os.path.splitext(os.path.basename(excel_path))[0]
    candidate = os.path.join(report_dir, f"{stem}_{suffix}.txt")
    if used_paths is None:
        return candidate
    key = os.path.normcase(os.path.abspath(candidate))
    if key not in used_paths:
        used_paths.add(key)
        return candidate
    idx = 2
    while True:
        cand = os.path.join(report_dir, f"{stem}_{suffix}_{idx}.txt")
        cand_key = os.path.normcase(os.path.abspath(cand))
        if cand_key not in used_paths:
            used_paths.add(cand_key)
            return cand
        idx += 1


@dataclass
class FileCheckResult:
    """單一 Excel 檔案的核對結果（供單檔、批次與互動修正共用）。"""
    path: str
    exit_code: int
    ok: bool = True
    error: str = ""
    summary: Optional[CheckSummary] = None
    verify_summary: Optional[CheckSummary] = None
    report_text: str = ""
    verify_text: str = ""
    pre_messages: List[str] = field(default_factory=list)
    post_messages: List[str] = field(default_factory=list)
    saved_report: str = ""
    saved_verify_report: str = ""
    saved_output_path: str = ""
    fixed_count: int = 0
    # 暫存已解析的 PDF 供 --interactive-fix 直接沿用，免去二次解析
    test_reports: List[TestReportData] = field(default_factory=list)
    msds_list: List[MSDSData] = field(default_factory=list)
    total_pdfs: int = 0
    is_o202: bool = False
    extra_o193d_paths: List[str] = field(default_factory=list)


def _is_excel_file(path: str) -> bool:
    name = os.path.basename(path)
    return (
        os.path.splitext(name)[1].lower() in _EXCEL_EXTS
        and not name.startswith("~$")
    )


def _collect_excel_targets(
    inputs: List[str],
    recursive: bool,
    exclude_dirs: Optional[List[str]] = None,
) -> Tuple[List[str], List[str]]:
    targets: List[str] = []
    errors: List[str] = []
    seen = set()
    excluded = {
        os.path.normcase(os.path.abspath(d))
        for d in (exclude_dirs or [])
        if d
    }

    def add(path: str) -> None:
        abs_path = os.path.abspath(path)
        key = os.path.normcase(abs_path)
        if key in seen:
            return
        seen.add(key)
        targets.append(abs_path)

    for raw in inputs:
        path = os.path.abspath(os.path.expanduser(raw))
        if os.path.isdir(path):
            found: List[str] = []
            if recursive:
                for root, dirs, files in os.walk(path):
                    dirs[:] = [
                        d for d in dirs
                        if not d.startswith(".")
                        and os.path.normcase(os.path.abspath(os.path.join(root, d))) not in excluded
                    ]
                    for name in files:
                        if _is_excel_file(name):
                            found.append(os.path.join(root, name))
            else:
                try:
                    with os.scandir(path) as entries:
                        for entry in entries:
                            if entry.is_file() and _is_excel_file(entry.name):
                                found.append(entry.path)
                except OSError as exc:
                    errors.append(f"無法讀取資料夾 {path}：{exc}")
                    continue
            if not found:
                errors.append(f"資料夾內沒有 Excel 檔案：{path}")
            for item in sorted(found, key=lambda p: p.lower()):
                add(item)
        elif os.path.isfile(path):
            if not _is_excel_file(path):
                errors.append(f"不支援的檔案格式（僅 .xls/.xlsx/.xlsm）：{path}")
            else:
                add(path)
        else:
            errors.append(f"找不到檔案或資料夾：{path}")

    return targets, errors


def _failure(path: str, message: str) -> FileCheckResult:
    return FileCheckResult(path=path, exit_code=1, ok=False, error=message)


def _set_error(result: FileCheckResult, message: str) -> FileCheckResult:
    result.ok = False
    result.exit_code = 1
    result.error = message
    return result


def reverify_result(
    result: FileCheckResult,
    args,
    used_verify_paths: Optional[set] = None,
    fmd_records: Optional[List[FMDRecord]] = None,
) -> FileCheckResult:
    """重新讀取最新輸出檔（或原檔）進行複驗並更新複驗報告。"""
    target_path = result.saved_output_path or result.path
    report_dir = _resolve_dir(getattr(args, "report_dir", None), DEFAULT_REPORT_DIR)

    try:
        sheets2 = load_workbook_sheets(target_path, target_sheet=args.sheet)
    except Exception as exc:
        return _set_error(result, f"讀取複驗檔案失敗：{exc}")

    if result.is_o202:
        verify = check_o202_workbook(
            target_path,
            sheets2,
            fmd_records=fmd_records,
            extra_o193d_paths=result.extra_o193d_paths,
            include_example=args.include_example,
            target_sheet=args.sheet,
        )
    else:
        verify = CheckSummary(
            excel_path=target_path,
            total_pdfs=result.total_pdfs,
            test_report_count=len(result.test_reports),
            msds_count=len(result.msds_list),
        )
        for sh in sheets2:
            verify.sheets_scanned.append(sh.name)
            if (
                not args.include_example
                and _SKIP_SHEET_NAME_RE.search(sh.name)
                and (args.sheet is None or args.sheet != sh.name)
            ):
                verify.notes.append(
                    f"工作表 [{sh.name}] 為範例/範本，已略過（可用 --include-example 納入）。"
                )
                continue
            sh_issues, sh_notes, checked = check_sheet(
                sh, result.test_reports, result.msds_list, fmd_records=fmd_records, excel_path=result.path
            )
            verify.issues.extend(sh_issues)
            verify.notes.extend(sh_notes)
            if checked:
                verify.sheets_checked.append(sh.name)

    if not verify.sheets_checked:
        return _set_error(result, "修正後複驗找不到可核對的工作表。")

    result.verify_summary = verify
    result.verify_text = format_report_text(
        verify, result.test_reports, result.msds_list, verbose=args.verbose
    )
    if args.save_report:
        verify_path = _make_report_path(
            result.path, report_dir, "核對報告_修正後複驗", used_paths=used_verify_paths
        )
        try:
            with open(verify_path, "w", encoding="utf-8-sig", newline="\r\n") as f:
                f.write(result.verify_text + "\n")
        except OSError as exc:
            return _set_error(result, f"寫入修正後複驗報告失敗：{exc}")
        result.saved_verify_report = verify_path
        result.post_messages.append(f"[已存檔] 修正後複驗報告：{verify_path}")

    if verify.issues:
        result.exit_code = 2 if verify.fixable_count > 0 else 3
        result.post_messages.append(
            f"[提醒] 修正後仍有錯誤 {verify.error_count} 項、警告 {verify.warning_count} 項，"
            "請依上方複驗報告確認。"
        )
    else:
        result.exit_code = 0
        result.post_messages.append("[完成] 修正後複驗：未發現異常。")
    return result


def fix_and_verify_result(
    result: FileCheckResult,
    args,
    excel_app: Any = None,
    used_verify_paths: Optional[set] = None,
    fmd_records: Optional[List[FMDRecord]] = None,
    progress_prefix: str = "修正進度",
) -> FileCheckResult:
    """
    對已完成核對的 FileCheckResult 執行 Excel COM 修正與複驗。
    直接沿用記憶體中的 test_reports / msds_list，避免重複解析 PDF。
    支援多輪連續修正：若已有 saved_output_path，直接針對該輸出檔繼續修正而不重複備份。
    """
    active_summary = result.verify_summary or result.summary
    if not result.ok or active_summary is None:
        return result

    if active_summary.fixable_count == 0:
        result.pre_messages.append("[提示] 沒有可自動修正的項目，未產生修正後檔案。")
        result.exit_code = 0 if not active_summary.issues else 3
        return result

    missing = _check_dependencies(result.path, need_fix=True)
    if missing:
        return _set_error(
            result,
            f"缺少自動修正必要套件：{', '.join(missing)}；請先執行 pip install {' '.join(missing)}",
        )

    output_dir = _resolve_dir(getattr(args, "output_dir", None), DEFAULT_OUTPUT_DIR)
    backup_dir = _resolve_dir(getattr(args, "backup_dir", None), DEFAULT_BACKUP_DIR)
    is_followup_round = bool(result.saved_output_path and os.path.isfile(result.saved_output_path))

    result.pre_messages.append(
        f"\n正在透過 Excel COM 自動修正 {active_summary.fixable_count} 個儲存格並標記紅字..."
    )
    progress_cb, progress_state = _make_fix_progress_callback(prefix=progress_prefix)
    try:
        fix_res = apply_fixes_via_excel_com(
            result.path,
            active_summary.issues,
            excel_app=excel_app,
            output_dir=output_dir,
            backup_dir=backup_dir,
            in_place_target=result.saved_output_path if is_followup_round else None,
            create_backup=not is_followup_round,
            progress_cb=progress_cb,
        )
    except Exception as exc:
        if progress_state["last_len"] and not progress_state["done_newline"]:
            print("", flush=True)
        return _set_error(result, f"Excel COM 自動修正失敗：{exc}")
    if progress_state["last_len"] and not progress_state["done_newline"]:
        print("", flush=True)

    result.fixed_count += fix_res.fixed_count
    result.saved_output_path = fix_res.saved_path
    if fix_res.backup_path:
        result.pre_messages.append(f"[備份] 修正前原檔已備份至：{fix_res.backup_path}")
    if is_followup_round:
        result.pre_messages.append(
            f"[完成] 已更新修正後檔案：{fix_res.saved_path}\n"
            f"       本輪共修正 {fix_res.fixed_count} 個儲存格（已套用紅色字體）。"
        )
    elif fix_res.overwrote_existing:
        result.pre_messages.append(
            f"[完成] 已保留原檔，比對 [Foxlink產品料號+供應商產品料號] 相同，已直接覆蓋舊版檔案：{fix_res.saved_path}\n"
            f"       共修正 {fix_res.fixed_count} 個儲存格（已套用紅色字體）。"
        )
    else:
        result.pre_messages.append(
            f"[完成] 已保留原檔，修正後檔案已輸出至：{fix_res.saved_path}\n"
            f"       共修正 {fix_res.fixed_count} 個儲存格（已套用紅色字體）。"
        )
    if fix_res.skipped_items:
        result.pre_messages.append(f"[警告] 以下 {len(fix_res.skipped_items)} 個儲存格修正略過：")
        for ref, reason in fix_res.skipped_items:
            result.pre_messages.append(f"  - {ref}: {reason}")

    result.pre_messages.append("\n正在複驗修正後的檔案...")
    return reverify_result(
        result,
        args,
        used_verify_paths=used_verify_paths,
        fmd_records=fmd_records,
    )


def process_file(
    excel_path: str,
    args,
    excel_app: Any = None,
    do_fix: Optional[bool] = None,
    used_report_paths: Optional[set] = None,
    fmd_records: Optional[List[FMDRecord]] = None,
    fmd_init_notes: Optional[List[str]] = None,
    extra_o193d_paths: Optional[List[str]] = None,
) -> FileCheckResult:
    """核對單一 Excel；所有輸出先收集在 FileCheckResult，由 main 決定顯示方式。"""
    excel_path = os.path.abspath(excel_path)
    if not os.path.isfile(excel_path):
        return _failure(excel_path, f"找不到檔案：{excel_path}")

    run_fix = args.fix if do_fix is None else do_fix
    missing = _check_dependencies(excel_path, run_fix)
    if missing:
        return _failure(
            excel_path,
            f"缺少必要 Python 套件：{', '.join(missing)}；請先執行 pip install {' '.join(missing)}",
        )

    try:
        embedded_pdfs = extract_embedded_pdfs(excel_path)
    except Exception as exc:
        return _failure(excel_path, f"提取內嵌 PDF 失敗：{exc}")

    test_reports: List[TestReportData] = []
    msds_list: List[MSDSData] = []
    for pdf in embedded_pdfs:
        try:
            if pdf.category == "msds":
                msds_list.append(parse_msds_pdf(pdf.data, pdf.filename, pdf.source_entry))
            else:
                rep = parse_test_report_pdf(pdf.data, pdf.filename, pdf.source_entry)
                if pdf.category == "test_report" or rep.report_no or rep.results:
                    test_reports.append(rep)
        except Exception as exc:
            print(f"[警告] 解析 PDF ({pdf.filename}) 時發生例外：{exc}", file=sys.stderr)

    summary = CheckSummary(
        excel_path=excel_path,
        total_pdfs=len(embedded_pdfs),
        test_report_count=len(test_reports),
        msds_count=len(msds_list),
    )
    result = FileCheckResult(
        path=excel_path,
        exit_code=1,
        summary=summary,
        test_reports=test_reports,
        msds_list=msds_list,
        total_pdfs=len(embedded_pdfs),
    )

    if not embedded_pdfs:
        summary.notes.append("未在 Excel 中找到內嵌 PDF，所有報告相關核對將略過。")
    for note in (fmd_init_notes or []):
        summary.notes.append(note)
    for rep in test_reports:
        for note in rep.notes:
            summary.notes.append(f"[{rep.filename}] {note}")

    try:
        sheets = load_workbook_sheets(excel_path, target_sheet=args.sheet)
    except Exception as exc:
        return _set_error(result, f"讀取 Excel 檔案失敗：{exc}")

    if args.sheet and not sheets:
        return _set_error(result, f"找不到指定的工作表：{args.sheet}")
    if not sheets:
        return _set_error(result, "活頁簿中沒有可讀取的工作表。")

    if is_o202_svhc_workbook(sheets):
        o202_summary = check_o202_workbook(
            excel_path,
            sheets,
            fmd_records=fmd_records,
            extra_o193d_paths=extra_o193d_paths or (),
            include_example=args.include_example,
            target_sheet=args.sheet,
        )
        o202_summary.total_pdfs = len(embedded_pdfs)
        o202_summary.test_report_count = len(test_reports)
        o202_summary.msds_count = len(msds_list)
        for note in (fmd_init_notes or []):
            o202_summary.notes.append(note)
        summary = o202_summary
        result.summary = summary
        result.is_o202 = True
        result.extra_o193d_paths = list(extra_o193d_paths or ())
    else:
        for sh in sheets:
            summary.sheets_scanned.append(sh.name)
            if (
                not args.include_example
                and _SKIP_SHEET_NAME_RE.search(sh.name)
                and (args.sheet is None or args.sheet != sh.name)
            ):
                summary.notes.append(
                    f"工作表 [{sh.name}] 為範例/範本，已略過（可用 --include-example 納入）。"
                )
                continue
            sh_issues, sh_notes, checked = check_sheet(
                sh, test_reports, msds_list, fmd_records=fmd_records, excel_path=excel_path
            )
            summary.issues.extend(sh_issues)
            summary.notes.extend(sh_notes)
            if checked:
                summary.sheets_checked.append(sh.name)

    if not summary.sheets_checked:
        return _set_error(
            result,
            "沒有核對任何工作表：找不到含「部件名稱 / Part name」或「REACH SVHC」表頭的工作表，"
            "請確認檔案格式或改用 --sheet 指定。",
        )

    result.report_text = format_report_text(summary, test_reports, msds_list, verbose=args.verbose)

    if args.save_report:
        report_dir = _resolve_dir(getattr(args, "report_dir", None), DEFAULT_REPORT_DIR)
        report_path = _make_report_path(
            excel_path, report_dir, "核對報告", used_paths=used_report_paths
        )
        try:
            with open(report_path, "w", encoding="utf-8-sig", newline="\r\n") as f:
                f.write(result.report_text + "\n")
        except OSError as exc:
            return _set_error(result, f"寫入核對報告失敗：{exc}")
        result.saved_report = report_path
        result.pre_messages.append(f"[已存檔] 核對報告：{report_path}")

    if not run_fix:
        result.exit_code = 0 if not summary.issues else (2 if summary.fixable_count > 0 else 3)
        return result

    return fix_and_verify_result(result, args, excel_app=excel_app, fmd_records=fmd_records)


def _result_status(result: FileCheckResult) -> str:
    if not result.ok:
        return "失敗"
    if result.exit_code == 0:
        return "已修正" if result.fixed_count else "通過"
    if result.exit_code == 2:
        return "可修正"
    if result.exit_code == 3:
        return "待確認"
    return f"結束碼 {result.exit_code}"


def _result_counts(result: FileCheckResult) -> Tuple[int, int, int]:
    summary = result.verify_summary or result.summary
    if summary is None:
        return 0, 0, 0
    return summary.error_count, summary.warning_count, summary.fixable_count


def _print_single_result(result: FileCheckResult) -> None:
    if result.report_text:
        print(result.report_text)
    for message in result.pre_messages:
        print(message)
    if result.error:
        print(f"[錯誤] {result.error}", file=sys.stderr)
    if result.verify_text:
        print(result.verify_text)
    for message in result.post_messages:
        print(message)


def _print_batch_detail(result: FileCheckResult) -> None:
    sep = "=" * 88
    print(sep)
    print(f"檔案：{result.path}")
    print(sep)
    _print_single_result(result)


def format_batch_summary(results: List[FileCheckResult], input_errors: List[str]) -> str:
    headers = [
        ("狀態", 8),
        ("檔案", 52),
        ("錯誤", 6),
        ("警告", 6),
        ("可修正", 8),
        ("已修正", 8),
    ]
    total_width = sum(w for _, w in headers) + 3 * (len(headers) - 1)
    sep = "=" * total_width
    thin = "-" * total_width

    ok_count = sum(1 for r in results if r.ok)
    failed_count = len(results) - ok_count
    total_errors = total_warnings = total_fixable = 0
    fixed_total = 0
    for result in results:
        err, warn, fixable = _result_counts(result)
        total_errors += err
        total_warnings += warn
        total_fixable += fixable
        fixed_total += result.fixed_count

    lines = [sep, "批次核對摘要", sep]
    lines.append(f"執行時間：{datetime.now().strftime('%Y/%m/%d %H:%M:%S')}")
    lines.append(f"目標檔案：{len(results)} 個（成功 {ok_count}、失敗 {failed_count}）")
    if input_errors:
        lines.append(f"輸入錯誤：{len(input_errors)} 個")
    lines.append(
        f"合計：錯誤 {total_errors}、警告 {total_warnings}、可修正 {total_fixable}、已修正 {fixed_total}"
    )
    lines.append(thin)
    lines.append(" | ".join(_pad_right(title, w) for title, w in headers))
    lines.append(thin)

    for result in results:
        err, warn, fixable = _result_counts(result)
        cells = [
            _result_status(result),
            _truncate(result.path, 52),
            str(err),
            str(warn),
            str(fixable),
            str(result.fixed_count),
        ]
        lines.append(" | ".join(_pad_right(value, w) for value, (_, w) in zip(cells, headers)))

    if input_errors:
        lines.append(thin)
        lines.append("輸入錯誤：")
        for message in input_errors:
            lines.append(f"  - {message}")

    failures = [r for r in results if not r.ok]
    if failures:
        lines.append(thin)
        lines.append("失敗原因：")
        for result in failures:
            lines.append(f"  - {result.path}：{result.error}")

    lines.append(sep)
    return "\n".join(lines)


def _batch_report_path(report_dir: str) -> str:
    os.makedirs(report_dir, exist_ok=True)
    return os.path.join(report_dir, "批次核對摘要.txt")


def _aggregate_exit_code(results: List[FileCheckResult], input_errors: List[str]) -> int:
    if input_errors or any(not r.ok for r in results):
        return 1
    codes = [r.exit_code for r in results]
    if 2 in codes:
        return 2
    if 3 in codes:
        return 3
    return 0


def _run_batch_fixes(
    results: List[FileCheckResult],
    args,
    batch_mode: bool,
    fmd_records: Optional[List[FMDRecord]] = None,
) -> None:
    """使用單一 ExcelComSession 批次修正所有可修正項目，減少 COM 啟閉開銷。"""
    targets = [
        r for r in results
        if r.ok and (r.verify_summary or r.summary) and (r.verify_summary or r.summary).fixable_count > 0
    ]
    if not targets:
        return

    used_verify_paths: set = set()
    try:
        with ExcelComSession() as excel_app:
            for idx, res in enumerate(targets, 1):
                if batch_mode:
                    print(f"[修正 {idx}/{len(targets)}] {res.saved_output_path or res.path}", flush=True)
                pre_len = len(res.pre_messages)
                post_len = len(res.post_messages)
                prefix = f"修正進度 [{idx}/{len(targets)}]" if batch_mode else "修正進度"
                fix_and_verify_result(
                    res,
                    args,
                    excel_app=excel_app,
                    used_verify_paths=used_verify_paths,
                    fmd_records=fmd_records,
                    progress_prefix=prefix,
                )
                if not batch_mode:
                    for msg in res.pre_messages[pre_len:]:
                        print(msg)
                    if res.error:
                        print(f"[錯誤] {res.error}", file=sys.stderr)
                    if res.verify_text:
                        print(res.verify_text)
                    for msg in res.post_messages[post_len:]:
                        print(msg)
                else:
                    err, warn, fixable = _result_counts(res)
                    print(
                        f"        {_result_status(res)} | 已修正 {res.fixed_count}、剩餘錯誤 {err}、警告 {warn}、可修正 {fixable}",
                        flush=True,
                    )
                    if not res.ok and res.error:
                        print(f"        [錯誤] {res.error}", file=sys.stderr)
    except Exception as exc:
        print(f"[錯誤] 啟動 Excel COM 失敗：{exc}", file=sys.stderr)
        for res in targets:
            if res.fixed_count == 0 and res.ok:
                _set_error(res, f"啟動 Excel COM 失敗：{exc}")


def _run_batch_reverify(
    results: List[FileCheckResult],
    args,
    batch_mode: bool,
    fmd_records: Optional[List[FMDRecord]] = None,
) -> None:
    """對目前仍有錯誤的檔案重新讀取複驗（供人工修正後再次驗證）。"""
    targets = [
        r for r in results
        if r.ok and (r.verify_summary or r.summary) and (r.verify_summary or r.summary).error_count > 0
    ]
    if not targets:
        return
    used_verify_paths: set = set()
    for idx, res in enumerate(targets, 1):
        if batch_mode:
            print(f"[複驗 {idx}/{len(targets)}] {res.saved_output_path or res.path}", flush=True)
        post_len = len(res.post_messages)
        reverify_result(
            res,
            args,
            used_verify_paths=used_verify_paths,
            fmd_records=fmd_records,
        )
        if not batch_mode:
            if res.error:
                print(f"[錯誤] {res.error}", file=sys.stderr)
            if res.verify_text:
                print(res.verify_text)
            for msg in res.post_messages[post_len:]:
                print(msg)
        else:
            err, warn, fixable = _result_counts(res)
            print(
                f"        {_result_status(res)} | 剩餘錯誤 {err}、警告 {warn}、可修正 {fixable}",
                flush=True,
            )


def main(argv: List[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except Exception:
                pass

    parser = argparse.ArgumentParser(
        description="O-193D 環境管理物質部件展開表 — 內嵌 PDF 自動核對與修正工具（支援批次）"
    )
    parser.add_argument(
        "excel_file",
        nargs="+",
        help="目標 .xls/.xlsx/.xlsm 檔案或資料夾；可一次指定多個",
    )
    parser.add_argument(
        "--save-report",
        action="store_true",
        help="將每份核對結果另存至報告資料夾（預設 reports/）；批次模式另存「批次核對摘要.txt」",
    )
    parser.add_argument(
        "--output-dir",
        default=DEFAULT_OUTPUT_DIR,
        help="比對／修正後的檔案輸出資料夾（預設：工具目錄下的 output/）",
    )
    parser.add_argument(
        "--report-dir",
        default=DEFAULT_REPORT_DIR,
        help="比對後的報告輸出資料夾（預設：工具目錄下的 reports/）",
    )
    parser.add_argument(
        "--backup-dir",
        default=DEFAULT_BACKUP_DIR,
        help="比對／修正前的原始檔案備份資料夾（預設：工具目錄下的 backup/）",
    )
    parser.add_argument(
        "--fmd-dir",
        default=DEFAULT_SAMSUNG_FMD_DIR,
        help="三星原廠成分表（SAMSUNG FMD）資料夾路徑，用於比對欄位 B~J",
    )
    parser.add_argument(
        "--no-fmd",
        action="store_true",
        help="停用欄位 B~J 與 SAMSUNG FMD 成分表的比對",
    )
    parser.add_argument(
        "--fix",
        action="store_true",
        help="透過 Windows Excel COM 自動修正錯誤儲存格並標記紅字 RGB(255,0,0)（保留原檔，輸出至 output/）",
    )
    parser.add_argument(
        "--interactive-fix",
        action="store_true",
        help="核對完成後若有可修正項目，於同一次執行內詢問是否修正（免去重複解析 PDF）",
    )
    parser.add_argument(
        "--sheet",
        default=None,
        help="指定只核對單一工作表名稱（套用到所有目標檔案）",
    )
    parser.add_argument(
        "--recursive",
        action="store_true",
        help="輸入資料夾時遞迴搜尋子資料夾內的 Excel 檔案",
    )
    parser.add_argument(
        "--include-example",
        action="store_true",
        help="一併核對名稱含「範例/範本/Example/Template」的工作表（預設略過）",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="顯示已解析之內嵌 PDF 報告與 MSDS 清單；批次模式另顯示各檔完整報告",
    )
    args = parser.parse_args(argv)
    output_dir = _resolve_dir(args.output_dir, DEFAULT_OUTPUT_DIR)
    report_dir = _resolve_dir(args.report_dir, DEFAULT_REPORT_DIR)
    backup_dir = _resolve_dir(args.backup_dir, DEFAULT_BACKUP_DIR)
    args.output_dir = output_dir
    args.report_dir = report_dir
    args.backup_dir = backup_dir

    targets, input_errors = _collect_excel_targets(
        args.excel_file,
        args.recursive,
        exclude_dirs=[output_dir, report_dir, backup_dir],
    )
    for message in input_errors:
        print(f"[錯誤] {message}", file=sys.stderr)
    if not targets:
        if not input_errors:
            print("[錯誤] 沒有可核對的 Excel 檔案。", file=sys.stderr)
        return 1

    batch_mode = len(targets) > 1 or bool(input_errors)
    results: List[FileCheckResult] = []
    used_report_paths: set = set()

    fmd_records: List[FMDRecord] = []
    fmd_init_notes: List[str] = []
    if not args.no_fmd and args.fmd_dir:
        fmd_records, fmd_init_notes = load_fmd_index(args.fmd_dir)

    batch_o193d_paths = [
        p for p in targets if re.search(r"O-?193D", os.path.basename(p), re.IGNORECASE)
    ]

    # 階段 1：先全部完成 PDF 提取與核對（批次 --fix 也統一在階段 2 共用同一個 Excel COM）
    for index, path in enumerate(targets, 1):
        if batch_mode:
            print(f"[{index}/{len(targets)}] 核對：{path}", flush=True)
        result = process_file(
            path,
            args,
            do_fix=False,
            used_report_paths=used_report_paths,
            fmd_records=fmd_records,
            fmd_init_notes=fmd_init_notes,
            extra_o193d_paths=batch_o193d_paths,
        )
        results.append(result)
        if batch_mode:
            err, warn, fixable = _result_counts(result)
            print(
                f"        {_result_status(result)} | 錯誤 {err}、警告 {warn}、可修正 {fixable}",
                flush=True,
            )
            if not result.ok and result.error:
                print(f"        [錯誤] {result.error}", file=sys.stderr)

    # 顯示初檢結果
    if not batch_mode:
        _print_single_result(results[0])
    else:
        if args.verbose:
            for result in results:
                _print_batch_detail(result)
        summary_text = format_batch_summary(results, input_errors)
        print(summary_text)
        if args.save_report:
            summary_path = _batch_report_path(report_dir)
            try:
                with open(summary_path, "w", encoding="utf-8-sig", newline="\r\n") as f:
                    f.write(summary_text + "\n")
            except OSError as exc:
                print(f"[錯誤] 寫入批次核對摘要失敗：{exc}", file=sys.stderr)
                return 1
            print(f"[已存檔] 批次核對摘要：{summary_path}")

    # 階段 2：判斷是否執行自動修正（--fix 直接執行；--interactive-fix 只要有可修正項就詢問，不被單檔失敗阻斷）
    fixable_files = [r for r in results if r.ok and r.summary and r.summary.fixable_count > 0]
    total_fixable = sum(r.summary.fixable_count for r in fixable_files if r.summary)

    should_fix = False
    if args.fix:
        if total_fixable > 0:
            should_fix = True
        elif not batch_mode and results[0].ok:
            print("[提示] 沒有可自動修正的項目，未產生修正後檔案。")
    elif args.interactive_fix and total_fixable > 0:
        print("-" * 76)
        if batch_mode:
            print(
                f"批次中共有 {len(fixable_files)} 個檔案、{total_fixable} 個項目可自動修正"
                f"（將保留原檔，備份至 {backup_dir}、修正後輸出至 {output_dir}；失敗檔案將略過）。"
            )
        try:
            ans = input("是否立即執行自動修正並標記紅字？(Y/N): ").strip()
        except EOFError:
            ans = ""
        if ans.lower() == "y":
            should_fix = True
        else:
            print("已取消自動修正。")

    if should_fix:
        _run_batch_fixes(results, args, batch_mode=batch_mode, fmd_records=fmd_records)
        if batch_mode:
            summary_text = format_batch_summary(results, input_errors)
            print(summary_text)
            if args.save_report:
                summary_path = _batch_report_path(report_dir)
                try:
                    with open(summary_path, "w", encoding="utf-8-sig", newline="\r\n") as f:
                        f.write(summary_text + "\n")
                except OSError as exc:
                    print(f"[錯誤] 更新批次核對摘要失敗：{exc}", file=sys.stderr)
                    return 1
                print(f"[已更新] 批次核對摘要：{summary_path}")

        # 互動模式：若修正後仍有錯誤，持續詢問是否再次修正／重新複驗，直到無錯誤或使用者取消
        while args.interactive_fix:
            rem_fixable_files = [
                r for r in results
                if r.ok and (r.verify_summary or r.summary) and (r.verify_summary or r.summary).fixable_count > 0
            ]
            rem_error_files = [
                r for r in results
                if r.ok and (r.verify_summary or r.summary) and (r.verify_summary or r.summary).error_count > 0
            ]
            rem_fixable_total = sum(
                (r.verify_summary or r.summary).fixable_count for r in rem_fixable_files
            )
            rem_error_total = sum(
                (r.verify_summary or r.summary).error_count for r in rem_error_files
            )

            if rem_fixable_total == 0 and rem_error_total == 0:
                break

            print("-" * 76)
            if rem_fixable_total > 0:
                try:
                    ans2 = input(
                        f"[提醒] 修正後仍有錯誤 {rem_error_total} 項（其中 {rem_fixable_total} 項可自動修正）。"
                        "是否再次執行自動修正？(Y/N): "
                    ).strip()
                except EOFError:
                    ans2 = ""
                if ans2.lower() != "y":
                    print("已結束自動修正。")
                    break
                _run_batch_fixes(results, args, batch_mode=batch_mode, fmd_records=fmd_records)
            else:
                try:
                    ans2 = input(
                        f"[提醒] 修正後仍有錯誤 {rem_error_total} 項（需人工修改檔案）。"
                        "若已手動修正，請輸入 R 重新複驗，或直接按 Enter 結束: "
                    ).strip()
                except EOFError:
                    ans2 = ""
                if ans2.lower() not in ("r", "y"):
                    print("已結束核對。")
                    break
                _run_batch_reverify(results, args, batch_mode=batch_mode, fmd_records=fmd_records)

            if batch_mode:
                summary_text = format_batch_summary(results, input_errors)
                print(summary_text)
                if args.save_report:
                    summary_path = _batch_report_path(report_dir)
                    try:
                        with open(summary_path, "w", encoding="utf-8-sig", newline="\r\n") as f:
                            f.write(summary_text + "\n")
                    except OSError as exc:
                        print(f"[錯誤] 更新批次核對摘要失敗：{exc}", file=sys.stderr)
                        return 1
                    print(f"[已更新] 批次核對摘要：{summary_path}")

    if not batch_mode:
        return results[0].exit_code
    return _aggregate_exit_code(results, input_errors)


if __name__ == "__main__":
    sys.exit(main())
