# -*- coding: utf-8 -*-
"""
O-193D Excel 欄位 B～AD 自動核對核心模組
支援：
  - 讀取 .xls (xlrd) 與 .xlsx (openpyxl)，完整處理合併儲存格
  - 自動偵測表頭列（含「部件名稱 / Part name」）與各欄位對應位置（不寫死欄序）
  - 重量驗算（各部件重量統一單位換算加總 vs 表頭總重量）
  - 成分資訊（均質材料組成總和 = 100%、CAS No. 是否存在於內嵌 MSDS）
  - 報告基本資訊（依報告編號配對 PDF，核對測試機構與報告日期）
  - 測試結果（Cd, Pb, Hg, Cr6+, PBB, PBDE, DEHP, BBP, DBP, DIBP, Cl, Br, F, PFOA, PFOS, Be, As, Sb）
    容許 ㎍/cm2、μg/cm2、ug/cm2 符號差異與 38 vs 38.0 數值格式差異
"""

from __future__ import annotations

import datetime as dt
import math
import os
import re
import zipfile
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set, Tuple

from pdf_parser import (
    AGENCY_ALIAS_GROUPS,
    MSDSData,
    SUBSTANCE_KEYS,
    TestReportData,
    is_valid_cas_checksum,
    normalize_unit,
    parse_date_string,
)


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
# 統一的 .xls / .xlsx 工作表讀取器（1-based 座標 + 合併儲存格解析）
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
    ) -> None:
        self.name = name
        self.nrows = nrows
        self.ncols = ncols
        self._grid = grid
        self._merged_map = merged_map
        self.datemode = datemode
        self.formula_without_cache: List[str] = formula_without_cache or []

    def get_anchor(self, row: int, col: int) -> Tuple[int, int]:
        return self._merged_map.get((row, col), (row, col))

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
                    row_vals.append(val)
                grid.append(row_vals)

            merged_map: Dict[Tuple[int, int], Tuple[int, int]] = {}
            for rlo, rhi, clo, chi in sh.merged_cells:
                anchor = (rlo + 1, clo + 1)
                for rr in range(rlo + 1, rhi + 1):
                    for cc in range(clo + 1, chi + 1):
                        merged_map[(rr, cc)] = anchor

            sheets.append(UnifiedSheet(sh.name, sh.nrows, sh.ncols, grid, merged_map, wb.datemode))

    elif ext in (".xlsx", ".xlsm"):
        import openpyxl

        wb = openpyxl.load_workbook(excel_path, data_only=True)
        pending = []
        try:
            for ws in wb.worksheets:
                if target_sheet and ws.title != target_sheet:
                    continue
                nrows = ws.max_row or 0
                # 只核對 B～AD（≤35 欄）；避免右側殘留格式把 max_column 撐到 16384
                ncols = min(ws.max_column or 0, 35)
                if nrows and ncols:
                    grid = [
                        list(row)
                        for row in ws.iter_rows(min_row=1, max_row=nrows, max_col=ncols, values_only=True)
                    ]
                else:
                    grid = []
                merged_map: Dict[Tuple[int, int], Tuple[int, int]] = {}
                for rng in ws.merged_cells.ranges:
                    if rng.min_col > ncols:
                        continue
                    anchor = (rng.min_row, rng.min_col)
                    for rr in range(rng.min_row, rng.max_row + 1):
                        for cc in range(rng.min_col, min(rng.max_col, ncols) + 1):
                            merged_map[(rr, cc)] = anchor
                pending.append((ws.title, nrows, ncols, grid, merged_map))
        finally:
            wb.close()

        # data_only=True 對「從未被 Excel 計算並儲存」的公式會回傳 None。
        # 先用便宜的 XML 掃描確認有公式，再以 read-only 模式找公式座標，
        # 避免沒有公式的活頁簿也要完整載入第二次。
        uncached = (
            _collect_uncached_formulas(excel_path, pending)
            if _workbook_has_formulas(excel_path)
            else {}
        )
        for name, nrows, ncols, grid, merged_map in pending:
            sheets.append(
                UnifiedSheet(
                    name, nrows, ncols, grid, merged_map,
                    formula_without_cache=uncached.get(name, []),
                )
            )
    else:
        raise ValueError(f"不支援的檔案格式：{ext}")

    return sheets


def _workbook_has_formulas(excel_path: str) -> bool:
    """便宜地掃描 worksheet XML 是否含公式，避免無公式時仍第二次完整載入。"""
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
                        if b"<f" in data:
                            return True
                        tail = data[-1:]
    except Exception:
        # 無法判斷時保守執行完整公式檢查
        return True
    return False


def _collect_uncached_formulas(
    excel_path: str,
    sheet_grids: List[Tuple[str, int, int, List[List[Any]], Dict[Tuple[int, int], Tuple[int, int]]]],
) -> Dict[str, List[str]]:
    """找出公式儲存格但 data_only 值為 None 的位置（最多每表記錄 20 個）。"""
    import openpyxl

    result: Dict[str, List[str]] = {}
    try:
        wb = openpyxl.load_workbook(excel_path, read_only=True, data_only=False)
    except Exception:
        return result
    try:
        for name, nrows, ncols, grid, _ in sheet_grids:
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
# 表頭偵測與欄位對應（自動偵測欄位 B～AD，不寫死順序）
# ---------------------------------------------------------------------------

_HEADER_ANCHOR_RE = re.compile(r"部件名稱|部件名称|零件名稱|零件名称|Part\s*Name", re.IGNORECASE)

_META_COLUMN_PATTERNS: Dict[str, List[str]] = {
    "part_name": [r"部件名稱|部件名称|零件名稱|零件名称|Part\s*Name"],
    # 先辨識「重量單位」，避免被下方的「重量」規則吃掉
    "weight_unit": [r"重量單位|重量单位|單位|单位|Unit"],
    "content_pct": [r"含量|百分比|比例|組成比|组成比|Content|Composition\s*\(?%\)?|Weight\s*%"],
    "part_weight": [
        r"(?:部件重|零件重|單重|单重|淨重|净重|重量|Weight)(?!.*(?:單位|单位|Unit|%|％|百分比|比例))"
    ],
    "material_name": [r"均質材料|均质材料|材質名稱|材质名称|材料名稱|材料名称|Homogeneous\s*Material|Material\s*Name"],
    "composition_name": [r"成分名稱|成份名稱|成分名称|化學物質|化学物质|物質名稱|物质名称|Substance\s*Name|Chemical\s*Name|Ingredient"],
    "cas_no": [r"\bCAS\b|CAS\s*No|CAS編號|CAS编号"],
    "report_no": [r"報告編號|报告编号|測試報告編號|测试报告编号|檢測報告編號|检测报告编号|Report\s*No|Report\s*Number"],
    "agency": [r"測試機構|测试机构|檢測機構|检测机构|實驗室|实验室|Test\s*Agency|Testing\s*Lab|Lab(?:oratory)?"],
    "report_date": [r"測試日期|测试日期|報告日期|报告日期|簽發日期|签发日期|Test\s*Date|Report\s*Date|Issue\s*Date"],
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

# 與 PDF 解析器的物質鍵保持一致；只改一邊時立即在 import 階段失敗
if {key for key, _ in _SUBSTANCE_HEADER_PATTERNS} != set(SUBSTANCE_KEYS):
    raise RuntimeError(
        "excel_checker 與 pdf_parser 的物質鍵不一致，請同步更新 "
        "_SUBSTANCE_HEADER_PATTERNS 與 SUBSTANCE_PATTERNS"
    )


@dataclass
class HeaderLayout:
    header_row: int            # 主表頭列 (1-based)
    data_start_row: int        # 資料起始列 (1-based)
    meta_cols: Dict[str, int]  # field_key -> col_index (1-based)
    substance_cols: Dict[str, int]  # substance_key -> col_index (1-based)
    weight_header_unit: str = "g"   # 表頭中若標示「重量(g)」或「重量(mg)」


def detect_header_layout(sheet: UnifiedSheet) -> Optional[HeaderLayout]:
    """自動尋找含「部件名稱 / Part name」的表頭列，並掃描欄位 B～AD 建立欄位對應。"""
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

    # 偵測多列表頭（例如 header_row 與 header_row+1 皆為表頭，常見於 RoHS 合併大標題 + 子項目）
    header_rows = [header_row]
    if header_row + 1 <= sheet.nrows:
        # 若下一列出現任何物質關鍵字或 CAS/報告編號，則納入表頭範圍
        next_row_text = " ".join(sheet.get_str(header_row + 1, c) for c in range(2, min(sheet.ncols, 35) + 1))
        if re.search(r"\b(Cd|Pb|Hg|Cr6\+?|PBB|PBDE|DEHP|CAS)\b|檢出|限值|單位", next_row_text, re.IGNORECASE):
            header_rows.append(header_row + 1)

    data_start_row = max(header_rows) + 1
    # 若資料起始列其實是「限值 / Limit / 單位」說明列，再往下跳一列
    if data_start_row <= sheet.nrows:
        row_probe = " ".join(sheet.get_str(data_start_row, c) for c in range(2, min(sheet.ncols, 35) + 1))
        if re.search(r"(?:^|\s)(?:Limit|限值|管制標準|管控標準|標準值|單位|Unit)(?:\s|$)", row_probe, re.IGNORECASE):
            if not sheet.get_str(data_start_row, 2) or re.search(r"限值|Limit|標準", sheet.get_str(data_start_row, 2), re.IGNORECASE):
                data_start_row += 1

    # 掃描欄位 B (2) ～ AD (30)（若工作表更寬且有物質延伸，最多掃到 min(ncols, 35)）
    scan_max_col = min(sheet.ncols, 35)
    meta_cols: Dict[str, int] = {}
    substance_cols: Dict[str, int] = {}
    weight_header_unit = "g"

    for c in range(2, scan_max_col + 1):
        # 組合該欄在所有表頭列的文字（去重）
        parts = []
        for hr in header_rows:
            t = sheet.get_str(hr, c)
            if t and t not in parts:
                parts.append(t)
        combined = " ".join(parts).strip()
        if not combined:
            continue

        # 優先辨識物質欄位（避免 Cl/Br/F 等被誤配到其他欄位）
        # 由下往上逐列表頭比對：子表頭優先，找不到再回上層表頭；
        # 可處理「主表頭 Cd/Pb + 下一列限值/單位」與兩列式子表頭。
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

        # 辨識中繼資料欄位
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
    """換算為公克；無法辨識的單位回傳 None（不再默默當成 g）。"""
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
    # 只接受「數值 + 單位」的完整內容；避免從長備註（例如 GQS-ZZ09-13）誤抓數字當重量
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
    """在表頭上方區域搜尋「總重量 / Total Weight」，回傳 (cell_address, grams, raw_display)。"""
    label_re = re.compile(r"總重量|总重量|產品總重|产品总重|Total\s*Weight", re.IGNORECASE)
    for r in range(1, header_row):
        for c in range(1, min(sheet.ncols, 20) + 1):
            txt = sheet.get_str(r, c)
            if not txt or not label_re.search(txt):
                continue
            label_match = label_re.search(txt)
            # 情況 1：數值在同一格，例如 "總重量: 12,500 g"（支援千分位）
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
                    # 單位可能寫在標籤括號內，例如 "總重量(kg): 1.5"
                    u_lbl = re.search(
                        rf"[\(（]\s*{_WEIGHT_TOKEN_RE}\s*[\)）]", txt, re.IGNORECASE
                    )
                    u = u_lbl.group(1).lower() if u_lbl else "g"
                grams = _to_grams(val, u)
                if grams is not None:
                    return make_cell_address(r, c), grams, f"{val}{u}"

            # 情況 2：數值在右側相鄰儲存格（最多往右找 3 格）
            for dc in range(1, 4):
                if c + dc > sheet.ncols:
                    break
                # 跳過同一合併格
                if sheet.get_anchor(r, c + dc) == sheet.get_anchor(r, c):
                    continue
                v_raw = sheet.get_value(r, c + dc)
                parsed = _parse_weight_and_unit(v_raw, "g")
                if parsed:
                    val, u = parsed
                    # 檢查再右一格是否為單位（如 "g" / "mg" / "kg"）
                    if c + dc + 1 <= sheet.ncols:
                        u_next = sheet.get_str(r, c + dc + 1).lower()
                        if u_next in _WEIGHT_UNIT_FACTORS:
                            u = u_next
                    # 也檢查標籤本身是否註明單位，例如 "總重量(g)"
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
    """只允許前綴/後綴關係（版本尾碼），避免任意包含造成誤配。"""
    if not a or not b:
        return False
    short, long = (a, b) if len(a) <= len(b) else (b, a)
    if len(short) < 8:
        return False
    return long.startswith(short) or long.endswith(short)


def _match_test_report(
    report_no_cell: str, reports: List[TestReportData]
) -> Tuple[Optional[TestReportData], Optional[str]]:
    """
    回傳 (配對到的報告, 警告訊息)。
    若有多份報告同時符合，回傳 (None, 警告)，交由人工確認，不任意挑選。
    """
    if not report_no_cell:
        return None, None
    target_norm = _normalize_report_key(report_no_cell)
    if len(target_norm) < 4:
        return None, None

    # 1. 精準比對（忽略空白與符號）
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

    # 2. 前綴/後綴比對（處理 Excel 填寫精簡編號或 PDF 帶版本尾碼如 _01）
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

    # 3. 檔名比對 fallback（僅接受唯一命中）
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
    """
    將測試結果拆解為 (kind, numeric_value, normalized_unit)：
      - kind: lt/le/gt/ge/approx/num/text/empty
      - 統一 ㎍/cm2、μg/cm2、µg/cm2、ug/cm2、μg/cm² 為 "μg/cm2"
      - 數值轉 float，使 38 == 38.0、<2 == <2.0、<0.10 == <0.1、-0.5 可解析
    """
    s = (val_str or "").strip()
    if not s:
        return ("empty", None, "")

    # 統一全形符號與千分位
    s_norm = (
        s.replace("＜", "<").replace("＞", ">")
        .replace("≤", "≤").replace("≥", "≥")
        .replace("～", "~").replace("，", "").replace(",", "")
    )
    s_norm = re.sub(r"[㎍μµu]g\s*/\s*cm[2²]", "μg/cm2", s_norm, flags=re.IGNORECASE)

    # 比較符號 + 數值 [unit]
    m_sym = re.match(r"^([<≤>≥~])\s*(-?\d+(?:\.\d+)?)\s*(.*)$", s_norm)
    if m_sym:
        kind = _COMPARE_SYMBOLS[m_sym.group(1)]
        num = float(m_sym.group(2))
        unit = normalize_unit(m_sym.group(3)) if m_sym.group(3).strip() else "ppm"
        return (kind, num, unit)

    # 純數字 [unit]
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
    """
    比對 Excel 填寫值與報告正確值：
      - 容許 ㎍/cm2、μg/cm2、ug/cm2 符號差異
      - 容許 38 vs 38.0、<2 vs ≤2（同方向）數值格式差異
      - 支援 >、≥、~ 與負數
      - ND / N.D. 視為不符合 <MDL（強制要求修正）
    """
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
      - 未合併：連續相同文字視為同一區塊（避免每列重複填寫造成重複計算）
      - 空白：沿用目前區塊（常見於合併儲存格或只填第一列）
    """
    if col is None:
        return [list(rows)]

    blocks: List[List[int]] = []
    cur_key: Any = None
    cur_rows: List[int] = []
    for r in rows:
        text = sheet.get_str(r, col)
        # 以「文字」為區塊鍵：合併儲存格的錨點列與被覆蓋列會取得相同文字，
        # 未合併但每列重複填寫的名稱也能連續分組。
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


def check_sheet(
    sheet: UnifiedSheet,
    test_reports: List[TestReportData],
    msds_list: List[MSDSData],
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

    # 決定有效資料列範圍（避開底部空白與備註列）
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

    data_rows: List[int] = []
    for r in range(layout.data_start_row, sheet.nrows + 1):
        # 若整列 B~AD 皆空或為「合計 / Total / 備註 / Note」則略過
        row_vals = [sheet.get_str(r, c) for c in range(2, min(sheet.ncols, 35) + 1)]
        if not any(row_vals):
            continue
        first_txt = sheet.get_str(r, part_col) if part_col else row_vals[0]
        if re.match(r"^(合計|总计|總計|Total|備註|备注|Note|說明|说明)\b", first_txt, re.IGNORECASE):
            continue
        # 排除頁尾說明／簽核列：這類列常被合併儲存格帶入部件欄，
        # 若當成資料列會把備註中的數字誤認為重量或報告編號。
        if len(first_txt) > 80:
            continue
        note_cols = [c for c in (part_col, mat_col, comp_col, rep_col) if c is not None]
        if any(_NOTE_ROW_RE.search(sheet.get_str(r, c)) for c in note_cols):
            continue
        # 需有部件／材料／成分名稱、CAS、報告編號或百分比其中之一；
        # 百分比列即使其他欄位空白（例如 Balance/Other）也要保留給成分加總。
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
    # 1. 重量驗算
    # -----------------------------------------------------------------------
    total_wt_info = _find_total_weight_in_header(sheet, layout.header_row)
    if total_wt_info and wt_col:
        total_addr, total_g, total_disp = total_wt_info
        sum_g = 0.0
        wt_count = 0

        # 以「部件」為區塊，每個區塊只計一次重量（相容合併與每列重複填寫）
        for block in _row_blocks(sheet, data_rows, part_col):
            distinct: Dict[Tuple[float, str], int] = {}
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
            tol = max(abs(total_g) * 0.005, 1e-3)  # 容許 0.5% 四捨五入誤差或 0.001g
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

        # 先切部件區塊，再切均質材料區塊；未合併而重複填寫的名稱也能正確分組
        part_blocks = _row_blocks(sheet, data_rows, part_col) if part_col else [list(data_rows)]
        for part_rows in part_blocks:
            if mat_col and mat_col != part_col:
                material_blocks = _row_blocks(sheet, part_rows, mat_col)
            else:
                material_blocks = [part_rows]

            for block in material_blocks:
                entries: List[Tuple[int, float]] = []
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
                    entries.append((r, num_pct))

                if not entries:
                    continue

                raw_sum = sum(v for _, v in entries)
                # 判斷是否為 Excel 百分比小數格式（例如 0.35 + 0.65 = 1.0）
                is_decimal_pct = (
                    all(0.0 <= v <= 1.0001 for _, v in entries) and abs(raw_sum - 1.0) <= 0.15
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
    # 3. 報告基本資訊（報告編號配對 PDF、測試機構、測試日期） & 4. 測試結果（欄位 N～AD）
    # -----------------------------------------------------------------------
    if not rep_col:
        notes.append(f"工作表 [{sheet.name}] 未偵測到「測試報告編號」欄位，無法配對 PDF 報告。")
        return issues, notes, True

    checked_cells: Set[Tuple[int, int]] = set()
    warned_missing_reports: Set[Tuple[int, int]] = set()
    warned_empty_reports: Set[Tuple[str, str]] = set()

    for r in data_rows:
        rep_anchor = sheet.get_anchor(r, rep_col)
        rep_no_str = sheet.get_str(r, rep_col)
        if not rep_no_str or re.match(r"^(N/?A|NONE|無|无|-|—|/)$", rep_no_str, re.IGNORECASE):
            continue

        matched_pdf, match_warning = _match_test_report(rep_no_str, test_reports)
        if not matched_pdf:
            if rep_anchor not in warned_missing_reports:
                warned_missing_reports.add(rep_anchor)
                issues.append(
                    CheckIssue(
                        sheet_name=sheet.name,
                        cell_address=make_cell_address(rep_anchor[0], rep_anchor[1]),
                        item_name="測試報告配對",
                        actual_value=rep_no_str,
                        expected_value="對應之內嵌 PDF 報告",
                        report_no=rep_no_str,
                        evidence=match_warning
                        or "於 Excel 內嵌 PDF 中找不到對應此報告編號的第三方測試報告",
                        severity="WARNING",
                        fixable=False,
                    )
                )
            continue

        # 配對成功但完全解析不到物質結果：可能是掃描影像或表格格式不支援，
        # 必須明確提示，避免「什麼都沒檢查」卻回報全部一致。
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

        # 3a. 核對測試機構 (欄位 L)
        if agency_col and matched_pdf.agency:
            ag_anchor = sheet.get_anchor(r, agency_col)
            if ag_anchor not in checked_cells:
                checked_cells.add(ag_anchor)
                ag_actual = sheet.get_str(r, agency_col)
                if not _agencies_match(ag_actual, matched_pdf.agency):
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

        # 3b. 核對測試日期 (欄位 M)
        if date_col and matched_pdf.report_date:
            dt_anchor = sheet.get_anchor(r, date_col)
            if dt_anchor not in checked_cells:
                checked_cells.add(dt_anchor)
                raw_dt = sheet.get_value(r, date_col)
                dt_actual_str = sheet.get_str(r, date_col)
                parsed_dt = _parse_excel_date(raw_dt)
                if parsed_dt != matched_pdf.report_date:
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

        # 4. 核對各項物質測試結果 (欄位 N～AD)
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
                issues.append(
                    CheckIssue(
                        sheet_name=sheet.name,
                        cell_address=make_cell_address(cell_anchor[0], cell_anchor[1]),
                        item_name=sub_key,
                        actual_value=actual_str or "(空白)",
                        expected_value=pdf_res.expected_value,
                        report_no=matched_pdf.report_no or rep_no_str,
                        pdf_page=f"P.{pdf_res.page}",
                        evidence=f"PDF 原始結果={pdf_res.raw_result}, MDL={pdf_res.mdl or '-'}, 單位={pdf_res.unit}",
                        severity="ERROR",
                        fixable=True,
                        fix_value=pdf_res.expected_value,
                    )
                )

    return issues, notes, True
