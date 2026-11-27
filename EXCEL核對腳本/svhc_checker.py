# -*- coding: utf-8 -*-
"""
Foxlink O-202AH《REACH 法規 SVHC 物質調查表》自動核對模組

比對來源：
  1. 表單內附「REACH 要求通報的高關注物質（SVHC）一覽表」（目前 253 項，含多組異構體 CAS）
  2. 同資料夾／同批次的 O-193D 展開表（含檔名 O-193D 前的正崴料號、表頭料號／聯絡人／總重、以及欄位 I 所有 CAS）
  3. SAMSUNG FMD 三星原廠成分表（依供應商料號配對總重與所有成分 CAS）
  4. 表單自身填寫規範與數學驗算（不含時後方留空、含有時 SVHC重量 ÷ 物料重量 == 比重、≥ 0.1% 警示）
"""

from __future__ import annotations

import datetime as dt
import math
import os
import re
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Set, Tuple

from excel_checker import (
    CheckIssue,
    CheckSummary,
    UnifiedSheet,
    _find_header_field_value,
    _find_total_weight_in_header,
    _parse_excel_date,
    _parse_weight_and_unit,
    _to_grams,
    detect_header_layout,
    extract_foxlink_pn_from_filename,
    load_workbook_sheets,
    make_cell_address,
)
from fmd_matcher import FMDRecord, match_samsung_fmd
from pdf_parser import is_valid_cas_checksum

_CAS_RE = re.compile(r"\b(\d{2,7}-\d{2}-\d)\b")
_NONE_SVHC_RE = re.compile(r"^(?:不含|無|无|未含|未檢出|None|N/?A|No|Not\s*Contained|-|—|/)$", re.IGNORECASE)
_EXAMPLE_PN_RE = re.compile(r"XXXX|8112-9000-XXX|5811-XXXX|5812-XXXX", re.IGNORECASE)
_SKIP_SHEET_RE = re.compile(r"範例|範本|Example|Template", re.IGNORECASE)

_SVHC_TABLE_ANCHOR_RE = re.compile(r"含有\s*SVHC|The\s*name\s*of\s*SVHC|SVHC登記號|SVHC\s*CAS", re.IGNORECASE)
_SVHC_CATALOG_TITLE_RE = re.compile(r"高關注物質.*一覽表|List\s+of\s+SVHCs", re.IGNORECASE)
_FILENAME_O202_PN_RE = re.compile(
    r"^([A-Z0-9][A-Z0-9\-]{5,})\s*[_\-\s]*(?:REACH|SVHC|O-?202)",
    re.IGNORECASE,
)


def extract_foxlink_pn_from_o202_filename(excel_path: str) -> Optional[str]:
    """
    從 O-202AH 檔名解析開頭的正崴料號，例如：
      '07C6-105M-43AD REACH法規SVHC物質調查表O-202 AH03.xls' -> '07C6-105M-43AD'
    """
    if not excel_path:
        return None
    stem = os.path.splitext(os.path.basename(excel_path))[0].strip()
    m = _FILENAME_O202_PN_RE.search(stem)
    if not m:
        return None
    return m.group(1).strip().rstrip("-_")

_O193D_PRODUCT_NAME_RE = re.compile(r"產品名稱|产品名称|Product\s*name", re.IGNORECASE)
_O193D_COMPANY_RE = re.compile(r"公司名稱|公司名称|Compony\s*Name|Company\s*Name", re.IGNORECASE)
_O193D_CONTACT_RE = re.compile(r"填表人|Contact\s*Name", re.IGNORECASE)
_O193D_TEL_RE = re.compile(r"電話|电话|Tel\.?\s*No", re.IGNORECASE)
_O193D_EMAIL_RE = re.compile(r"電子郵件|电子邮件|E-?mail", re.IGNORECASE)
_O193D_DATE_RE = re.compile(r"填表日期|Date\s*:", re.IGNORECASE)


@dataclass
class O193DReference:
    """從對應 O-193D 展開表抽取的比對參考資料。"""
    filepath: str
    filename: str
    filename_foxlink_pn: str = ""
    foxlink_pn: str = ""
    supplier_pn: str = ""
    product_name: str = ""
    total_weight_mg: Optional[float] = None
    total_weight_disp: str = ""
    company_name: str = ""
    contact_name: str = ""
    tel: str = ""
    email: str = ""
    fill_date: str = ""
    cas_set: Set[str] = field(default_factory=set)

    @property
    def canonical_foxlink_pn(self) -> str:
        return self.filename_foxlink_pn or self.foxlink_pn


def _norm_key(text: str) -> str:
    return re.sub(r"\s+", "", (text or "").strip()).upper()


def _format_mg(val_mg: float) -> str:
    s = f"{round(val_mg, 6):.6f}".rstrip("0").rstrip(".")
    return f"{s}mg"


def _find_o202_table_header(sheet: UnifiedSheet) -> Optional[Tuple[int, Dict[str, int]]]:
    """尋找 O-202AH 調查表明細表頭列與欄位對應。"""
    max_r = min(sheet.nrows, 20)
    max_c = min(sheet.ncols, 15)
    for r in range(1, max_r + 1):
        row_text = " ".join(sheet.get_str(r, c) for c in range(1, max_c + 1))
        if not _SVHC_TABLE_ANCHOR_RE.search(row_text):
            continue
        cols: Dict[str, int] = {}
        for c in range(1, max_c + 1):
            t = sheet.get_str(r, c)
            if not t:
                continue
            if re.search(r"供應商料號|供应商料号|Supplier\s*P/?N", t, re.IGNORECASE):
                cols.setdefault("supplier_pn", c)
            elif re.search(r"正崴料號|正崴料号|Foxlink\s*P/?N", t, re.IGNORECASE):
                cols.setdefault("foxlink_pn", c)
            elif re.search(r"物料名稱|物料名称|Description", t, re.IGNORECASE):
                cols.setdefault("description", c)
            elif re.search(r"含有\s*SVHC|name\s*of\s*SVHC", t, re.IGNORECASE):
                cols.setdefault("svhc_name", c)
            elif re.search(r"SVHC\s*登記號|SVHC\s*登记号|SVHC\s*CAS", t, re.IGNORECASE):
                cols.setdefault("svhc_cas", c)
            elif re.search(r"物料重量|weight\s*of\s*materials", t, re.IGNORECASE):
                cols.setdefault("material_weight", c)
            elif re.search(r"SVHC\s*重量|weight\s*of\s*SVHC", t, re.IGNORECASE):
                cols.setdefault("svhc_weight", c)
            elif re.search(r"SVHC\s*占物料比重|weight\s*%\s*of\s*SVHC", t, re.IGNORECASE):
                cols.setdefault("svhc_pct", c)
            elif re.search(r"使用部位|用途|location|application", t, re.IGNORECASE):
                cols.setdefault("svhc_usage", c)
            elif re.search(r"削減日期|削减日期|phased\s*out", t, re.IGNORECASE):
                cols.setdefault("phase_out_date", c)
        if "svhc_name" in cols and ("supplier_pn" in cols or "foxlink_pn" in cols):
            return r, cols
    return None


def is_o202_svhc_workbook(sheets: Sequence[UnifiedSheet]) -> bool:
    """判斷活頁簿是否為 Foxlink O-202AH REACH SVHC 物質調查表。"""
    for sh in sheets:
        if _find_o202_table_header(sh) is not None:
            return True
    return False


def extract_svhc_catalog(sheets: Sequence[UnifiedSheet]) -> Dict[str, str]:
    """從活頁簿內附的 SVHC 一覽表工作表抽取所有 CAS No. -> 物質名稱對照。"""
    catalog: Dict[str, str] = {}
    for sh in sheets:
        top_text = " ".join(
            sh.get_str(r, c)
            for r in range(1, min(sh.nrows, 5) + 1)
            for c in range(1, min(sh.ncols, 6) + 1)
        )
        if not _SVHC_CATALOG_TITLE_RE.search(top_text):
            continue

        cas_col = None
        name_col = None
        header_r = None
        for r in range(1, min(sh.nrows, 8) + 1):
            for c in range(1, min(sh.ncols, 8) + 1):
                t = sh.get_str(r, c)
                if re.search(r"\bCAS\s*NO", t, re.IGNORECASE):
                    cas_col = c
                elif re.search(r"化學物質名稱|化学物质名称|Substance\s*name", t, re.IGNORECASE):
                    name_col = c
            if cas_col and name_col:
                header_r = r
                break

        if not header_r or not cas_col or not name_col:
            continue

        for r in range(header_r + 1, sh.nrows + 1):
            cas_raw = sh.get_str(r, cas_col)
            name_raw = sh.get_str(r, name_col)
            if not cas_raw:
                continue
            short_name = name_raw.splitlines()[0].strip() if name_raw else ""
            for cas in _CAS_RE.findall(cas_raw):
                catalog.setdefault(cas, short_name or cas)
    return catalog


def extract_o193d_reference(o193d_path: str) -> Optional[O193DReference]:
    """從指定的 O-193D Excel 檔案抽取比對用摘要資訊（含檔名正崴料號與所有 CAS）。"""
    abs_path = os.path.abspath(o193d_path)
    if not os.path.isfile(abs_path):
        return None
    try:
        sheets = load_workbook_sheets(abs_path)
    except Exception:
        return None

    fn_fox = extract_foxlink_pn_from_filename(abs_path) or ""

    for sh in sheets:
        if _SKIP_SHEET_RE.search(sh.name):
            continue
        layout = detect_header_layout(sh)
        if not layout:
            continue

        fox = _find_header_field_value(sh, layout.header_row, re.compile(r"Foxlink\s*產品料號|Foxlink\s*产品料号|Foxlink\s*Product\s*No", re.IGNORECASE)) or ""
        sup = _find_header_field_value(sh, layout.header_row, re.compile(r"供應商產品料號|供应商产品料号|Supplier\s*Product\s*No", re.IGNORECASE)) or ""
        prod = _find_header_field_value(sh, layout.header_row, _O193D_PRODUCT_NAME_RE) or ""
        comp = _find_header_field_value(sh, layout.header_row, _O193D_COMPANY_RE) or ""
        contact = _find_header_field_value(sh, layout.header_row, _O193D_CONTACT_RE) or ""
        tel = _find_header_field_value(sh, layout.header_row, _O193D_TEL_RE) or ""
        email = _find_header_field_value(sh, layout.header_row, _O193D_EMAIL_RE) or ""
        raw_dt = _find_header_field_value(sh, layout.header_row, _O193D_DATE_RE) or ""
        fill_dt = _parse_excel_date(raw_dt) or raw_dt

        wt_info = _find_total_weight_in_header(sh, layout.header_row)
        total_mg = (wt_info[1] * 1000.0) if wt_info else None
        total_disp = wt_info[2] if wt_info else ""

        cas_set: Set[str] = set()
        cas_col = layout.meta_cols.get("cas_no")
        if cas_col:
            for r in range(layout.data_start_row, sh.nrows + 1):
                txt = sh.get_str(r, cas_col)
                for cas in _CAS_RE.findall(txt):
                    cas_set.add(cas)

        return O193DReference(
            filepath=abs_path,
            filename=os.path.basename(abs_path),
            filename_foxlink_pn=fn_fox,
            foxlink_pn=fox.strip(),
            supplier_pn=sup.strip(),
            product_name=prod.strip(),
            total_weight_mg=total_mg,
            total_weight_disp=total_disp,
            company_name=comp.strip(),
            contact_name=contact.strip(),
            tel=tel.strip(),
            email=email.strip(),
            fill_date=fill_dt,
            cas_set=cas_set,
        )
    return None


def find_matching_o193d(
    svhc_path: str,
    foxlink_pn: str,
    supplier_pn: str,
    extra_o193d_paths: Sequence[str] = (),
) -> Optional[O193DReference]:
    """
    在同資料夾與同批次檔案中尋找對應的 O-193D 展開表作為比對來源：
    優先配對檔名 O-193D 前的正崴料號，其次比對表頭正崴料號與供應商料號。
    """
    candidates: List[str] = []
    seen: Set[str] = set()

    def _add(path: str) -> None:
        ap = os.path.abspath(path)
        key = os.path.normcase(ap)
        if key in seen or key == os.path.normcase(os.path.abspath(svhc_path)):
            return
        name = os.path.basename(ap)
        if name.startswith("~$") or not os.path.splitext(name)[1].lower() in (".xls", ".xlsx", ".xlsm"):
            return
        if re.search(r"O-?193D", name, re.IGNORECASE):
            seen.add(key)
            candidates.append(ap)

    for p in extra_o193d_paths:
        if os.path.isfile(p):
            _add(p)

    folder = os.path.dirname(os.path.abspath(svhc_path))
    if os.path.isdir(folder):
        try:
            with os.scandir(folder) as entries:
                for entry in entries:
                    if entry.is_file():
                        _add(entry.path)
        except OSError:
            pass

    if not candidates:
        return None

    target_fox = _norm_key(foxlink_pn)
    target_sup = _norm_key(supplier_pn)

    scored: List[Tuple[int, O193DReference]] = []
    for path in candidates:
        ref = extract_o193d_reference(path)
        if ref is None:
            continue
        score = 0
        if target_fox and _norm_key(ref.filename_foxlink_pn) == target_fox:
            score += 100
        if target_fox and _norm_key(ref.foxlink_pn) == target_fox:
            score += 80
        if target_sup and _norm_key(ref.supplier_pn) == target_sup:
            score += 60
        # 優先非備份、非暫存的正式檔
        if not re.search(r"修正前備份|已修正", ref.filename):
            score += 5
        if score > 0:
            scored.append((score, ref))

    if not scored:
        return None
    scored.sort(key=lambda x: x[0], reverse=True)
    return scored[0][1]


def _extract_inline_header_field(
    sheet: UnifiedSheet, header_row: int, label_re: re.Pattern
) -> Optional[Tuple[int, int, str]]:
    """
    O-202AH 表頭常將標籤與填寫值寫在同一儲存格（例如 '供應商名稱/代碼 Supplier Name/vendor code:力垣企業股份有限公司'），
    或寫在右側相鄰儲存格（例如日期在 B6）。回傳 (row, col, value)。
    """
    for r in range(1, header_row):
        for c in range(1, min(sheet.ncols, 12) + 1):
            txt = sheet.get_str(r, c)
            if not txt or not label_re.search(txt):
                continue
            m_colon = re.search(r"[:：]\s*(.+)$", txt)
            if m_colon and m_colon.group(1).strip():
                ar, ac = sheet.get_anchor(r, c)
                return ar, ac, m_colon.group(1).strip()
            for dc in range(1, 3):
                if c + dc <= sheet.ncols and sheet.get_anchor(r, c + dc) != sheet.get_anchor(r, c):
                    val = sheet.get_str(r, c + dc)
                    if val:
                        ar, ac = sheet.get_anchor(r, c + dc)
                        return ar, ac, val
    return None


def _is_example_o202_sheet(sheet: UnifiedSheet, header_row: int, cols: Dict[str, int]) -> bool:
    """判斷該 O-202AH 工作表是否為範例頁（名稱含範例，或明細料號含 XXXX 範例佔位）。"""
    if _SKIP_SHEET_RE.search(sheet.name):
        return True
    check_cols = [c for k, c in cols.items() if k in ("supplier_pn", "foxlink_pn")]
    for r in range(header_row + 1, min(sheet.nrows, header_row + 6) + 1):
        for c in check_cols:
            if _EXAMPLE_PN_RE.search(sheet.get_str(r, c)):
                return True
    return False


def _parse_o202_weight_mg(raw: object) -> Optional[Tuple[float, bool]]:
    """
    解析 O-202AH 物料/SVHC 重量並統一換算為 mg。
    回傳 (weight_mg, has_explicit_unit)；若無明寫單位，依表頭預設為 grams。
    """
    if raw is None or str(raw).strip() == "":
        return None
    s = str(raw).strip()
    has_unit = bool(re.search(r"(kg|mg|μg|ug|µg|公斤|千克|公克|毫克|微克|克|g)\s*$", s, re.IGNORECASE))
    parsed = _parse_weight_and_unit(raw, default_unit="g")
    if not parsed:
        return None
    grams = _to_grams(parsed[0], parsed[1])
    if grams is None:
        return None
    return grams * 1000.0, has_unit


def check_o202_workbook(
    excel_path: str,
    sheets: Sequence[UnifiedSheet],
    fmd_records: Optional[List[FMDRecord]] = None,
    extra_o193d_paths: Sequence[str] = (),
    include_example: bool = False,
    target_sheet: Optional[str] = None,
) -> CheckSummary:
    """核對整份 O-202AH《REACH 法規 SVHC 物質調查表》工作簿。"""
    summary = CheckSummary(excel_path=os.path.abspath(excel_path))
    svhc_catalog = extract_svhc_catalog(sheets)
    if svhc_catalog:
        summary.notes.append(f"已從活頁簿內附 SVHC 一覽表載入 {len(svhc_catalog)} 組高關注物質 CAS No.。")

    for sh in sheets:
        summary.sheets_scanned.append(sh.name)
        found = _find_o202_table_header(sh)
        if not found:
            continue
        header_row, cols = found

        if (
            not include_example
            and (target_sheet is None or target_sheet != sh.name)
            and _is_example_o202_sheet(sh, header_row, cols)
        ):
            summary.notes.append(f"工作表 [{sh.name}] 為 O-202AH 填寫範例頁，已略過（可用 --include-example 納入）。")
            continue

        summary.sheets_checked.append(sh.name)

        # 收集有效資料列（到「備注 / Notes」為止）
        data_rows: List[int] = []
        for r in range(header_row + 1, sh.nrows + 1):
            row_vals = [sh.get_str(r, c) for c in range(1, min(sh.ncols, 12) + 1)]
            joined = " ".join(v for v in row_vals if v)
            if not joined:
                continue
            if re.match(r"^(?:備注|備註|备注|Notes?|O-202)", joined, re.IGNORECASE):
                break
            data_rows.append(r)

        if not data_rows:
            summary.issues.append(
                CheckIssue(
                    sheet_name=sh.name,
                    cell_address=f"A{header_row + 1}",
                    item_name="SVHC 調查表明細",
                    actual_value="(空白)",
                    expected_value="至少 1 筆物料宣告資料",
                    evidence="O-202AH 調查表未填寫任何料號與物料資料",
                    severity="ERROR",
                    fixable=False,
                )
            )
            continue

        # 記錄當前歸屬料號（多列共用同一料號時，向下沿用）
        cur_sup = ""
        cur_fox = ""
        cur_desc = ""
        cur_mat_wt_mg: Optional[float] = None
        matched_o193d_cache: Dict[Tuple[str, str], Optional[O193DReference]] = {}
        header_checked_against_o193d = False

        c_sup = cols.get("supplier_pn")
        c_fox = cols.get("foxlink_pn")
        c_desc = cols.get("description")
        c_svhc = cols.get("svhc_name")
        c_cas = cols.get("svhc_cas")
        c_mwt = cols.get("material_weight")
        c_swt = cols.get("svhc_weight")
        c_pct = cols.get("svhc_pct")
        c_use = cols.get("svhc_usage")
        c_dt = cols.get("phase_out_date")

        for r in data_rows:
            sup_val = sh.get_str(r, c_sup) if c_sup else ""
            fox_val = sh.get_str(r, c_fox) if c_fox else ""
            desc_val = sh.get_str(r, c_desc) if c_desc else ""
            svhc_val = sh.get_str(r, c_svhc) if c_svhc else ""
            cas_val = sh.get_str(r, c_cas) if c_cas else ""
            mwt_raw = sh.get_value(r, c_mwt) if c_mwt else None
            mwt_str = sh.get_str(r, c_mwt) if c_mwt else ""
            swt_raw = sh.get_value(r, c_swt) if c_swt else None
            swt_str = sh.get_str(r, c_swt) if c_swt else ""
            pct_raw = sh.get_value(r, c_pct) if c_pct else None
            pct_str = sh.get_str(r, c_pct) if c_pct else ""
            use_val = sh.get_str(r, c_use) if c_use else ""
            dt_raw = sh.get_value(r, c_dt) if c_dt else None
            dt_str = sh.get_str(r, c_dt) if c_dt else ""

            is_new_material = bool(sup_val or fox_val or desc_val)
            if is_new_material:
                cur_sup = sup_val
                cur_fox = fox_val
                cur_desc = desc_val
                cur_mat_wt_mg = None

            # 1. 尋找對應的 O-193D 展開表（依表內正崴料號／檔名正崴料號／供應商料號）與 SAMSUNG FMD
            fn_fox_o202 = extract_foxlink_pn_from_o202_filename(excel_path) or ""
            lookup_fox = cur_fox or fn_fox_o202
            pair_key = (_norm_key(lookup_fox), _norm_key(cur_sup))
            if pair_key not in matched_o193d_cache:
                ref_hit = find_matching_o193d(
                    excel_path, lookup_fox, cur_sup, extra_o193d_paths=extra_o193d_paths
                )
                if ref_hit is None and fn_fox_o202 and _norm_key(fn_fox_o202) != _norm_key(lookup_fox):
                    ref_hit = find_matching_o193d(
                        excel_path, fn_fox_o202, cur_sup, extra_o193d_paths=extra_o193d_paths
                    )
                matched_o193d_cache[pair_key] = ref_hit
            o193d_ref = matched_o193d_cache[pair_key]

            fmd_ref: Optional[FMDRecord] = None
            if fmd_records and cur_sup:
                fmd_ref, _ = match_samsung_fmd(cur_sup, fmd_records)

            # 2. 首次配對到 O-193D 時，核對表頭聯絡資訊並記錄比對來源
            if o193d_ref and not header_checked_against_o193d:
                header_checked_against_o193d = True
                summary.notes.append(
                    f"工作表 [{sh.name}] 已配對 O-193D 比對來源：{o193d_ref.filename}"
                    f"（正崴料號={o193d_ref.canonical_foxlink_pn or '-'}、供應商料號={o193d_ref.supplier_pn or '-'}）。"
                )
                for label_name, lbl_re, ref_val in (
                    ("供應商名稱", re.compile(r"供應商名稱|Supplier\s*Name", re.IGNORECASE), o193d_ref.company_name),
                    ("聯系電話", re.compile(r"聯系電話|聯絡電話|Tel\s*number", re.IGNORECASE), o193d_ref.tel),
                    ("電子郵箱", re.compile(r"電子郵箱|E-?mail", re.IGNORECASE), o193d_ref.email),
                ):
                    if not ref_val:
                        continue
                    hdr_hit = _extract_inline_header_field(sh, header_row, lbl_re)
                    if hdr_hit and _norm_key(hdr_hit[2]) != _norm_key(ref_val):
                        summary.issues.append(
                            CheckIssue(
                                sheet_name=sh.name,
                                cell_address=make_cell_address(hdr_hit[0], hdr_hit[1]),
                                item_name=f"表頭{label_name}",
                                actual_value=hdr_hit[2],
                                expected_value=ref_val,
                                report_no=o193d_ref.filename,
                                evidence=f"與 O-193D ({o193d_ref.filename}) 表頭「{label_name}」不一致",
                                severity="WARNING",
                                fixable=False,
                            )
                        )

            # 3. 核對必填欄位（A/B/C/F）與 O-193D / FMD 一致性
            if is_new_material:
                if c_sup and not sup_val:
                    summary.issues.append(
                        CheckIssue(
                            sheet_name=sh.name,
                            cell_address=make_cell_address(r, c_sup),
                            item_name="供應商料號",
                            actual_value="(空白)",
                            expected_value=o193d_ref.supplier_pn if o193d_ref and o193d_ref.supplier_pn else "必填",
                            report_no=o193d_ref.filename if o193d_ref else "",
                            evidence="依備註 3，供應商料號為必填欄位",
                            severity="ERROR",
                            fixable=bool(o193d_ref and o193d_ref.supplier_pn),
                            fix_value=o193d_ref.supplier_pn if (o193d_ref and o193d_ref.supplier_pn) else None,
                        )
                    )
                elif c_sup and o193d_ref and o193d_ref.supplier_pn and _norm_key(sup_val) != _norm_key(o193d_ref.supplier_pn):
                    summary.issues.append(
                        CheckIssue(
                            sheet_name=sh.name,
                            cell_address=make_cell_address(r, c_sup),
                            item_name="供應商料號",
                            actual_value=sup_val,
                            expected_value=o193d_ref.supplier_pn,
                            report_no=o193d_ref.filename,
                            evidence=f"與 O-193D ({o193d_ref.filename}) 供應商產品料號不一致",
                            severity="ERROR",
                            fixable=True,
                            fix_value=o193d_ref.supplier_pn,
                        )
                    )

                exp_fox = (o193d_ref.canonical_foxlink_pn if o193d_ref else "") or fn_fox_o202
                if c_fox and exp_fox and _norm_key(fox_val) != _norm_key(exp_fox):
                    fox_src = f"O-193D ({o193d_ref.filename})" if (o193d_ref and o193d_ref.canonical_foxlink_pn) else "檔名"
                    summary.issues.append(
                        CheckIssue(
                            sheet_name=sh.name,
                            cell_address=make_cell_address(r, c_fox),
                            item_name="正崴料號",
                            actual_value=fox_val or "(空白)",
                            expected_value=exp_fox,
                            report_no=o193d_ref.filename if o193d_ref else "",
                            evidence=f"與 {fox_src} 正崴料號不一致",
                            severity="ERROR",
                            fixable=True,
                            fix_value=exp_fox,
                        )
                    )

                if c_desc and o193d_ref and o193d_ref.product_name and _norm_key(desc_val) != _norm_key(o193d_ref.product_name):
                    summary.issues.append(
                        CheckIssue(
                            sheet_name=sh.name,
                            cell_address=make_cell_address(r, c_desc),
                            item_name="物料名稱",
                            actual_value=desc_val or "(空白)",
                            expected_value=o193d_ref.product_name,
                            report_no=o193d_ref.filename,
                            evidence=f"與 O-193D ({o193d_ref.filename}) 產品名稱不一致",
                            severity="ERROR",
                            fixable=True,
                            fix_value=o193d_ref.product_name,
                        )
                    )

            # 4. 物料重量（欄 F）比對 O-193D 總重與 FMD 總重
            parsed_mwt = _parse_o202_weight_mg(mwt_raw)
            if parsed_mwt:
                cur_mat_wt_mg = parsed_mwt[0]
            if c_mwt and is_new_material:
                ref_wt_mg = None
                ref_wt_src = ""
                if o193d_ref and o193d_ref.total_weight_mg is not None:
                    ref_wt_mg = o193d_ref.total_weight_mg
                    ref_wt_src = f"O-193D ({o193d_ref.filename})"
                elif fmd_ref and fmd_ref.total_weight_mg > 0:
                    ref_wt_mg = fmd_ref.total_weight_mg
                    ref_wt_src = f"SAMSUNG FMD ({fmd_ref.filename})"

                if not mwt_str:
                    summary.issues.append(
                        CheckIssue(
                            sheet_name=sh.name,
                            cell_address=make_cell_address(r, c_mwt),
                            item_name="物料重量",
                            actual_value="(空白)",
                            expected_value=_format_mg(ref_wt_mg) if ref_wt_mg is not None else "必填",
                            report_no=ref_wt_src,
                            evidence="依備註 3，物料重量為必填欄位",
                            severity="ERROR",
                            fixable=ref_wt_mg is not None,
                            fix_value=_format_mg(ref_wt_mg) if ref_wt_mg is not None else None,
                        )
                    )
                elif ref_wt_mg is not None and parsed_mwt is not None:
                    tol = max(abs(ref_wt_mg) * 0.02, 1e-3)
                    if abs(parsed_mwt[0] - ref_wt_mg) > tol:
                        exp_wt_str = _format_mg(ref_wt_mg)
                        unit_hint = "（注意表頭預設單位為 grams，若為毫克請加註 mg）" if not parsed_mwt[1] else ""
                        summary.issues.append(
                            CheckIssue(
                                sheet_name=sh.name,
                                cell_address=make_cell_address(r, c_mwt),
                                item_name="物料重量",
                                actual_value=mwt_str,
                                expected_value=exp_wt_str,
                                report_no=ref_wt_src,
                                evidence=f"與 {ref_wt_src} 總重量 {exp_wt_str} 不符{unit_hint}",
                                severity="ERROR",
                                fixable=True,
                                fix_value=exp_wt_str,
                            )
                        )

            # 5. 欄 D~J：區分「不含 SVHC」與「含有 SVHC」
            is_not_contained = bool(svhc_val and _NONE_SVHC_RE.match(svhc_val))
            if is_not_contained:
                # 5a. 檢查 O-193D 與 SAMSUNG FMD 的所有 CAS 是否命中 253 項 SVHC 清單（防漏報！）
                if svhc_catalog:
                    product_cas: Set[str] = set()
                    sources: List[str] = []
                    if o193d_ref and o193d_ref.cas_set:
                        product_cas.update(o193d_ref.cas_set)
                        sources.append(f"O-193D ({o193d_ref.filename})")
                    if fmd_ref:
                        fmd_cas = {e.cas_no for e in fmd_ref.entries if e.cas_no}
                        if fmd_cas:
                            product_cas.update(fmd_cas)
                            sources.append(f"SAMSUNG FMD ({fmd_ref.filename})")

                    hits = sorted(cas for cas in product_cas if cas in svhc_catalog)
                    if hits and c_svhc:
                        hit_desc = "、".join(f"{c} ({svhc_catalog[c]})" for c in hits[:5])
                        summary.issues.append(
                            CheckIssue(
                                sheet_name=sh.name,
                                cell_address=make_cell_address(r, c_svhc),
                                item_name="SVHC 漏報檢查",
                                actual_value=svhc_val,
                                expected_value=f"命中 SVHC：{hits[0]}",
                                report_no=" / ".join(sources),
                                evidence=f"來源成分（{'、'.join(sources)}）含有 253 項 SVHC 清單物質：{hit_desc}，不可填寫「{svhc_val}」",
                                severity="ERROR",
                                fixable=False,
                            )
                        )

                # 5b. 依備註 3，不含 SVHC 時欄 E、G、H、I、J 應留空
                for col_idx, col_title, val in (
                    (c_cas, "SVHC登記號", cas_val),
                    (c_swt, "SVHC重量", swt_str),
                    (c_pct, "SVHC占物料比重", pct_str),
                    (c_use, "SVHC使用部位/用途", use_val),
                    (c_dt, "SVHC削減日期", dt_str),
                ):
                    if col_idx and val and not _NONE_SVHC_RE.match(val):
                        summary.issues.append(
                            CheckIssue(
                                sheet_name=sh.name,
                                cell_address=make_cell_address(r, col_idx),
                                item_name=col_title,
                                actual_value=val,
                                expected_value="(留空)",
                                evidence="依備註 3，填寫「不含」SVHC 時此欄位應留空",
                                severity="WARNING",
                                fixable=False,
                            )
                        )
            elif svhc_val:
                # 5c. 含有 SVHC：檢查 CAS、清單歸屬、重量與比重驗算
                found_cas = _CAS_RE.findall(cas_val)
                if c_cas and not found_cas:
                    summary.issues.append(
                        CheckIssue(
                            sheet_name=sh.name,
                            cell_address=make_cell_address(r, c_cas),
                            item_name="SVHC登記號",
                            actual_value=cas_val or "(空白)",
                            expected_value="有效 SVHC CAS No.",
                            evidence=f"已宣告含有 SVHC「{svhc_val}」，須填寫對應 CAS No.",
                            severity="ERROR",
                            fixable=False,
                        )
                    )
                for cas in found_cas:
                    if not is_valid_cas_checksum(cas):
                        summary.issues.append(
                            CheckIssue(
                                sheet_name=sh.name,
                                cell_address=make_cell_address(r, c_cas),
                                item_name="SVHC CAS 格式",
                                actual_value=cas,
                                expected_value="有效 CAS 編號",
                                evidence="CAS 檢查碼驗證失敗，請確認是否輸入錯誤",
                                severity="ERROR",
                                fixable=False,
                            )
                        )
                    elif svhc_catalog and cas not in svhc_catalog:
                        summary.issues.append(
                            CheckIssue(
                                sheet_name=sh.name,
                                cell_address=make_cell_address(r, c_cas),
                                item_name="SVHC 清單核對",
                                actual_value=cas,
                                expected_value="存在於 253 項 SVHC 一覽表",
                                evidence="於本表 253 項 SVHC 一覽表中查無此 CAS No.，請確認是否填錯",
                                severity="WARNING",
                                fixable=False,
                            )
                        )

                parsed_swt = _parse_o202_weight_mg(swt_raw)
                if c_swt and not parsed_swt:
                    summary.issues.append(
                        CheckIssue(
                            sheet_name=sh.name,
                            cell_address=make_cell_address(r, c_swt),
                            item_name="SVHC重量",
                            actual_value=swt_str or "(空白)",
                            expected_value="有效數值與單位",
                            evidence="宣告含有 SVHC 時須填寫 SVHC 重量",
                            severity="ERROR",
                            fixable=False,
                        )
                    )

                if c_pct and parsed_swt and cur_mat_wt_mg and cur_mat_wt_mg > 0:
                    exp_ratio = parsed_swt[0] / cur_mat_wt_mg  # 小數比例（例如 0.15 = 15%）
                    if parsed_swt[0] > cur_mat_wt_mg * 1.001:
                        summary.issues.append(
                            CheckIssue(
                                sheet_name=sh.name,
                                cell_address=make_cell_address(r, c_swt),
                                item_name="SVHC重量驗算",
                                actual_value=swt_str,
                                expected_value=f"≤ {_format_mg(cur_mat_wt_mg)}",
                                evidence="SVHC 重量大於物料總重量（請檢查 mg/g 單位是否誤填）",
                                severity="ERROR",
                                fixable=False,
                            )
                        )
                    else:
                        act_ratio: Optional[float] = None
                        if pct_raw is not None and str(pct_raw).strip() != "":
                            s_p = str(pct_raw).strip()
                            has_pct_sym = ("%" in s_p or "％" in s_p)
                            try:
                                v = float(s_p.replace("%", "").replace("％", "").strip())
                                if has_pct_sym:
                                    act_ratio = v / 100.0
                                elif sh.is_percent_format(r, c_pct):
                                    act_ratio = v
                                else:
                                    # 同時支援小數（0.15）或百分比數值（15）
                                    act_ratio = v if abs(v - exp_ratio) <= abs((v / 100.0) - exp_ratio) else (v / 100.0)
                            except ValueError:
                                act_ratio = None
                        if act_ratio is None or not math.isclose(act_ratio, exp_ratio, rel_tol=0.02, abs_tol=1e-4):
                            summary.issues.append(
                                CheckIssue(
                                    sheet_name=sh.name,
                                    cell_address=make_cell_address(r, c_pct),
                                    item_name="SVHC比重驗算",
                                    actual_value=pct_str or "(空白)",
                                    expected_value=f"{exp_ratio * 100:.4g}% ({exp_ratio:.6g})",
                                    evidence=f"SVHC重量 ({swt_str}) ÷ 物料重量 ({mwt_str or _format_mg(cur_mat_wt_mg)}) 與所填比重不符",
                                    severity="ERROR",
                                    fixable=False,
                                )
                            )
                        if exp_ratio >= 0.001:
                            summary.notes.append(
                                f"工作表 [{sh.name}] 第 {r} 列 SVHC「{svhc_val}」占比 {exp_ratio * 100:.3g}% ≥ 0.1%，須依 REACH 第 33 條履行通報與安全資訊義務。"
                            )

                if c_use and not use_val:
                    summary.issues.append(
                        CheckIssue(
                            sheet_name=sh.name,
                            cell_address=make_cell_address(r, c_use),
                            item_name="SVHC使用部位/用途",
                            actual_value="(空白)",
                            expected_value="必填",
                            evidence="宣告含有 SVHC 時須填寫使用部位或用途",
                            severity="WARNING",
                            fixable=False,
                        )
                    )

                if c_dt and dt_raw is not None and str(dt_raw).strip() != "":
                    if not isinstance(dt_raw, (dt.datetime, dt.date)) and not _parse_excel_date(dt_raw):
                        summary.issues.append(
                            CheckIssue(
                                sheet_name=sh.name,
                                cell_address=make_cell_address(r, c_dt),
                                item_name="SVHC削減日期",
                                actual_value=dt_str,
                                expected_value="有效日期格式",
                                evidence="削減日期無法解析為合法日期",
                                severity="WARNING",
                                fixable=False,
                            )
                        )

    return summary
