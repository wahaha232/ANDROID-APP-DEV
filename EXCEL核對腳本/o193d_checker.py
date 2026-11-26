# -*- coding: utf-8 -*-
"""
O-193D 環境管理物質部件展開表 — 自動核對與修正主程式

用法：
  python o193d_checker.py <Excel檔案路徑> [--save-report] [--fix] [--sheet 工作表名] [--verbose]

結束代碼 (Exit Code)：
  0 : 核對完成且無異常（或 --fix 執行完成）
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
from typing import List, Optional, Tuple

from embed_extractor import extract_embedded_pdfs
from excel_checker import CheckSummary, check_sheet, load_workbook_sheets
from excel_fixer import apply_fixes_via_excel_com
from pdf_parser import MSDSData, TestReportData, parse_msds_pdf, parse_test_report_pdf


# ---------------------------------------------------------------------------
# 中英文對齊表格排版工具
# ---------------------------------------------------------------------------

def _display_width(text: str) -> int:
    w = 0
    for ch in text:
        w += 2 if unicodedata.east_asian_width(ch) in ("F", "W") else 1
    return w


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
        row_vals = [
            _truncate(lvl, headers[0][1]),
            _truncate(loc, headers[1][1]),
            _truncate(iss.item_name, headers[2][1]),
            _truncate(iss.actual_value, headers[3][1]),
            _truncate(iss.expected_value, headers[4][1]),
            _truncate(iss.report_no or "-", headers[5][1]),
            _truncate(iss.pdf_page or "-", headers[6][1]),
            _truncate(evidence, evidence_width),
        ]
        cells = [_pad_right(v, w) for v, w in zip(row_vals, (w for _, w in headers))]
        lines.append(" | ".join(cells))

        # 依據過長時折行顯示，避免破壞表格對齊
        if _display_width(evidence) > evidence_width:
            for chunk in _wrap_text(evidence, evidence_width):
                lines.append(evidence_indent + chunk)

    lines.append(sep)
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# 套件檢查
# ---------------------------------------------------------------------------

def _check_dependencies(excel_path: str, need_fix: bool) -> List[str]:
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
    if need_fix:
        checks.append(("win32com", "pywin32"))
        checks.append(("pythoncom", "pywin32"))

    for mod_name, pip_name in checks:
        if importlib.util.find_spec(mod_name) is None:
            missing.append(pip_name)
    return missing


# ---------------------------------------------------------------------------
# 主程式
# ---------------------------------------------------------------------------

_EXCEL_EXTS = (".xls", ".xlsx", ".xlsm")
_SKIP_SHEET_NAME_RE = re.compile(r"範例|範本|Example|Template", re.IGNORECASE)


@dataclass
class FileCheckResult:
    """單一 Excel 檔案的核對結果（供單檔與批次輸出共用）。"""
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
    fixed_count: int = 0


def _is_excel_file(path: str) -> bool:
    name = os.path.basename(path)
    return (
        os.path.splitext(name)[1].lower() in _EXCEL_EXTS
        and not name.startswith("~$")
    )


def _collect_excel_targets(inputs: List[str], recursive: bool) -> Tuple[List[str], List[str]]:
    """
    展開命令列輸入：檔案、資料夾（可遞迴）皆可，並去除重複與 Excel 暫存鎖檔。
    回傳 (實際要核對的檔案清單, 輸入錯誤訊息)。
    """
    targets: List[str] = []
    errors: List[str] = []
    seen = set()

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
                    dirs[:] = [d for d in dirs if not d.startswith(".")]
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


def process_file(excel_path: str, args) -> FileCheckResult:
    """核對單一 Excel；所有輸出先收集在 FileCheckResult，由 main 決定顯示方式。"""
    excel_path = os.path.abspath(excel_path)
    if not os.path.isfile(excel_path):
        return _failure(excel_path, f"找不到檔案：{excel_path}")

    missing = _check_dependencies(excel_path, args.fix)
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
                # test_report 與 unknown 皆嘗試解析測試報告
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
    result = FileCheckResult(path=excel_path, exit_code=1, summary=summary)

    if not embedded_pdfs:
        summary.notes.append("未在 Excel 中找到內嵌 PDF，所有報告相關核對將略過。")
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
        sh_issues, sh_notes, checked = check_sheet(sh, test_reports, msds_list)
        summary.issues.extend(sh_issues)
        summary.notes.extend(sh_notes)
        if checked:
            summary.sheets_checked.append(sh.name)

    if not summary.sheets_checked:
        return _set_error(
            result,
            "沒有核對任何工作表：找不到含「部件名稱 / Part name」表頭的工作表，"
            "請確認檔案格式或改用 --sheet 指定。",
        )

    result.report_text = format_report_text(summary, test_reports, msds_list, verbose=args.verbose)

    if args.save_report:
        base = os.path.splitext(excel_path)[0]
        report_path = f"{base}_核對報告.txt"
        try:
            with open(report_path, "w", encoding="utf-8-sig", newline="\r\n") as f:
                f.write(result.report_text + "\n")
        except OSError as exc:
            return _set_error(result, f"寫入核對報告失敗：{exc}")
        result.saved_report = report_path
        result.pre_messages.append(f"[已存檔] 核對報告：{report_path}")

    if not args.fix:
        result.exit_code = 0 if not summary.issues else (2 if summary.fixable_count > 0 else 3)
        return result

    if summary.fixable_count == 0:
        result.pre_messages.append("[提示] 沒有可自動修正的項目，未修改 Excel 檔案。")
        result.exit_code = 0 if not summary.issues else 3
        return result

    result.pre_messages.append(
        f"\n正在透過 Excel COM 自動修正 {summary.fixable_count} 個儲存格並標記紅字..."
    )
    try:
        fix_res = apply_fixes_via_excel_com(excel_path, summary.issues)
    except Exception as exc:
        return _set_error(result, f"Excel COM 自動修正失敗：{exc}")

    result.fixed_count = fix_res.fixed_count
    if fix_res.backup_path:
        result.pre_messages.append(f"[備份] 修正前原檔已備份為：{fix_res.backup_path}")
    if fix_res.used_fallback:
        result.pre_messages.append(
            f"[完成] 原檔案未覆寫，已另存為：{fix_res.saved_path}\n"
            f"       共修正 {fix_res.fixed_count} 個儲存格（已套用紅色字體）。"
        )
    else:
        result.pre_messages.append(
            f"[完成] 已直接儲存至原檔案：{fix_res.saved_path}\n"
            f"       共修正 {fix_res.fixed_count} 個儲存格（已套用紅色字體）。"
        )
    if fix_res.skipped_items:
        result.pre_messages.append(f"[警告] 以下 {len(fix_res.skipped_items)} 個儲存格修正略過：")
        for ref, reason in fix_res.skipped_items:
            result.pre_messages.append(f"  - {ref}: {reason}")

    result.pre_messages.append("\n正在複驗修正後的檔案...")
    try:
        sheets2 = load_workbook_sheets(fix_res.saved_path, target_sheet=args.sheet)
    except Exception as exc:
        return _set_error(result, f"讀取修正後檔案失敗：{exc}")

    verify = CheckSummary(
        excel_path=fix_res.saved_path,
        total_pdfs=len(embedded_pdfs),
        test_report_count=len(test_reports),
        msds_count=len(msds_list),
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
        sh_issues, sh_notes, checked = check_sheet(sh, test_reports, msds_list)
        verify.issues.extend(sh_issues)
        verify.notes.extend(sh_notes)
        if checked:
            verify.sheets_checked.append(sh.name)

    if not verify.sheets_checked:
        return _set_error(result, "修正後複驗找不到可核對的工作表。")

    result.verify_summary = verify
    result.verify_text = format_report_text(verify, test_reports, msds_list, verbose=args.verbose)
    if args.save_report:
        base = os.path.splitext(fix_res.saved_path)[0]
        verify_path = f"{base}_核對報告_修正後複驗.txt"
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
            "請依上方複驗報告人工確認。"
        )
    else:
        result.exit_code = 0
        result.post_messages.append("[完成] 修正後複驗：未發現異常。")
    return result


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


def _batch_report_path(targets: List[str]) -> str:
    base_dir = os.path.dirname(targets[0]) if targets else os.getcwd()
    return os.path.join(base_dir, "批次核對摘要.txt")


def _aggregate_exit_code(results: List[FileCheckResult], input_errors: List[str]) -> int:
    if input_errors or any(not r.ok for r in results):
        return 1
    codes = [r.exit_code for r in results]
    if 2 in codes:
        return 2
    if 3 in codes:
        return 3
    return 0


def main(argv: List[str] | None = None) -> int:
    # 確保 Windows 終端機 UTF-8 輸出不亂碼
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
        help="將每份核對結果另存為「原檔名_核對報告.txt」；批次模式另存「批次核對摘要.txt」",
    )
    parser.add_argument(
        "--fix",
        action="store_true",
        help="透過 Windows Excel COM 自動修正錯誤儲存格並標記紅字 RGB(255,0,0)",
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

    targets, input_errors = _collect_excel_targets(args.excel_file, args.recursive)
    for message in input_errors:
        print(f"[錯誤] {message}", file=sys.stderr)
    if not targets:
        if not input_errors:
            print("[錯誤] 沒有可核對的 Excel 檔案。", file=sys.stderr)
        return 1

    batch_mode = len(targets) > 1 or bool(input_errors)
    results: List[FileCheckResult] = []

    for index, path in enumerate(targets, 1):
        if batch_mode:
            print(f"[{index}/{len(targets)}] 核對：{path}", flush=True)
        result = process_file(path, args)
        results.append(result)
        if batch_mode:
            err, warn, fixable = _result_counts(result)
            print(
                f"        {_result_status(result)} | 錯誤 {err}、警告 {warn}、可修正 {fixable}",
                flush=True,
            )
            if not result.ok and result.error:
                print(f"        [錯誤] {result.error}", file=sys.stderr)

    if not batch_mode:
        _print_single_result(results[0])
        return results[0].exit_code

    if args.verbose:
        for result in results:
            _print_batch_detail(result)

    summary_text = format_batch_summary(results, input_errors)
    print(summary_text)
    if args.save_report:
        summary_path = _batch_report_path(targets)
        try:
            with open(summary_path, "w", encoding="utf-8-sig", newline="\r\n") as f:
                f.write(summary_text + "\n")
        except OSError as exc:
            print(f"[錯誤] 寫入批次核對摘要失敗：{exc}", file=sys.stderr)
            return 1
        print(f"[已存檔] 批次核對摘要：{summary_path}")

    return _aggregate_exit_code(results, input_errors)


if __name__ == "__main__":
    sys.exit(main())
