# -*- coding: utf-8 -*-
"""
O-193D Excel 欄位自動核對核心模組
支援：
  - 讀取 .xls (xlrd) 與 .xlsx / .xlsm (openpyxl)，完整處理合併儲存格與儲存格格式（百分比）
  - 動態偵測有效欄數（上限 80 欄），避免寬表右側物質漏檢
  - 自動偵測表頭列（含「部件名稱 / Part name」）與各欄位對應位置（不寫死欄序）
  - 重量驗算（區分合併/分拆成分列與獨立同名同重部件，統一單位換算加總 vs 表頭總重量）
  - 成分資訊（依儲存格百分比格式或數值推測計算均質材料組成總和 = 100%、CAS No. 核對 MSDS）
  - 報告基本資訊（依報告編號配對 PDF，核對測試機構與報告日期）
  - 測試結果（Cd, Pb, Hg, Cr6+, PBB, PBDE, DEHP, BBP, DBP, DIBP, Cl, Br, F, PFOA, PFOS, Be, As, Sb）
    容許 ㎍/cm2、μg/cm2、ug/cm2 符號差異與 38 vs 38.0 數值格式差異；多樣品歧義結果僅警告不自動修正
"""

from __future__ import annotations

import datetime as dt
import math
import os
import re
import zipfile
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set, Tuple

from fmd_matcher import (
    FMDEntry,
    FMDRecord,
    match_samsung_fmd,
)
from pdf_parser import (
    AGENCY_ALIAS_GROUPS,
    MSDSData,
    SUBSTANCE_KEYS,
    TestReportData,
    is_valid_cas_checksum,
    normalize_unit,
    parse_date_string,
)

MAX_SCAN_COLS = 80


# ---------------------------------------------------------------------------
# 資料結構
# ---------------------------------------------------------------------------

@dataclass
class CheckIssue:
    """單筆核對不符或警告項目。"""
    sheet_name: str
    cell_address: str          # 例如 "N15" 或 "總重量"
    item_name: str             # 例如 "Cr6+" / "測試日期" / "重量驗算"
    actual_value: str          # Excel 原填寫值
    expected_value: str        # 報告正確值 / 預期值
    report_no: str = ""        # 對應報告編號
    pdf_page: str = ""         # PDF 頁碼（如 "P.3"）
    evidence: str = ""         # 判斷依據摘要
    severity: str = "ERROR"    # "ERROR" | "WARNING"
    fixable: bool = False      # 是否可由 --fix 自動修正
    fix_value: Optional[str] = None  # 寫入 Excel 的修正值


@dataclass
class CheckSummary:
    """整份工作簿的核對摘要。"""
    excel_path: str
    sheets_checked: List[str] = field(default_factory=list)
    sheets_scanned: List[str] = field(default_factory=list)
    total_pdfs: int = 0
    test_report_count: int = 0
    msds_count: int = 0
    issues: List[CheckIssue] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)

    @property
    def error_count(self) -> int:
        return sum(1 for i in self.issues if i.severity == "ERROR")

    @property
    def warning_count(self) -> int:
        return sum(1 for i in self.issues if i.severity == "WARNING")

    @property
    def fixable_count(self) -> int:
        return sum(1 for i in self.issues if i.fixable and i.fix_value is not None)


# ---------------------------------------------------------------------------
# 統一的 .xls / .xlsx 工作表讀取器（1-based 座標 + 合併儲存格解析 + 百分比格式）
# ---------------------------------------------------------------------------

def col_index_to_letter(col: int) -> str:
    """1 -> 'A', 2 -> 'B', 27 -> 'AA', 30 -> 'AD'"""
    res = []
    while col > 0:
        col, rem = divmod(col - 1, 26)
        res.append(chr(ord("A") + rem))
    return "".join(reversed(res))


def make_cell_address(row: int, col: int) -> str:
    return f"{col_index_to_letter(col)}{row}"


class UnifiedSheet:
    """統一封裝 xlrd / openpyxl 工作表介面，座標皆為 1-based。"""

    def __init__(
        self,
        name: str,
        nrows: int,
        ncols: int,
        grid: List[List[Any]],
        merged_map: Dict[Tuple[int, int], Tuple[int, int]],
        datemode: int = 0,
        formula_without_cache: Optional[List[str]] = None,
        percent_cells: Optional[Set[Tuple[int, int]]] = None,
    ) -> None:
        self.name = name
        self.nrows = nrows
        self.ncols = ncols
        self._grid = grid
        self._merged_map = merged_map
        self.datemode = datemode
        self.formula_without_cache: List[str] = formula_without_cache or []
        self._percent_cells: Set[Tuple[int, int]] = percent_cells or set()

    def get_anchor(self, row: int, col: int) -> Tuple[int, int]:
        return self._merged_map.get((row, col), (row, col))

    def is_percent_format(self, row: int, col: int) -> bool:
        anchor = self.get_anchor(row, col)
        return anchor in self._percent_cells

    def get_raw(self, row: int, col: int) -> Any:
        if row < 1 or col < 1 or row > self.nrows or col > self.ncols:
            return None
        return self._grid[row - 1][col - 1]

    def get_value(self, row: int, col: int) -> Any:
        ar, ac = self.get_anchor(row, col)
        return self.get_raw(ar, ac)

    def get_str(self, row: int, col: int) -> str:
        val = self.get_value(row, col)
        if val is None:
            return ""
        if isinstance(val, (dt.datetime, dt.date)):
            return val.strftime("%Y/%m/%d")
        return str(val).strip()


def _detect_effective_ncols_openpyxl(ws, nrows: int) -> int:
    """動態偵測表頭區（前 40 列）實際有值的最右欄，避免硬截 35 欄漏檢或被殘留格式撐爆。"""
    max_col = min(ws.max_column or 0, MAX_SCAN_COLS)
    if max_col <= 35 or not nrows:
        return max_col
    rightmost = 35
    probe_rows = min(nrows, 40)
    for row in ws.iter_rows(min_row=1, max_row=probe_rows, max_col=max_col, values_only=True):
        for idx in range(len(row), rightmost, -1):
            v = row[idx - 1]
            if v is not None and str(v).strip() != "":
                rightmost = idx
                break
    return rightmost


def load_workbook_sheets(excel_path: str, target_sheet: Optional[str] = None) -> List[UnifiedSheet]:
    ext = os.path.splitext(excel_path)[1].lower()
    sheets: List[UnifiedSheet] = []

    if ext == ".xls":
        import xlrd

        wb = xlrd.open_workbook(excel_path, formatting_info=True)
        for sh in wb.sheets():
            if target_sheet and sh.name != target_sheet:
                continue
            grid: List[List[Any]] = []
            percent_cells: Set[Tuple[int, int]] = set()
            for r in range(sh.nrows):
                row_vals: List[Any] = []
                for c in range(sh.ncols):
                    cell = sh.cell(r, c)
                    val = cell.value
                    if cell.ctype == xlrd.XL_CELL_DATE:
                        try:
                            y, mo, d, _, _, _ = xlrd.xldate_as_tuple(val, wb.datemode)
                            val = dt.date(y, mo, d)
                        except Exception:
                            pass
                    elif cell.ctype == xlrd.XL_CELL_EMPTY:
                        val = None
                    elif cell.ctype == xlrd.XL_CELL_NUMBER:
                        try:
                            xf = wb.xf_list[cell.xf_index]
                            fmt = wb.format_map.get(xf.format_key)
                            if fmt and "%" in (fmt.format_str or ""):
                                percent_cells.add((r + 1, c + 1))
                        except Exception:
                            pass
                    row_vals.append(val)
                grid.append(row_vals)

            merged_map: Dict[Tuple[int, int], Tuple[int, int]] = {}
            for rlo, rhi, clo, chi in sh.merged_cells:
                anchor = (rlo + 1, clo + 1)
                for rr in range(rlo + 1, rhi + 1):
                    for cc in range(clo + 1, chi + 1):
                        merged_map[(rr, cc)] = anchor

            sheets.append(
                UnifiedSheet(
                    sh.name, sh.nrows, sh.ncols, grid, merged_map,
                    wb.datemode, percent_cells=percent_cells,
                )
            )

    elif ext in (".xlsx", ".xlsm"):
        import openpyxl

        wb = openpyxl.load_workbook(excel_path, data_only=True)
        pending = []
        try:
            for ws in wb.worksheets:
                if target_sheet and ws.title != target_sheet:
                    continue
                nrows = ws.max_row or 0
                ncols = _detect_effective_ncols_openpyxl(ws, nrows)
                grid: List[List[Any]] = []
                percent_cells: Set[Tuple[int, int]] = set()
                if nrows and ncols:
                    for r_idx, row in enumerate(
                        ws.iter_rows(min_row=1, max_row=nrows, max_col=ncols, values_only=False),
                        start=1,
                    ):
                        row_vals: List[Any] = []
                        for c_idx, cell in enumerate(row, start=1):
                            val = cell.value
                            row_vals.append(val)
                            if (
                                isinstance(val, (int, float))
                                and not isinstance(val, bool)
                                and "%" in (cell.number_format or "")
                            ):
                                percent_cells.add((r_idx, c_idx))
                        grid.append(row_vals)
                merged_map: Dict[Tuple[int, int], Tuple[int, int]] = {}
                for rng in ws.merged_cells.ranges:
                    if rng.min_col > ncols:
                        continue
                    anchor = (rng.min_row, rng.min_col)
                    for rr in range(rng.min_row, rng.max_row + 1):
                        for cc in range(rng.min_col, min(rng.max_col, ncols) + 1):
                            merged_map[(rr, cc)] = anchor
                pending.append((ws.title, nrows, ncols, grid, merged_map, percent_cells))
        finally:
            wb.close()

        uncached = (
            _collect_uncached_formulas(excel_path, pending)
            if _workbook_has_formulas(excel_path)
            else {}
        )
        for name, nrows, ncols, grid, merged_map, percent_cells in pending:
            sheets.append(
                UnifiedSheet(
                    name, nrows, ncols, grid, merged_map,
                    formula_without_cache=uncached.get(name, []),
                    percent_cells=percent_cells,
                )
            )
    else:
        raise ValueError(f"不支援的檔案格式：{ext}")

    return sheets


def _workbook_has_formulas(excel_path: str) -> bool:
    """掃描 worksheet XML 是否含公式標籤（<f> 或 <f ...>），避免把 <filters> 誤判為公式。"""
    try:
        with zipfile.ZipFile(excel_path) as zf:
            for info in zf.infolist():
                name = info.filename
                if not (name.startswith("xl/worksheets/") and name.endswith(".xml")):
                    continue
                with zf.open(info) as fp:
                    tail = b""
                    while True:
                        chunk = fp.read(1024 * 1024)
                        if not chunk:
                            break
                        data = tail + chunk
                        if b"<f>" in data or b"<f " in data:
                            return True
                        tail = data[-3:]
    except Exception:
        return True
    return False


def _collect_uncached_formulas(
    excel_path: str,
    sheet_grids: List[Tuple[str, int, int, List[List[Any]], Dict[Tuple[int, int], Tuple[int, int]], Set[Tuple[int, int]]]],
) -> Dict[str, List[str]]:
    """找出公式儲存格但 data_only 值為 None 的位置（最多每表記錄 20 個）。"""
    import openpyxl

    result: Dict[str, List[str]] = {}
    try:
        wb = openpyxl.load_workbook(excel_path, read_only=True, data_only=False)
    except Exception:
        return result
    try:
        for name, nrows, ncols, grid, _, _ in sheet_grids:
            if name not in wb.sheetnames or not nrows or not ncols:
                continue
            try:
                ws = wb[name]
            except Exception:
                continue
            if not hasattr(ws, "iter_rows"):
                continue
            missing: List[str] = []
            for row in ws.iter_rows():
                stop = False
                for cell in row:
                    if getattr(cell, "data_type", None) != "f":
                        continue
                    r, c = cell.row, cell.column
                    if 1 <= r <= nrows and 1 <= c <= ncols and grid[r - 1][c - 1] is None:
                        missing.append(make_cell_address(r, c))
                        if len(missing) >= 20:
                            stop = True
                            break
                if stop:
                    break
            if missing:
                result[name] = missing
    finally:
        try:
            wb.close()
        except Exception:
            pass
    return result


# ---------------------------------------------------------------------------
# 表頭偵測與欄位對應（自動偵測，不寫死順序）
# ---------------------------------------------------------------------------

_HEADER_ANCHOR_RE = re.compile(r"部件名稱|部件名称|零件名稱|零件名称|Part\s*Name", re.IGNORECASE)

_META_COLUMN_PATTERNS: Dict[str, List[str]] = {
    "part_name": [r"部件名稱|部件名称|零件名稱|零件名称|Part\s*Name"],
    "part_no": [r"部件料號|部件料号|零件料號|零件料号|Part\s*No\.?"],
    "weight_unit": [r"重量單位|重量单位|單位|单位|Unit"],
    "content_pct": [r"含量|百分比|比例|組成比|组成比|Content|Composition\s*\(?%\)?|Weight\s*%"],
    "material_weight": [
        r"(?:均質材料重|均质材料重|材料重量|材質重量|Homogeneous\s*Material\s*Weight|Material\s*Weight)(?!.*(?:單位|单位|Unit|%|％|百分比|比例))"
    ],
    "part_weight": [
        r"(?:部件重|零件重|單重|单重|淨重|净重|重量|Weight)(?!.*(?:單位|单位|Unit|%|％|百分比|比例))"
    ],
    "material_name": [r"均質材料|均质材料|材質名稱|材质名称|材料名稱|材料名称|Homogeneous\s*Material|Material\s*Name"],
    "manufacturer": [r"製造商|制造商|廠牌|厂牌|Manufacturer(?:\s*Name)?"],
    "composition_name": [r"成分名稱|成份名稱|成分名称|化學物質|化学物质|物質名稱|物质名称|Substance\s*Name|Chemical\s*Name|Ingredient"],
    "cas_no": [r"\bCAS\b|CAS\s*No|CAS編號|CAS编号"],
    "report_no": [r"報告編號|报告编号|測試報告編號|测试报告编号|檢測報告編號|检测报告编号|報告號|报告号|測試報告號|测试报告号|Report\s*No|Report\s*Number"],
    "agency": [r"測試機構|测试机构|檢測機構|检测机构|檢測單位|检测单位|測試單位|测试单位|實驗室|实验室|Test\s*Agency|Testing\s*Lab|Lab(?:oratory)?"],
    "report_date": [r"測試日期|测试日期|報告日期|报告日期|簽發日期|签发日期|發行日期|发行日期|出具日期|報告日|报告日|Test\s*Date|Report\s*Date|Issue\s*Date"],
}

_SUBSTANCE_HEADER_PATTERNS: List[Tuple[str, List[str]]] = [
    ("Cd", [r"\bCd\b|Cadmium|鎘|镉"]),
    ("Pb", [r"\bPb\b|Lead|鉛|铅"]),
    ("Hg", [r"\bHg\b|Mercury|汞"]),
    ("Cr6+", [r"Cr\s*\(?\s*(?:6\+?|VI)\s*\)?|Hexavalent|六價鉻|六价铬"]),
    ("PBB", [r"\bPBBs?\b|多溴聯苯|多溴联苯"]),
    ("PBDE", [r"\bPBDEs?\b|多溴二苯醚"]),
    ("DEHP", [r"\bDEHP\b"]),
    ("BBP", [r"\bBBP\b"]),
    ("DBP", [r"\bDBP\b"]),
    ("DIBP", [r"\bDIBP\b"]),
    ("Cl", [r"(?:^|[\s\(（/])Cl(?:$|[\s\)）/])|Chlorine|氯"]),
    ("Br", [r"(?:^|[\s\(（/])Br(?:$|[\s\)）/])|Bromine|溴"]),
    ("F", [r"(?:^|[\s\(（/])F(?:$|[\s\)）/])|Fluorine|氟"]),
    ("PFOA", [r"\bPFOA\b|全氟辛酸"]),
    ("PFOS", [r"\bPFOS\b|全氟辛烷"]),
    ("Be", [r"(?:^|[\s\(（/])Be(?:$|[\s\)）/])|Beryllium|鈹|铍"]),
    ("As", [r"(?:^|[\s\(（/])As(?:$|[\s\)）/])|Arsenic|砷"]),
    ("Sb", [r"(?:^|[\s\(（/])Sb(?:$|[\s\)）/])|Antimony|銻|锑"]),
]

if {key for key, _ in _SUBSTANCE_HEADER_PATTERNS} != set(SUBSTANCE_KEYS):
    raise RuntimeError(
        "excel_checker 與 pdf_parser 的物質鍵不一致，請同步更新 "
        "_SUBSTANCE_HEADER_PATTERNS 與 SUBSTANCE_PATTERNS"
    )


@dataclass
class HeaderLayout:
    header_row: int
    data_start_row: int
    meta_cols: Dict[str, int]
    substance_cols: Dict[str, int]
    weight_header_unit: str = "g"


def detect_header_layout(sheet: UnifiedSheet) -> Optional[HeaderLayout]:
    """自動尋找含「部件名稱 / Part name」的表頭列，並掃描有效欄位建立欄位對應。"""
    max_scan_row = min(sheet.nrows, 40)
    header_row = None

    for r in range(1, max_scan_row + 1):
        for c in range(1, min(sheet.ncols, 15) + 1):
            txt = sheet.get_str(r, c)
            if txt and _HEADER_ANCHOR_RE.search(txt):
                header_row = r
                break
        if header_row:
            break

    if not header_row:
        return None

    scan_max_col = min(sheet.ncols, MAX_SCAN_COLS)
    header_rows = [header_row]
    if header_row + 1 <= sheet.nrows:
        next_row_text = " ".join(sheet.get_str(header_row + 1, c) for c in range(2, scan_max_col + 1))
        if re.search(r"\b(Cd|Pb|Hg|Cr6\+?|PBB|PBDE|DEHP|CAS)\b|檢出|限值|單位", next_row_text, re.IGNORECASE):
            header_rows.append(header_row + 1)

    data_start_row = max(header_rows) + 1
    if data_start_row <= sheet.nrows:
        row_probe = " ".join(sheet.get_str(data_start_row, c) for c in range(2, scan_max_col + 1))
        if re.search(r"(?:^|\s)(?:Limit|限值|管制標準|管控標準|標準值|單位|Unit)(?:\s|$)", row_probe, re.IGNORECASE):
            if not sheet.get_str(data_start_row, 2) or re.search(r"限值|Limit|標準", sheet.get_str(data_start_row, 2), re.IGNORECASE):
                data_start_row += 1

    meta_cols: Dict[str, int] = {}
    substance_cols: Dict[str, int] = {}
    weight_header_unit = "g"

    for c in range(2, scan_max_col + 1):
        parts = []
        for hr in header_rows:
            t = sheet.get_str(hr, c)
            if t and t not in parts:
                parts.append(t)
        combined = " ".join(parts).strip()
        if not combined:
            continue

        matched_sub = False
        for sub_key, pats in _SUBSTANCE_HEADER_PATTERNS:
            if sub_key in substance_cols:
                continue
            for hr in reversed(header_rows):
                row_text = sheet.get_str(hr, c)
                if not row_text:
                    continue
                if any(re.search(p, row_text, re.IGNORECASE) for p in pats):
                    substance_cols[sub_key] = c
                    matched_sub = True
                    break
            if matched_sub:
                break
        if matched_sub:
            continue

        for field_key, pats in _META_COLUMN_PATTERNS.items():
            if field_key in meta_cols:
                continue
            if any(re.search(p, combined, re.IGNORECASE) for p in pats):
                meta_cols[field_key] = c
                if field_key == "part_weight":
                    u_m = re.search(
                        rf"[\(（]\s*{_WEIGHT_TOKEN_RE}\s*[\)）]", combined, re.IGNORECASE
                    )
                    if u_m:
                        unit = u_m.group(1).lower()
                        if unit in _WEIGHT_UNIT_FACTORS:
                            weight_header_unit = unit
                break

    return HeaderLayout(
        header_row=header_row,
        data_start_row=data_start_row,
        meta_cols=meta_cols,
        substance_cols=substance_cols,
        weight_header_unit=weight_header_unit,
    )


# ---------------------------------------------------------------------------
# 數值、單位、機構、日期比對工具
# ---------------------------------------------------------------------------

_WEIGHT_UNIT_FACTORS: Dict[str, float] = {
    "g": 1.0, "gram": 1.0, "grams": 1.0, "公克": 1.0, "克": 1.0,
    "kg": 1000.0, "公斤": 1000.0, "千克": 1000.0,
    "mg": 0.001, "毫克": 0.001,
    "μg": 1e-6, "ug": 1e-6, "µg": 1e-6, "微克": 1e-6,
}

_WEIGHT_TOKEN_RE = r"(kg|mg|μg|ug|µg|公斤|千克|公克|毫克|微克|克|g)"


def _to_grams(value: float, unit: str) -> Optional[float]:
    u = (unit or "g").strip().lower()
    factor = _WEIGHT_UNIT_FACTORS.get(u)
    if factor is None:
        return None
    return value * factor


def _parse_weight_and_unit(raw: Any, default_unit: str = "g") -> Optional[Tuple[float, str]]:
    if raw is None:
        return None
    if isinstance(raw, (int, float)) and not isinstance(raw, bool):
        return float(raw), default_unit.lower()
    s = str(raw).strip().replace(",", "").replace("，", "")
    m = re.fullmatch(
        rf"\s*(?:約|~)?\s*(-?\d+(?:\.\d+)?)\s*{_WEIGHT_TOKEN_RE}?\s*",
        s,
        re.IGNORECASE,
    )
    if not m:
        return None
    val = float(m.group(1))
    unit = (m.group(2) or default_unit).lower()
    return val, unit


def _find_total_weight_in_header(sheet: UnifiedSheet, header_row: int) -> Optional[Tuple[str, float, str]]:
    label_re = re.compile(r"總重量|总重量|產品總重|产品总重|Total\s*Weight", re.IGNORECASE)
    for r in range(1, header_row):
        for c in range(1, min(sheet.ncols, 20) + 1):
            txt = sheet.get_str(r, c)
            if not txt or not label_re.search(txt):
                continue
            label_match = label_re.search(txt)
            m_inline = re.search(
                rf"[:：]?\s*(-?\d[\d,]*(?:\.\d+)?)\s*{_WEIGHT_TOKEN_RE}?",
                txt[label_match.end():],
                re.IGNORECASE,
            )
            if m_inline:
                val = float(m_inline.group(1).replace(",", "").replace("，", ""))
                if m_inline.group(2):
                    u = m_inline.group(2).lower()
                else:
                    u_lbl = re.search(
                        rf"[\(（]\s*{_WEIGHT_TOKEN_RE}\s*[\)）]", txt, re.IGNORECASE
                    )
                    u = u_lbl.group(1).lower() if u_lbl else "g"
                grams = _to_grams(val, u)
                if grams is not None:
                    return make_cell_address(r, c), grams, f"{val}{u}"

            for dc in range(1, 4):
                if c + dc > sheet.ncols:
                    break
                if sheet.get_anchor(r, c + dc) == sheet.get_anchor(r, c):
                    continue
                v_raw = sheet.get_value(r, c + dc)
                parsed = _parse_weight_and_unit(v_raw, "g")
                if parsed:
                    val, u = parsed
                    if c + dc + 1 <= sheet.ncols:
                        u_next = sheet.get_str(r, c + dc + 1).lower()
                        if u_next in _WEIGHT_UNIT_FACTORS:
                            u = u_next
                    u_lbl = re.search(rf"[\(（]\s*{_WEIGHT_TOKEN_RE}\s*[\)）]", txt, re.IGNORECASE)
                    if u_lbl and not re.search(_WEIGHT_TOKEN_RE, str(v_raw), re.IGNORECASE):
                        u = u_lbl.group(1).lower()
                    grams = _to_grams(val, u)
                    if grams is not None:
                        return make_cell_address(r, c + dc), grams, f"{val}{u}"
    return None


def _normalize_report_key(s: str) -> str:
    return re.sub(r"[\s\-_/\.]+", "", s.strip().upper())


def _report_keys(rep: TestReportData) -> List[str]:
    return rep.all_report_nos or ([rep.report_no] if rep.report_no else [])


def _safe_affix_match(a: str, b: str) -> bool:
    if not a or not b:
        return False
    short, long = (a, b) if len(a) <= len(b) else (b, a)
    if len(short) < 8:
        return False
    return long.startswith(short) or long.endswith(short)


def _match_test_report(
    report_no_cell: str, reports: List[TestReportData]
) -> Tuple[Optional[TestReportData], Optional[str]]:
    if not report_no_cell:
        return None, None
    target_norm = _normalize_report_key(report_no_cell)
    if len(target_norm) < 4:
        return None, None

    exact = [
        rep for rep in reports
        if any(_normalize_report_key(cand) == target_norm for cand in _report_keys(rep))
    ]
    if exact:
        unique = {id(rep) for rep in exact}
        if len(unique) > 1:
            names = "、".join(sorted({rep.filename for rep in exact}))
            return None, f"有多份報告的編號與「{report_no_cell}」完全相同（{names}）"
        return exact[0], None

    fuzzy = [
        rep for rep in reports
        if any(_safe_affix_match(target_norm, _normalize_report_key(cand)) for cand in _report_keys(rep))
    ]
    if fuzzy:
        unique = {id(rep) for rep in fuzzy}
        if len(unique) > 1:
            names = "、".join(sorted({rep.filename for rep in fuzzy}))
            return None, f"「{report_no_cell}」同時符合多份報告（{names}），請人工確認"
        return fuzzy[0], None

    fname_hits = []
    for rep in reports:
        fn = _normalize_report_key(os.path.splitext(rep.filename)[0])
        if len(target_norm) >= 8 and target_norm in fn:
            fname_hits.append(rep)
    if len(fname_hits) == 1:
        return fname_hits[0], None
    if len(fname_hits) > 1:
        names = "、".join(sorted({rep.filename for rep in fname_hits}))
        return None, f"「{report_no_cell}」符合多個報告檔名（{names}），請人工確認"

    return None, None


def _canonical_agency(name: str) -> str:
    low = (name or "").strip().lower()
    if not low:
        return ""
    for canon, aliases in AGENCY_ALIAS_GROUPS:
        for a in aliases:
            if a in low:
                return canon
    return name.strip()


def _agencies_match(excel_val: str, pdf_agency: str) -> bool:
    if not pdf_agency:
        return True
    c1 = _canonical_agency(excel_val)
    c2 = _canonical_agency(pdf_agency)
    return c1.lower() == c2.lower()


def _parse_excel_date(raw: Any) -> Optional[str]:
    if raw is None:
        return None
    if isinstance(raw, dt.datetime):
        return raw.strftime("%Y/%m/%d")
    if isinstance(raw, dt.date):
        return raw.strftime("%Y/%m/%d")
    return parse_date_string(str(raw))


_COMPARE_SYMBOLS = {"<": "lt", "≤": "le", ">": "gt", "≥": "ge", "~": "approx"}


def _normalize_result_for_compare(val_str: str) -> Tuple[str, Optional[float], str]:
    s = (val_str or "").strip()
    if not s:
        return ("empty", None, "")

    s_norm = (
        s.replace("＜", "<").replace("＞", ">")
        .replace("≤", "≤").replace("≥", "≥")
        .replace("～", "~").replace("，", "").replace(",", "")
    )
    s_norm = re.sub(r"[㎍μµu]g\s*/\s*cm[2²]", "μg/cm2", s_norm, flags=re.IGNORECASE)

    m_sym = re.match(r"^([<≤>≥~])\s*(-?\d+(?:\.\d+)?)\s*(.*)$", s_norm)
    if m_sym:
        kind = _COMPARE_SYMBOLS[m_sym.group(1)]
        num = float(m_sym.group(2))
        unit = normalize_unit(m_sym.group(3)) if m_sym.group(3).strip() else "ppm"
        return (kind, num, unit)

    m_num = re.match(r"^(-?\d+(?:\.\d+)?)\s*(.*)$", s_norm)
    if m_num:
        num = float(m_num.group(1))
        unit_raw = m_num.group(2).strip()
        unit = normalize_unit(unit_raw) if unit_raw else "ppm"
        return ("num", num, unit)

    return ("text", None, s_norm.upper())


def _compare_kind_group(kind: str) -> str:
    if kind in ("lt", "le"):
        return "lt"
    if kind in ("gt", "ge"):
        return "gt"
    if kind in ("approx", "num"):
        return "num"
    return kind


def results_match(excel_val: str, expected_val: str) -> bool:
    k1, n1, u1 = _normalize_result_for_compare(excel_val)
    k2, n2, u2 = _normalize_result_for_compare(expected_val)

    if _compare_kind_group(k1) != _compare_kind_group(k2):
        return False
    if _compare_kind_group(k1) in ("lt", "gt", "num"):
        if n1 is None or n2 is None:
            return False
        return math.isclose(n1, n2, rel_tol=1e-6, abs_tol=1e-9) and (u1 == u2)
    return u1 == u2


# ---------------------------------------------------------------------------
# 主核對流程
# ---------------------------------------------------------------------------

_NOTE_ROW_RE = re.compile(
    r"★|Supplier\s+shall|GQS-ZZ|核准|審查\s*Check|測試結果欄位填寫要求|"
    r"填寫產品/部件/均質材料重量",
    re.IGNORECASE,
)


def _row_blocks(sheet: UnifiedSheet, rows: List[int], col: Optional[int]) -> List[List[int]]:
    """
    將連續資料列切成「區塊」：
      - 合併儲存格：同一個 anchor 視為同一區塊
      - 未合併：連續相同文字視為同一區塊
      - 空白：沿用目前區塊
    """
    if col is None:
        return [list(rows)]

    blocks: List[List[int]] = []
    cur_key: Any = None
    cur_rows: List[int] = []
    for r in rows:
        text = sheet.get_str(r, col)
        key: Any = ("text", text) if text else cur_key

        if cur_key is None or key != cur_key:
            if cur_rows:
                blocks.append(cur_rows)
            cur_rows = []
            cur_key = key
        cur_rows.append(r)

    if cur_rows:
        blocks.append(cur_rows)
    return blocks


def _is_standalone_100_pct(sheet: UnifiedSheet, row: int, pct_col: Optional[int]) -> bool:
    """判斷該列是否為單獨構成 100% 的獨立成分列（用於區分兩個同名同重的獨立部件）。"""
    # 沒有含量欄位時無法證明是獨立部件，維持原行為：同重量只計一次，
    # 避免每列重複填寫重量時被誤加總成多個部件。
    if pct_col is None:
        return False
    raw = sheet.get_value(row, pct_col)
    if raw is None or str(raw).strip() == "":
        return False
    s = str(raw).strip().replace("%", "").replace("％", "").strip()
    try:
        v = float(s)
    except ValueError:
        return False
    if sheet.is_percent_format(row, pct_col) or abs(v - 1.0) <= 0.005:
        return abs(v - 1.0) <= 0.005
    return abs(v - 100.0) <= 0.15


def _find_supplier_part_no_in_header(sheet: UnifiedSheet, header_row: int) -> Optional[str]:
    """從表頭區讀取「供應商產品料號 / Supplier Product No.」。"""
    label_re = re.compile(
        r"供應商產品料號|供应商产品料号|Supplier\s*Product\s*No|原廠型番|原厂型番|Manufacturer\s*Part\s*No",
        re.IGNORECASE,
    )
    for r in range(1, header_row):
        for c in range(1, min(sheet.ncols, 20) + 1):
            txt = sheet.get_str(r, c)
            if not txt or not label_re.search(txt):
                continue
            for dc in range(1, 5):
                if c + dc > sheet.ncols:
                    break
                if sheet.get_anchor(r, c + dc) == sheet.get_anchor(r, c):
                    continue
                val = sheet.get_str(r, c + dc)
                if val:
                    return val
    return None


def _norm_fmd_text(text: str) -> str:
    return re.sub(r"\s+", "", (text or "").strip().lower())


def _format_mg(val_mg: float) -> str:
    rounded = round(val_mg, 6)
    s = f"{rounded:.6f}".rstrip("0").rstrip(".")
    return f"{s}mg"


def _check_columns_b_to_j_against_fmd(
    sheet: UnifiedSheet,
    layout: HeaderLayout,
    data_rows: List[int],
    fmd_records: List[FMDRecord],
    total_wt_info: Optional[Tuple[str, float, str]],
) -> Tuple[List[CheckIssue], List[str]]:
    """
    將 O-193D 的欄位 B~J（部件名稱、部件料號、部件重量、均質材料名稱、製造商、
    均質材料重量、物質名稱、CAS No.、含量%）與配對到的 SAMSUNG FMD 成分表比對。
    """
    issues: List[CheckIssue] = []
    notes: List[str] = []
    if not fmd_records or not data_rows:
        return issues, notes

    supplier_pn = _find_supplier_part_no_in_header(sheet, layout.header_row)
    if not supplier_pn:
        return issues, notes

    part_col = layout.meta_cols.get("part_name")
    part_no_col = layout.meta_cols.get("part_no")
    wt_col = layout.meta_cols.get("part_weight")
    wt_unit_col = layout.meta_cols.get("weight_unit")
    mat_col = layout.meta_cols.get("material_name")
    mfr_col = layout.meta_cols.get("manufacturer")
    mat_wt_col = layout.meta_cols.get("material_weight")
    comp_col = layout.meta_cols.get("composition_name")
    cas_col = layout.meta_cols.get("cas_no")
    pct_col = layout.meta_cols.get("content_pct")

    # 檢查是否為三星產品（料號以 CL/CI 開頭，或製造商含 SAMSUNG）
    mfr_values = [sheet.get_str(r, mfr_col).upper() for r in data_rows] if mfr_col else []
    is_samsung = (
        bool(re.match(r"^(?:CL\d{2}[A-Z]|CI[A-Z]{2})", supplier_pn.strip(), re.IGNORECASE))
        or any("SAMSUNG" in m for m in mfr_values)
    )
    if not is_samsung:
        return issues, notes

    excel_materials: List[str] = []
    for r in data_rows:
        if part_no_col:
            v = sheet.get_str(r, part_no_col)
            if v:
                excel_materials.append(v)
        if mat_col:
            v = sheet.get_str(r, mat_col)
            if v:
                excel_materials.append(v)

    excel_total_mg = (total_wt_info[1] * 1000.0) if total_wt_info else None
    matched_fmd, fmd_warn = match_samsung_fmd(
        supplier_pn,
        fmd_records,
        excel_materials=excel_materials,
        excel_total_mg=excel_total_mg,
    )
    if not matched_fmd:
        if fmd_warn:
            issues.append(
                CheckIssue(
                    sheet_name=sheet.name,
                    cell_address="B~J欄",
                    item_name="SAMSUNG FMD 配對",
                    actual_value=supplier_pn,
                    expected_value="唯一對應之 FMD 檔案",
                    evidence=fmd_warn,
                    severity="WARNING",
                    fixable=False,
                )
            )
        else:
            notes.append(
                f"工作表 [{sheet.name}] 供應商料號「{supplier_pn}」於 SAMSUNG FMD 資料夾中未找到對應成分表，略過欄位 B~J FMD 比對。"
            )
        return issues, notes

    # 預先計算 FMD 各部件總重與各均質材料總重（mg）
    part_weight_mg: Dict[str, float] = {}
    mat_weight_mg: Dict[Tuple[str, str], float] = {}
    mat_entry_count: Dict[Tuple[str, str], int] = {}
    for e in matched_fmd.entries:
        p_key = _norm_fmd_text(e.part_name)
        m_key = (p_key, _norm_fmd_text(e.material_name))
        mat_entry_count[m_key] = mat_entry_count.get(m_key, 0) + 1
        if e.weight_mg is not None:
            part_weight_mg[p_key] = part_weight_mg.get(p_key, 0.0) + e.weight_mg
            mat_weight_mg[m_key] = mat_weight_mg.get(m_key, 0.0) + e.weight_mg

    # 將 O-193D 各資料列與 FMD entries 配對（多輪精準對齊，避免單一欄位打錯就錯位）
    remaining_fmd = list(range(len(matched_fmd.entries)))
    row_to_fmd: Dict[int, int] = {}

    def _row_fmd_score(r: int, e: FMDEntry) -> int:
        score = 0
        if part_col and _norm_fmd_text(sheet.get_str(r, part_col)) == _norm_fmd_text(e.part_name):
            score += 4
        if cas_col and e.cas_no and _norm_fmd_text(sheet.get_str(r, cas_col)) == _norm_fmd_text(e.cas_no):
            score += 4
        if part_no_col and _norm_fmd_text(sheet.get_str(r, part_no_col)) == _norm_fmd_text(e.material_name):
            score += 3
        if comp_col and _norm_fmd_text(sheet.get_str(r, comp_col)) == _norm_fmd_text(e.substance_name):
            score += 3
        if mat_col and _norm_fmd_text(sheet.get_str(r, mat_col)) in (
            _norm_fmd_text(e.substance_name),
            _norm_fmd_text(e.material_name),
        ):
            score += 2
        return score

    # 先配對高分列，再配對其餘列
    for threshold in (8, 4, 2):
        for r in data_rows:
            if r in row_to_fmd or not remaining_fmd:
                continue
            best_idx = None
            best_score = 0
            for idx in remaining_fmd:
                sc = _row_fmd_score(r, matched_fmd.entries[idx])
                if sc > best_score:
                    best_score = sc
                    best_idx = idx
            if best_idx is not None and best_score >= threshold:
                row_to_fmd[r] = best_idx
                remaining_fmd.remove(best_idx)

    # 若行數剛好相等且仍有未配對列，依剩餘順序配對（抓出整列填錯的情況）
    unmatched_rows = [r for r in data_rows if r not in row_to_fmd]
    for r, idx in zip(unmatched_rows, list(remaining_fmd)):
        row_to_fmd[r] = idx
        remaining_fmd.remove(idx)

    checked_anchors: Set[Tuple[int, int]] = set()
    fmd_issue_count = 0
    fmd_label = matched_fmd.filename

    def _add_fmd_issue(
        row: int,
        col: int,
        item_name: str,
        actual: str,
        expected: str,
        evidence: str,
        fixable: bool = True,
        fix_value: Optional[str] = None,
    ) -> None:
        nonlocal fmd_issue_count
        anchor = sheet.get_anchor(row, col)
        if anchor in checked_anchors:
            return
        checked_anchors.add(anchor)
        fmd_issue_count += 1
        issues.append(
            CheckIssue(
                sheet_name=sheet.name,
                cell_address=make_cell_address(anchor[0], anchor[1]),
                item_name=item_name,
                actual_value=actual or "(空白)",
                expected_value=expected,
                report_no=fmd_label,
                evidence=evidence,
                severity="ERROR",
                fixable=fixable,
                fix_value=fix_value if fixable else None,
            )
        )

    def _cell_mg(row: int, col: int) -> Optional[float]:
        raw = sheet.get_value(row, col)
        if raw is None or str(raw).strip() == "":
            return None
        unit_override = sheet.get_str(row, wt_unit_col).lower() if wt_unit_col else ""
        default_u = unit_override if unit_override in _WEIGHT_UNIT_FACTORS else layout.weight_header_unit
        parsed = _parse_weight_and_unit(raw, default_u)
        if not parsed:
            return None
        g = _to_grams(parsed[0], parsed[1])
        return g * 1000.0 if g is not None else None

    for r in data_rows:
        if r not in row_to_fmd:
            continue
        entry = matched_fmd.entries[row_to_fmd[r]]
        p_key = _norm_fmd_text(entry.part_name)
        m_key = (p_key, _norm_fmd_text(entry.material_name))

        # 欄 B：部件名稱
        if part_col and entry.part_name:
            actual = sheet.get_str(r, part_col)
            if _norm_fmd_text(actual) != _norm_fmd_text(entry.part_name):
                _add_fmd_issue(
                    r, part_col, "部件名稱(FMD)", actual, entry.part_name,
                    f"SAMSUNG FMD ({fmd_label}) Part Name 為 {entry.part_name}",
                    fixable=True, fix_value=entry.part_name,
                )

        # 欄 C：部件料號（對應 FMD Material Name）
        if part_no_col and entry.material_name:
            actual = sheet.get_str(r, part_no_col)
            if _norm_fmd_text(actual) != _norm_fmd_text(entry.material_name):
                _add_fmd_issue(
                    r, part_no_col, "部件料號(FMD)", actual, entry.material_name,
                    f"SAMSUNG FMD ({fmd_label}) Material Name 為 {entry.material_name}",
                    fixable=True, fix_value=entry.material_name,
                )

        # 欄 D：部件重量
        if wt_col and p_key in part_weight_mg:
            exp_mg = part_weight_mg[p_key]
            act_mg = _cell_mg(r, wt_col)
            tol = max(abs(exp_mg) * 0.02, 1e-3)
            if act_mg is None or abs(act_mg - exp_mg) > tol:
                exp_disp = _format_mg(exp_mg)
                _add_fmd_issue(
                    r, wt_col, "部件重量(FMD)", sheet.get_str(r, wt_col), exp_disp,
                    f"SAMSUNG FMD ({fmd_label}) 部件「{entry.part_name}」重量為 {exp_disp}",
                    fixable=True, fix_value=exp_disp,
                )

        # 欄 E：均質材料名稱（單一物質材料容許填寫 Substance Name 或 Material Name）
        if mat_col and (entry.substance_name or entry.material_name):
            actual = sheet.get_str(r, mat_col)
            allowed = {_norm_fmd_text(entry.material_name)}
            if mat_entry_count.get(m_key, 1) == 1 and entry.substance_name:
                allowed.add(_norm_fmd_text(entry.substance_name))
            allowed.discard("")
            if _norm_fmd_text(actual) not in allowed:
                exp_name = entry.substance_name if mat_entry_count.get(m_key, 1) == 1 and entry.substance_name else entry.material_name
                _add_fmd_issue(
                    r, mat_col, "均質材料名稱(FMD)", actual, exp_name,
                    f"SAMSUNG FMD ({fmd_label}) 均質材料應為 {exp_name}",
                    fixable=True, fix_value=exp_name,
                )

        # 欄 F：製造商名稱
        if mfr_col:
            actual = sheet.get_str(r, mfr_col)
            if "samsung" not in actual.lower():
                _add_fmd_issue(
                    r, mfr_col, "製造商名稱(FMD)", actual, "SAMSUNG",
                    f"資料來源為 SAMSUNG 原廠 FMD ({fmd_label})，製造商應含 SAMSUNG",
                    fixable=True, fix_value="SAMSUNG",
                )

        # 欄 G：均質材料重量
        if mat_wt_col and m_key in mat_weight_mg:
            exp_mg = mat_weight_mg[m_key]
            act_mg = _cell_mg(r, mat_wt_col)
            tol = max(abs(exp_mg) * 0.02, 1e-3)
            if act_mg is None or abs(act_mg - exp_mg) > tol:
                exp_disp = _format_mg(exp_mg)
                _add_fmd_issue(
                    r, mat_wt_col, "均質材料重量(FMD)", sheet.get_str(r, mat_wt_col), exp_disp,
                    f"SAMSUNG FMD ({fmd_label}) 材料「{entry.material_name}」重量為 {exp_disp}",
                    fixable=True, fix_value=exp_disp,
                )

        # 欄 H：物質名稱
        if comp_col and entry.substance_name:
            actual = sheet.get_str(r, comp_col)
            if _norm_fmd_text(actual) != _norm_fmd_text(entry.substance_name):
                _add_fmd_issue(
                    r, comp_col, "物質名稱(FMD)", actual, entry.substance_name,
                    f"SAMSUNG FMD ({fmd_label}) Substance Name 為 {entry.substance_name}",
                    fixable=True, fix_value=entry.substance_name,
                )

        # 欄 I：CAS No.
        if cas_col and entry.cas_no:
            actual = sheet.get_str(r, cas_col)
            if _norm_fmd_text(actual) != _norm_fmd_text(entry.cas_no):
                _add_fmd_issue(
                    r, cas_col, "CAS No.(FMD)", actual, entry.cas_no,
                    f"SAMSUNG FMD ({fmd_label}) CAS No. 為 {entry.cas_no}",
                    fixable=True, fix_value=entry.cas_no,
                )

        # 欄 J：含量 (%)（以同一均質材料內各物質重量比例換算）
        if pct_col and entry.weight_mg is not None and mat_weight_mg.get(m_key, 0.0) > 0:
            exp_pct = (entry.weight_mg / mat_weight_mg[m_key]) * 100.0
            raw_pct = sheet.get_value(r, pct_col)
            act_pct: Optional[float] = None
            if raw_pct is not None and str(raw_pct).strip() != "":
                try:
                    v = float(str(raw_pct).strip().replace("%", "").replace("％", "").strip())
                    if sheet.is_percent_format(r, pct_col) or (0.0 < v <= 1.0001 and abs(exp_pct - v * 100.0) < abs(exp_pct - v)):
                        act_pct = v * 100.0
                    else:
                        act_pct = v
                except ValueError:
                    act_pct = None
            if act_pct is None or abs(act_pct - exp_pct) > 0.5:
                exp_pct_str = f"{round(exp_pct, 2):g}"
                _add_fmd_issue(
                    r, pct_col, "含量%(FMD)", sheet.get_str(r, pct_col), f"{exp_pct_str}%",
                    f"SAMSUNG FMD ({fmd_label}) 該物質於均質材料「{entry.material_name}」占比應為 {exp_pct_str}%",
                    fixable=False,
                )

    # 檢查 FMD 中是否仍有缺漏未填寫的成分列
    for idx in remaining_fmd:
        missing_e = matched_fmd.entries[idx]
        fmd_issue_count += 1
        issues.append(
            CheckIssue(
                sheet_name=sheet.name,
                cell_address="B~J欄",
                item_name="FMD 成分缺漏",
                actual_value="(缺少此列)",
                expected_value=f"{missing_e.part_name} / {missing_e.substance_name} ({missing_e.cas_no})",
                report_no=fmd_label,
                evidence=f"SAMSUNG FMD ({fmd_label}) 包含此成分列，但展開表中未填寫",
                severity="ERROR",
                fixable=False,
            )
        )

    notes.append(
        f"工作表 [{sheet.name}] 欄位 B~J 已比對 SAMSUNG FMD（{fmd_label}，料號={supplier_pn}）："
        f"比對 {len(row_to_fmd)}/{len(matched_fmd.entries)} 筆成分列，不符 {fmd_issue_count} 項。"
    )
    return issues, notes


def check_sheet(
    sheet: UnifiedSheet,
    test_reports: List[TestReportData],
    msds_list: List[MSDSData],
    fmd_records: Optional[List[FMDRecord]] = None,
) -> Tuple[List[CheckIssue], List[str], bool]:
    issues: List[CheckIssue] = []
    notes: List[str] = []

    if sheet.formula_without_cache:
        sample = "、".join(sheet.formula_without_cache[:5])
        notes.append(
            f"工作表 [{sheet.name}] 有 {len(sheet.formula_without_cache)} 個公式儲存格沒有快取結果"
            f"（例如 {sample}）；請先用 Excel 開啟並存檔，否則相關數值可能被讀成空白。"
        )

    layout = detect_header_layout(sheet)
    if not layout:
        notes.append(f"工作表 [{sheet.name}] 未偵測到含「部件名稱 / Part name」的表頭列，已略過。")
        return issues, notes, False

    notes.append(
        f"工作表 [{sheet.name}]：表頭列=第 {layout.header_row} 列，資料起始=第 {layout.data_start_row} 列，"
        f"偵測到 {len(layout.meta_cols)} 個基本欄位、{len(layout.substance_cols)} 個物質欄位。"
    )

    all_msds_cas: Set[str] = set()
    for m in msds_list:
        all_msds_cas.update(m.cas_set)

    part_col = layout.meta_cols.get("part_name")
    mat_col = layout.meta_cols.get("material_name")
    comp_col = layout.meta_cols.get("composition_name")
    rep_col = layout.meta_cols.get("report_no")
    cas_col = layout.meta_cols.get("cas_no")
    pct_col = layout.meta_cols.get("content_pct")
    wt_col = layout.meta_cols.get("part_weight")
    wt_unit_col = layout.meta_cols.get("weight_unit")
    agency_col = layout.meta_cols.get("agency")
    date_col = layout.meta_cols.get("report_date")

    scan_max_col = min(sheet.ncols, MAX_SCAN_COLS)
    data_rows: List[int] = []
    for r in range(layout.data_start_row, sheet.nrows + 1):
        row_vals = [sheet.get_str(r, c) for c in range(2, scan_max_col + 1)]
        if not any(row_vals):
            continue
        first_txt = sheet.get_str(r, part_col) if part_col else row_vals[0]
        if re.match(r"^(合計|总计|總計|Total|備註|备注|Note|說明|说明)\b", first_txt, re.IGNORECASE):
            continue
        if len(first_txt) > 80:
            continue
        note_cols = [c for c in (part_col, mat_col, comp_col, rep_col) if c is not None]
        if any(_NOTE_ROW_RE.search(sheet.get_str(r, c)) for c in note_cols):
            continue
        has_key_data = any(
            sheet.get_str(r, col)
            for col in (part_col, mat_col, comp_col, cas_col, rep_col, pct_col)
            if col is not None
        )
        if has_key_data:
            data_rows.append(r)

    if not data_rows:
        notes.append(f"工作表 [{sheet.name}] 未找到有效資料列，僅完成表頭偵測。")

    # -----------------------------------------------------------------------
    # 1. 重量驗算（區分合併/成分拆分列 vs 同名同重的獨立部件）
    # -----------------------------------------------------------------------
    total_wt_info = _find_total_weight_in_header(sheet, layout.header_row)
    if total_wt_info and wt_col:
        total_addr, total_g, total_disp = total_wt_info
        sum_g = 0.0
        wt_count = 0

        for block in _row_blocks(sheet, data_rows, part_col):
            # 檢查此區塊是否為「多個未合併且各自獨立 100% 的同名部件」
            part_anchors = {sheet.get_anchor(r, part_col) for r in block} if part_col else set()
            wt_anchors = {sheet.get_anchor(r, wt_col) for r in block}
            is_unmerged_independent = (
                len(block) > 1
                and part_col is not None
                and len(part_anchors) == len(block)
                and len(wt_anchors) == len(block)
                and all(_is_standalone_100_pct(sheet, r, pct_col) for r in block)
            )

            if is_unmerged_independent:
                for r in block:
                    raw_wt = sheet.get_value(r, wt_col)
                    if raw_wt is None or str(raw_wt).strip() == "":
                        continue
                    unit_override = sheet.get_str(r, wt_unit_col).lower() if wt_unit_col else ""
                    default_u = (
                        unit_override if unit_override in _WEIGHT_UNIT_FACTORS else layout.weight_header_unit
                    )
                    parsed = _parse_weight_and_unit(raw_wt, default_u)
                    if not parsed:
                        notes.append(f"工作表 [{sheet.name}] 第 {r} 列重量「{raw_wt}」無法解析，已排除。")
                        continue
                    val, u = parsed
                    grams = _to_grams(val, u)
                    if grams is None:
                        notes.append(f"工作表 [{sheet.name}] 第 {r} 列重量單位「{u}」無法辨識，已排除。")
                        continue
                    sum_g += grams
                    wt_count += 1
                continue

            distinct: Dict[Tuple[float, str], int] = {}
            seen_wt_anchors: Set[Tuple[int, int]] = set()
            for r in block:
                wa = sheet.get_anchor(r, wt_col)
                if wa in seen_wt_anchors:
                    continue
                seen_wt_anchors.add(wa)

                raw_wt = sheet.get_value(r, wt_col)
                if raw_wt is None or str(raw_wt).strip() == "":
                    continue
                unit_override = sheet.get_str(r, wt_unit_col).lower() if wt_unit_col else ""
                default_u = (
                    unit_override if unit_override in _WEIGHT_UNIT_FACTORS else layout.weight_header_unit
                )
                parsed = _parse_weight_and_unit(raw_wt, default_u)
                if not parsed:
                    notes.append(f"工作表 [{sheet.name}] 第 {r} 列重量「{raw_wt}」無法解析，已排除。")
                    continue
                val, u = parsed
                grams = _to_grams(val, u)
                if grams is None:
                    notes.append(f"工作表 [{sheet.name}] 第 {r} 列重量單位「{u}」無法辨識，已排除。")
                    continue
                key = (round(grams, 9), u)
                distinct[key] = distinct.get(key, 0) + 1

            if not distinct:
                continue
            if len(distinct) > 1:
                notes.append(
                    f"工作表 [{sheet.name}] 同一部件區塊（第 {block[0]} 列起）出現多個重量值，已全部加總。"
                )
            for grams, _u in distinct:
                sum_g += grams
                wt_count += 1

        if wt_count > 0:
            tol = max(abs(total_g) * 0.005, 1e-3)
            if abs(sum_g - total_g) > tol:
                issues.append(
                    CheckIssue(
                        sheet_name=sheet.name,
                        cell_address=total_addr,
                        item_name="重量驗算",
                        actual_value=f"{total_disp} ({total_g:.4g}g)",
                        expected_value=f"各部件加總 {sum_g:.4g}g",
                        evidence=f"共 {wt_count} 個部件重量加總與表頭總重量相差 {abs(sum_g - total_g):.4g}g",
                        severity="ERROR",
                        fixable=False,
                    )
                )

    # -----------------------------------------------------------------------
    # 2. 成分資訊：均質材料組成總和 = 100% & CAS No. 核對 MSDS
    # -----------------------------------------------------------------------
    if pct_col:
        seen_pct_anchors: Set[Tuple[int, int]] = set()
        part_blocks = _row_blocks(sheet, data_rows, part_col) if part_col else [list(data_rows)]
        for part_rows in part_blocks:
            if mat_col and mat_col != part_col:
                material_blocks = _row_blocks(sheet, part_rows, mat_col)
            else:
                material_blocks = [part_rows]

            for block in material_blocks:
                entries: List[Tuple[int, float, bool]] = []
                for r in block:
                    pct_anchor = sheet.get_anchor(r, pct_col)
                    if pct_anchor in seen_pct_anchors:
                        continue
                    seen_pct_anchors.add(pct_anchor)

                    raw_pct = sheet.get_value(r, pct_col)
                    if raw_pct is None or str(raw_pct).strip() == "":
                        continue
                    s_pct = str(raw_pct).strip().replace("%", "").replace("％", "").strip()
                    try:
                        num_pct = float(s_pct)
                    except ValueError:
                        continue
                    has_pct_fmt = sheet.is_percent_format(r, pct_col)
                    entries.append((r, num_pct, has_pct_fmt))

                if not entries:
                    continue

                raw_sum = sum(v for _, v, _ in entries)
                # 優先依據 Excel 儲存格的百分比格式判斷；無格式資訊時退回數值推測
                if any(fmt for _, _, fmt in entries):
                    is_decimal_pct = True
                else:
                    is_decimal_pct = (
                        all(0.0 <= v <= 1.0001 for _, v, _ in entries) and abs(raw_sum - 1.0) <= 0.15
                    )
                pct_sum = raw_sum * 100.0 if is_decimal_pct else raw_sum
                if abs(pct_sum - 100.0) > 0.15:
                    first_r = entries[0][0]
                    mat_label = ""
                    if mat_col:
                        mat_label = sheet.get_str(block[0], mat_col)[:24]
                    if not mat_label and part_col:
                        mat_label = sheet.get_str(block[0], part_col)[:24]
                    if not mat_label:
                        mat_label = f"第{first_r}列"
                    issues.append(
                        CheckIssue(
                            sheet_name=sheet.name,
                            cell_address=make_cell_address(first_r, pct_col),
                            item_name="成分含量總和",
                            actual_value=f"{pct_sum:.2f}%",
                            expected_value="100%",
                            evidence=f"均質材料「{mat_label}」共 {len(entries)} 項成分加總不為 100%",
                            severity="ERROR",
                            fixable=False,
                        )
                    )

    if cas_col:
        seen_cas_anchors: Set[Tuple[int, int]] = set()
        cas_re = re.compile(r"\b(\d{2,7}-\d{2}-\d)\b")
        for r in data_rows:
            anchor = sheet.get_anchor(r, cas_col)
            if anchor in seen_cas_anchors:
                continue
            seen_cas_anchors.add(anchor)

            cas_txt = sheet.get_str(r, cas_col)
            if not cas_txt or re.match(r"^(N/?A|NONE|無|无|-|—|/|保密|Trade\s*Secret|Proprietary)$", cas_txt, re.IGNORECASE):
                continue

            found_cas = cas_re.findall(cas_txt)
            if not found_cas:
                continue

            for cas in found_cas:
                if not is_valid_cas_checksum(cas):
                    issues.append(
                        CheckIssue(
                            sheet_name=sheet.name,
                            cell_address=make_cell_address(anchor[0], anchor[1]),
                            item_name="CAS No. 格式",
                            actual_value=cas,
                            expected_value="有效 CAS 編號",
                            evidence="CAS 檢查碼驗證失敗，請確認是否輸入錯誤",
                            severity="WARNING",
                            fixable=False,
                        )
                    )
                elif all_msds_cas and cas not in all_msds_cas:
                    issues.append(
                        CheckIssue(
                            sheet_name=sheet.name,
                            cell_address=make_cell_address(anchor[0], anchor[1]),
                            item_name="CAS No. (MSDS核對)",
                            actual_value=cas,
                            expected_value="存在於內嵌 MSDS",
                            evidence=f"於 {len(msds_list)} 份內嵌 MSDS 中皆未查到此 CAS No.",
                            severity="WARNING",
                            fixable=False,
                        )
                    )

    # -----------------------------------------------------------------------
    # 2b. 欄位 B~J 比對三星原廠成分表（SAMSUNG FMD）
    # -----------------------------------------------------------------------
    if fmd_records:
        fmd_issues, fmd_notes = _check_columns_b_to_j_against_fmd(
            sheet, layout, data_rows, fmd_records, total_wt_info
        )
        issues.extend(fmd_issues)
        notes.extend(fmd_notes)

    # -----------------------------------------------------------------------
    # 3. 報告基本資訊（欄位 K~M：測試報告編號、測試機構、測試日期） & 4. 測試結果
    # -----------------------------------------------------------------------
    if not rep_col:
        notes.append(f"工作表 [{sheet.name}] 未偵測到「測試報告編號」欄位，無法配對 PDF 報告。")
        return issues, notes, True

    checked_cells: Set[Tuple[int, int]] = set()
    warned_missing_reports: Set[Tuple[int, int]] = set()
    warned_blank_reports: Set[Tuple[int, int]] = set()
    warned_empty_reports: Set[Tuple[str, str]] = set()
    matched_pdf_ids: Set[int] = set()
    checked_rep_anchors: Set[Tuple[int, int]] = set()
    km_mismatch = {"report_no": 0, "agency": 0, "report_date": 0}
    available_report_nos = [r.report_no for r in test_reports if r.report_no]

    for r in data_rows:
        rep_anchor = sheet.get_anchor(r, rep_col)
        rep_no_str = sheet.get_str(r, rep_col)
        if not rep_no_str:
            has_substance_or_mat = bool(
                (mat_col and sheet.get_str(r, mat_col))
                or any(sheet.get_str(r, sc) for sc in layout.substance_cols.values())
            )
            if test_reports and has_substance_or_mat and rep_anchor not in warned_blank_reports:
                warned_blank_reports.add(rep_anchor)
                issues.append(
                    CheckIssue(
                        sheet_name=sheet.name,
                        cell_address=make_cell_address(rep_anchor[0], rep_anchor[1]),
                        item_name="測試報告編號",
                        actual_value="(空白)",
                        expected_value="對應之內嵌 PDF 報告編號",
                        evidence="該列有材料或測試數據但未填寫測試報告編號，無法與內嵌 PDF 比對",
                        severity="WARNING",
                        fixable=False,
                    )
                )
            continue
        if re.match(r"^(N/?A|NONE|無|无|-|—|/)$", rep_no_str, re.IGNORECASE):
            continue

        matched_pdf, match_warning = _match_test_report(rep_no_str, test_reports)
        if not matched_pdf:
            if rep_anchor not in warned_missing_reports:
                warned_missing_reports.add(rep_anchor)
                ev_msg = match_warning or "於 Excel 內嵌 PDF 中找不到對應此報告編號的第三方測試報告"
                if not match_warning and available_report_nos:
                    sample_nos = "、".join(available_report_nos[:5])
                    if len(available_report_nos) > 5:
                        sample_nos += f" 等共 {len(available_report_nos)} 份"
                    ev_msg += f"（內嵌 PDF 編號：{sample_nos}）"
                issues.append(
                    CheckIssue(
                        sheet_name=sheet.name,
                        cell_address=make_cell_address(rep_anchor[0], rep_anchor[1]),
                        item_name="測試報告配對",
                        actual_value=rep_no_str,
                        expected_value="對應之內嵌 PDF 報告",
                        report_no=rep_no_str,
                        evidence=ev_msg,
                        severity="WARNING",
                        fixable=False,
                    )
                )
            continue

        matched_pdf_ids.add(id(matched_pdf))

        # 3a. 核對測試報告編號本身（欄位 K）是否與內嵌 PDF 正式編號完全一致
        if rep_anchor not in checked_rep_anchors:
            checked_rep_anchors.add(rep_anchor)
            if matched_pdf.report_no:
                pdf_nos = matched_pdf.all_report_nos or [matched_pdf.report_no]
                exact_str_match = any(
                    rep_no_str.strip().upper() == cand.strip().upper()
                    for cand in pdf_nos
                )
                if not exact_str_match:
                    km_mismatch["report_no"] += 1
                    issues.append(
                        CheckIssue(
                            sheet_name=sheet.name,
                            cell_address=make_cell_address(rep_anchor[0], rep_anchor[1]),
                            item_name="測試報告編號",
                            actual_value=rep_no_str,
                            expected_value=matched_pdf.report_no,
                            report_no=matched_pdf.report_no,
                            pdf_page="P.1",
                            evidence=f"PDF 報告 ({matched_pdf.filename}) 正式編號為 {matched_pdf.report_no}",
                            severity="ERROR",
                            fixable=True,
                            fix_value=matched_pdf.report_no,
                        )
                    )

        report_key = (matched_pdf.filename, matched_pdf.report_no)
        if not matched_pdf.results and report_key not in warned_empty_reports:
            warned_empty_reports.add(report_key)
            issues.append(
                CheckIssue(
                    sheet_name=sheet.name,
                    cell_address=make_cell_address(rep_anchor[0], rep_anchor[1]),
                    item_name="報告結果解析",
                    actual_value=matched_pdf.filename,
                    expected_value="可解析的測試結果",
                    report_no=matched_pdf.report_no or rep_no_str,
                    evidence="報告已配對，但未解析到任何物質結果（可能為掃描影像或表格格式不支援），此報告的測試結果未核對",
                    severity="WARNING",
                    fixable=False,
                )
            )

        # 3b. 核對測試機構（欄位 L）
        if agency_col and matched_pdf.agency:
            ag_anchor = sheet.get_anchor(r, agency_col)
            if ag_anchor not in checked_cells:
                checked_cells.add(ag_anchor)
                ag_actual = sheet.get_str(r, agency_col)
                if not _agencies_match(ag_actual, matched_pdf.agency):
                    km_mismatch["agency"] += 1
                    issues.append(
                        CheckIssue(
                            sheet_name=sheet.name,
                            cell_address=make_cell_address(ag_anchor[0], ag_anchor[1]),
                            item_name="測試機構",
                            actual_value=ag_actual or "(空白)",
                            expected_value=matched_pdf.agency,
                            report_no=matched_pdf.report_no or rep_no_str,
                            pdf_page="P.1",
                            evidence=f"PDF 報告 ({matched_pdf.filename}) 測試機構為 {matched_pdf.agency}",
                            severity="ERROR",
                            fixable=True,
                            fix_value=matched_pdf.agency,
                        )
                    )

        # 3c. 核對測試日期（欄位 M）
        if date_col and matched_pdf.report_date:
            dt_anchor = sheet.get_anchor(r, date_col)
            if dt_anchor not in checked_cells:
                checked_cells.add(dt_anchor)
                raw_dt = sheet.get_value(r, date_col)
                dt_actual_str = sheet.get_str(r, date_col)
                parsed_dt = _parse_excel_date(raw_dt)
                if parsed_dt != matched_pdf.report_date:
                    km_mismatch["report_date"] += 1
                    date_labeled = matched_pdf.date_is_labeled
                    date_evidence = f"PDF 報告日期為 {matched_pdf.raw_date or matched_pdf.report_date}"
                    if not date_labeled:
                        date_evidence += "（未從具名欄位取得，可能有誤，請人工確認後再修改）"
                    issues.append(
                        CheckIssue(
                            sheet_name=sheet.name,
                            cell_address=make_cell_address(dt_anchor[0], dt_anchor[1]),
                            item_name="測試日期",
                            actual_value=dt_actual_str or "(空白)",
                            expected_value=matched_pdf.report_date,
                            report_no=matched_pdf.report_no or rep_no_str,
                            pdf_page="P.1",
                            evidence=date_evidence,
                            severity="ERROR" if date_labeled else "WARNING",
                            fixable=date_labeled,
                            fix_value=matched_pdf.report_date if date_labeled else None,
                        )
                    )

        # 4. 核對各項物質測試結果
        for sub_key, col_idx in layout.substance_cols.items():
            pdf_res = matched_pdf.results.get(sub_key)
            if not pdf_res:
                continue

            cell_anchor = sheet.get_anchor(r, col_idx)
            if cell_anchor in checked_cells:
                continue
            checked_cells.add(cell_anchor)

            actual_str = sheet.get_str(r, col_idx)
            if not results_match(actual_str, pdf_res.expected_value):
                is_ambig = bool(getattr(pdf_res, "ambiguous", False))
                ev = f"PDF 原始結果={pdf_res.raw_result}, MDL={pdf_res.mdl or '-'}, 單位={pdf_res.unit}"
                if is_ambig:
                    ev += "（偵測到多樣品並列，請人工確認後再修改）"
                issues.append(
                    CheckIssue(
                        sheet_name=sheet.name,
                        cell_address=make_cell_address(cell_anchor[0], cell_anchor[1]),
                        item_name=sub_key,
                        actual_value=actual_str or "(空白)",
                        expected_value=pdf_res.expected_value,
                        report_no=matched_pdf.report_no or rep_no_str,
                        pdf_page=f"P.{pdf_res.page}",
                        evidence=ev,
                        severity="WARNING" if is_ambig else "ERROR",
                        fixable=not is_ambig,
                        fix_value=None if is_ambig else pdf_res.expected_value,
                    )
                )

    # 3d. 檢查未被任何資料列引用的內嵌第三方測試報告
    if test_reports:
        for rep in test_reports:
            if id(rep) not in matched_pdf_ids:
                issues.append(
                    CheckIssue(
                        sheet_name=sheet.name,
                        cell_address=f"{col_index_to_letter(rep_col)}欄",
                        item_name="未引用測試報告",
                        actual_value="(未出現於表格)",
                        expected_value=rep.report_no or rep.filename,
                        report_no=rep.report_no,
                        pdf_page="P.1",
                        evidence=f"內嵌 PDF ({rep.filename}) 未被任何資料列的測試報告編號欄引用，請確認是否漏填或填錯編號",
                        severity="WARNING",
                        fixable=False,
                    )
                )

        col_tags = [col_index_to_letter(c) for c in (rep_col, agency_col, date_col) if c is not None]
        col_label = "~".join([col_tags[0], col_tags[-1]]) if len(col_tags) >= 2 else col_tags[0]
        notes.append(
            f"工作表 [{sheet.name}] 欄位 {col_label}（測試報告編號/測試機構/測試日期）已比對內嵌 PDF："
            f"配對 {len(matched_pdf_ids)}/{len(test_reports)} 份"
            f"（編號不符 {km_mismatch['report_no']}、機構不符 {km_mismatch['agency']}、日期不符 {km_mismatch['report_date']}）。"
        )

    return issues, notes, True
