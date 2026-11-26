# -*- coding: utf-8 -*-
"""
PDF 報告與 MSDS 解析模組 (PyMuPDF / fitz)
支援：
  1. 第三方測試報告（Intertek、SGS、CTI、TUV、Eurofins、BV 等）：
     - 略過流程圖（Flow Chart）與樣品照片頁
     - 解析報告編號（Report No.）、測試機構（Test Agency）、報告日期（Date）
     - 依表頭欄位（Result / MDL / Limit / Unit）的實際順序定位數值，
       避免 Intertek 的 Result 在 MDL 之前時把 Limit 當成結果
     - 解析各項物質之測試單位、MDL 與測試結果：
       * N.D. / ND -> "<MDL"（預設單位 ppm / mg/kg，不帶單位）
       * 非 ppm 單位（如 IEC 62321-7-1 六價鉻 μg/cm2 + Negative）-> "<MDLμg/cm2"
       * 檢出數值 -> 直接回傳數值（若非 ppm 則保留單位）
     - 無法判定時記錄 note，交由上層顯示，不再靜默略過
  2. MSDS / SDS / 成份表：
     - 提取成分名稱、CAS No. 與含量百分比
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple


# ---------------------------------------------------------------------------
# 資料結構
# ---------------------------------------------------------------------------

@dataclass
class SubstanceResult:
    """單一物質在 PDF 報告中的解析結果。"""
    substance_key: str         # 標準鍵名：Cd, Pb, Hg, Cr6+, PBB, PBDE, DEHP, ...
    expected_value: str        # 依規範格式化後的應填值，如 "<2", "<0.10μg/cm2", "38"
    raw_result: str            # PDF 原始結果字串，如 "N.D.", "Negative", "38"
    mdl: Optional[str] = None  # MDL 原始數字字串，如 "2", "0.10"
    unit: str = "ppm"          # 標準化單位："ppm" 或 "μg/cm2" 等
    page: int = 0              # 1-based 頁碼
    evidence: str = ""         # 來源行摘要


@dataclass
class TestReportData:
    """單份第三方測試報告的解析結果。"""
    filename: str
    source_entry: str
    report_no: str = ""
    agency: str = ""
    report_date: str = ""      # 標準化為 YYYY/MM/DD
    raw_date: str = ""
    results: Dict[str, SubstanceResult] = field(default_factory=dict)
    pages_skipped: List[int] = field(default_factory=list)
    all_report_nos: List[str] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)
    date_is_labeled: bool = False


@dataclass
class MSDSIngredient:
    """MSDS 中的單一化學成分。"""
    cas_no: str
    name: str = ""
    content_pct: str = ""
    page: int = 0


@dataclass
class MSDSData:
    """單份 MSDS / 成份表 PDF 的解析結果。"""
    filename: str
    source_entry: str
    ingredients: List[MSDSIngredient] = field(default_factory=list)
    cas_set: set = field(default_factory=set)


# ---------------------------------------------------------------------------
# 單位與日期正規化
# ---------------------------------------------------------------------------

def normalize_unit(unit_raw: str) -> str:
    """將常見單位統一為 'ppm' 或 'μg/cm2' 等標準表示。"""
    if not unit_raw:
        return "ppm"
    u = unit_raw.strip()
    u_low = u.lower().replace(" ", "").replace("／", "/")
    if re.fullmatch(r"(?:mg|㎎)/(?:kg|㎏)|ppm|mg/kg\(ppm\)|ug/g|μg/g|µg/g", u_low):
        return "ppm"
    if (
        re.fullmatch(r"(?:[uμµ]g|㎍)/(?:cm[2²]|cm\^2)", u_low)
        or "ug/cm" in u_low
        or "μg/cm" in u_low
        or "µg/cm" in u_low
        or "㎍/cm" in u_low
        or "㎍g/cm" in u_low
    ):
        return "μg/cm2"
    if u_low in ("%", "wt%", "w/w%"):
        return "%"
    if u_low in ("mg/l", "ug/l", "μg/l", "ng/cm2", "ug/m2", "μg/m2"):
        return u.replace("u", "μ").replace("µ", "μ").replace("²", "2")
    return u


_ND_PATTERN = re.compile(
    r"^(?:N\.?\s*D\.?|NOT\s*DETECTED|NEGATIVE|NEG\.?|ABSENT|BLQ|BDL|未檢出|未检出|陰性|阴性)$",
    re.IGNORECASE,
)
_SIGNED_NUM_PATTERN = re.compile(r"^([<≤>≥~])\s*(-?\d+(?:\.\d+)?)\s*(.*)$")
_NUM_PATTERN = re.compile(
    r"^(-?(?:\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?))\s*(.*)$"
)
_INLINE_UNIT_PATTERN = re.compile(
    r"(mg/kg|㎎/㎏|ppm|(?:[uμµ]g|㎍)/cm[2²]|%|wt%)\s*$", re.IGNORECASE
)


def _unit_suffix(unit: str, inline_unit: str = "") -> str:
    u = normalize_unit(inline_unit) if inline_unit else normalize_unit(unit)
    return "" if u == "ppm" else u


def _format_expected_value(raw_result: str, mdl: Optional[str], unit: str) -> Optional[str]:
    """
    依規範將 PDF 測試結果轉為 O-193D 應填寫值：
      - N.D. / ND / Not Detected / Negative -> <MDL（ppm 不帶單位；非 ppm 帶單位如 <0.10μg/cm2）
      - <X / >X / ≥X / ≤X / ~X -> 保留符號（ppm 不帶單位；非 ppm 帶單位）
      - 數字（含千分位、負數）-> 該數字（ppm 不帶單位；非 ppm 帶單位）
    """
    res = (raw_result or "").strip()
    if not res:
        return None

    if _ND_PATTERN.match(res):
        if not mdl:
            return None
        mdl_clean = re.sub(r"^[<≤>≥~\s]+", "", str(mdl)).strip()
        if not re.fullmatch(r"-?\d+(?:\.\d+)?", mdl_clean):
            return None
        return f"<{mdl_clean}{_unit_suffix(unit)}"

    m = _SIGNED_NUM_PATTERN.match(res)
    if m:
        symbol, val, inline_u = m.group(1), m.group(2), m.group(3).strip()
        return f"{symbol}{val}{_unit_suffix(unit, inline_u)}"

    m = _NUM_PATTERN.match(res)
    if m:
        val = m.group(1).replace(",", "")
        inline_u = m.group(2).strip()
        return f"{val}{_unit_suffix(unit, inline_u)}"

    return None


_MONTH_MAP = {
    "jan": 1, "january": 1,
    "feb": 2, "february": 2,
    "mar": 3, "march": 3,
    "apr": 4, "april": 4,
    "may": 5,
    "jun": 6, "june": 6,
    "jul": 7, "july": 7,
    "aug": 8, "august": 8,
    "sep": 9, "sept": 9, "september": 9,
    "oct": 10, "october": 10,
    "nov": 11, "november": 11,
    "dec": 12, "december": 12,
}


def parse_date_string_ex(text: str) -> Tuple[Optional[str], bool]:
    """
    將多種常見日期格式正規化為 YYYY/MM/DD。
    回傳 (日期, 是否歧義)；歧義日期不應作為自動修正依據。
    """
    if not text:
        return None, False
    s = text.strip()

    # YYYY-MM-DD / YYYY/MM/DD / YYYY.MM.DD / YYYY年MM月DD日
    m = re.search(r"(\d{4})\s*[-/\.年]\s*(\d{1,2})\s*[-/\.月]\s*(\d{1,2})", s)
    if m:
        y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
        if 1990 <= y <= 2100 and 1 <= mo <= 12 and 1 <= d <= 31:
            return f"{y:04d}/{mo:02d}/{d:02d}", False

    # Mon DD, YYYY  (e.g., Aug 15, 2024 / Aug. 15, 2024)
    m = re.search(r"([A-Za-z]{3,9})\.?\s+(\d{1,2}),?\s+(\d{4})", s)
    if m:
        mo = _MONTH_MAP.get(m.group(1).lower())
        if mo:
            d, y = int(m.group(2)), int(m.group(3))
            if 1990 <= y <= 2100 and 1 <= d <= 31:
                return f"{y:04d}/{mo:02d}/{d:02d}", False

    # DD Mon YYYY (e.g., 15 Aug 2024 / 15-Aug-2024)
    m = re.search(r"(\d{1,2})[\s\-]([A-Za-z]{3,9})\.?[\s\-,]+(\d{4})", s)
    if m:
        mo = _MONTH_MAP.get(m.group(2).lower())
        if mo:
            d, y = int(m.group(1)), int(m.group(3))
            if 1990 <= y <= 2100 and 1 <= d <= 31:
                return f"{y:04d}/{mo:02d}/{d:02d}", False

    # DD/MM/YYYY 或 MM/DD/YYYY（無法判定時標記為歧義）
    m = re.search(r"\b(\d{1,2})/(\d{1,2})/(\d{4})\b", s)
    if m:
        a, b, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
        if 1990 <= y <= 2100:
            if a > 12 and b <= 12:
                return f"{y:04d}/{b:02d}/{a:02d}", False
            if b > 12 and a <= 12:
                return f"{y:04d}/{a:02d}/{b:02d}", False
            return f"{y:04d}/{a:02d}/{b:02d}", True

    return None, False


def parse_date_string(text: str) -> Optional[str]:
    """將多種常見日期格式正規化為 YYYY/MM/DD（相容舊介面）。"""
    return parse_date_string_ex(text)[0]


# ---------------------------------------------------------------------------
# 頁面過濾（略過流程圖與樣品照片頁）
# ---------------------------------------------------------------------------

_SKIP_PAGE_PATTERNS = [
    r"\bTEST\s+FLOW\s*CHART\b",
    r"\bFLOW\s*CHART\b",
    r"測試流程圖|测试流程图|檢測流程圖|检测流程图",
    r"\bSAMPLE\s+PHOTO(?:GRAPH)?S?\b",
    r"\bPHOTO(?:GRAPH)?\s+OF\s+(?:THE\s+)?SAMPLE",
    r"樣品照片|样品照片|測試樣品照片|测试样品照片",
    r"\bAPPENDIX\s*:\s*PHOTO",
    r"End\s+of\s+Report.*Photo",
]


def _should_skip_page(page_text: str) -> bool:
    """判斷該頁是否為流程圖（Flow Chart）或樣品照片頁。"""
    if not page_text or len(page_text.strip()) < 20:
        return True
    # 若頁面同時有明確的測試結果表格關鍵字（如 MDL + Test Result），則不略過
    has_result_table = bool(
        re.search(r"\bMDL\b|\bRL\b|Detection\s+Limit|檢出限|检出限", page_text, re.IGNORECASE)
        and re.search(r"\bN\.?D\.?\b|Negative|mg/kg|ppm|μg/cm|ug/cm", page_text, re.IGNORECASE)
    )
    if has_result_table:
        return False

    for pat in _SKIP_PAGE_PATTERNS:
        if re.search(pat, page_text, re.IGNORECASE):
            return True
    return False


# ---------------------------------------------------------------------------
# 報告基本資訊（編號、機構、日期）提取
# ---------------------------------------------------------------------------

# 機構 canonical 名稱、Excel 比對別名、PDF 偵測正則集中一份，避免兩邊漂移
AGENCY_TABLE: List[Tuple[str, Tuple[str, ...], str]] = [
    ("Intertek", ("intertek", "its", "天祥"), r"\bINTERTEK\b|天祥"),
    ("SGS", ("sgs", "sgs-cstc", "通標", "通标", "通標標準", "通标标准"), r"\bSGS\b|通標標準|通标标准|SGS-CSTC|SGS\s+Taiwan"),
    ("CTI", ("cti", "centre testing", "華測", "华测", "華測檢測", "华测检测"), r"\bCTI\b|CENTRE\s+TESTING\s+INTERNATIONAL|华测检测|華測檢測"),
    ("TUV", ("tuv", "tüv", "rheinland", "tuv sud", "萊因", "莱茵", "南德"), r"\bT[UÜ]V\b|RHEINLAND|T[UÜ]V\s+S[UÜ]D|萊因|莱茵|南德"),
    ("Eurofins", ("eurofins",), r"\bEUROFINS\b"),
    ("BV", ("bv", "bureau veritas", "bv cps"), r"\bBUREAU\s+VERITAS\b|\bBV\s+CPS\b"),
    ("PONY", ("pony", "pony testing", "譜尼", "谱尼", "譜尼測試", "谱尼测试"), r"\bPONY\s+TESTING\b|譜尼測試|谱尼测试"),
    ("UL", ("ul", "underwriters", "underwriters laboratories", "ul vs"), r"\bUL\s+VS\b|\bUNDERWRITERS\s+LABORATORIES\b"),
]

AGENCY_ALIAS_GROUPS: List[Tuple[str, Tuple[str, ...]]] = [
    (canon, aliases) for canon, aliases, _ in AGENCY_TABLE
]
_AGENCY_RULES: List[Tuple[str, str]] = [
    (canon, pattern) for canon, _, pattern in AGENCY_TABLE
]


def _detect_agency(text: str, filename: str = "") -> str:
    combined = f"{text[:4000]}\n{filename}"
    for canonical, pat in _AGENCY_RULES:
        if re.search(pat, combined, re.IGNORECASE):
            return canonical
    return ""


_REPORT_NO_PATTERNS = [
    # 明確標籤：Report No. / Test Report No. / 報告編號 / 报告编号 / 报告号(REPORT NO.)
    r"(?:Test\s+)?Report\s*(?:No\.?|Number)\s*[\)）]?\s*[:：#]?\s*([A-Z0-9][A-Z0-9\-_/\.]{5,32})",
    r"(?:報告號|报告号)\s*(?:\([^)]*\))?\s*[:：#]?\s*([A-Z0-9][A-Z0-9\-_/\.]{5,32})",
    r"(?:測試|檢測|测试|检测)?(?:報告|报告)(?:編號|编号|號碼|号码)\s*[:：#]?\s*([A-Z0-9][A-Z0-9\-_/\.]{5,32})",
    r"\bNo\.?\s*[:：]\s*([A-Z]{2,6}\d{6,}[A-Z0-9\-]*)",
    # 常見實驗室編號特徵
    r"\b(CANEC\d{8,}[A-Z0-9\-]*)\b",     # SGS 廣州
    r"\b(SHAEC\d{8,}[A-Z0-9\-]*)\b",     # SGS 上海
    r"\b(SZXEC\d{8,}[A-Z0-9\-]*)\b",     # SGS 深圳
    r"\b(XMNEC\d{8,}[A-Z0-9\-]*)\b",     # SGS 廈門
    r"\b(TAOEC\d{8,}[A-Z0-9\-]*)\b",     # SGS 青島
    r"\b(TJNEC\d{8,}[A-Z0-9\-]*)\b",     # SGS 天津
    r"\b(CE/\d{4}/[A-Z0-9]+)\b",         # SGS 台灣
    r"\b(KA/\d{4}/[A-Z0-9]+)\b",         # SGS 高雄
    r"\b(SZHH\d{8,}[A-Z0-9\-]*)\b",      # Intertek 深圳
    r"\b(GZHH\d{8,}[A-Z0-9\-]*)\b",      # Intertek 廣州
    r"\b(SHAH\d{8,}[A-Z0-9\-]*)\b",      # Intertek 上海
    r"\b(TWNC\d{8,}[A-Z0-9\-]*)\b",      # Intertek 台灣
    r"\b(A\d{10,}[A-Z0-9]*)\b",          # CTI 華測常見格式
    r"\b(EDD\d{2}[A-Z]\d{6,})\b",        # CTI
]


def _extract_report_nos(text: str, filename: str = "") -> List[str]:
    found: List[str] = []
    seen = set()
    head = text[:5000]
    for pat in _REPORT_NO_PATTERNS:
        for m in re.finditer(pat, head, re.IGNORECASE):
            cand = m.group(1).strip().rstrip(".,;:")
            # 排除純日期或過短雜訊
            if len(cand) < 6 or re.match(r"^\d{4}[-/\.]\d{2}[-/\.]\d{2}$", cand):
                continue
            key = cand.upper()
            if key not in seen:
                seen.add(key)
                found.append(cand)
    # 檔名中也常含報告編號
    fname_stem = re.sub(r"\.pdf$", "", filename, flags=re.IGNORECASE)
    for m in re.finditer(r"([A-Z]{2,6}[\-_]?\d{6,}[A-Z0-9\-]*)", fname_stem, re.IGNORECASE):
        cand = m.group(1).strip()
        if cand.upper() not in seen:
            seen.add(cand.upper())
            found.append(cand)
    return found


_DATE_LABEL_PATTERNS = [
    # 只接受明確的報告／測試日期欄位；不可用裸 Date/日期，否則會命中「收樣日期」
    r"(?<![A-Za-z])(?:Report\s+Date|Date\s+of\s+Issue|Date\s+of\s+Testing|Issue\s+Date|Issued\s+Date|Test\s+Date|"
    r"報告日期|报告日期|測試日期|测试日期|簽發日期|签发日期)\s*[:：]\s*([^\n]{6,32})",
]


def _extract_report_date(text: str) -> Tuple[str, str, bool]:
    """
    回傳 (標準化日期, 原始字串, 是否來自具名欄位)。
    非具名 fallback 僅供顯示，不應自動寫入 Excel。
    """
    head = text[:3500]
    for pat in _DATE_LABEL_PATTERNS:
        for m in re.finditer(pat, head, re.IGNORECASE):
            raw = m.group(1).strip()
            parsed, ambiguous = parse_date_string_ex(raw)
            if parsed:
                return parsed, raw, not ambiguous
    # Fallback: 頁首前 1500 字元內的第一個有效日期（可能是收樣日，不可自動修正）
    parsed, _ = parse_date_string_ex(head[:1500])
    if parsed:
        return parsed, parsed, False
    return "", "", False


# ---------------------------------------------------------------------------
# 物質名稱對應規則（支援 18+ 常見 O-193D 管控物質）
# ---------------------------------------------------------------------------

# (標準鍵名, 正則列表) — 順序重要（較精確者在前）
SUBSTANCE_PATTERNS: List[Tuple[str, List[str]]] = [
    ("Cd", [
        r"\bCadmium\s*\(Cd\)",
        r"\bCadmium\b",
        r"鎘\s*\(?Cd\)?|镉\s*\(?Cd\)?",
        r"(?:^|\s)Cd(?:\s|$|\()",
    ]),
    ("Pb", [
        r"\bLead\s*\(Pb\)",
        r"\bLead\b",
        r"鉛\s*\(?Pb\)?|铅\s*\(?Pb\)?",
        r"(?:^|\s)Pb(?:\s|$|\()",
    ]),
    ("Hg", [
        r"\bMercury\s*\(Hg\)",
        r"\bMercury\b",
        r"汞\s*\(?Hg\)?",
        r"(?:^|\s)Hg(?:\s|$|\()",
    ]),
    ("Cr6+", [
        r"\bHexavalent\s+Chromium\b",
        r"\bChromium\s*\(?\s*VI\s*\)?",
        r"\bCr\s*\(?\s*(?:VI|6\+?)\s*\)?",
        r"六價鉻|六价铬",
    ]),
    ("PBB", [
        r"\bSum\s+of\s+PBBs?\b",
        r"\bPolybrominated\s+Biphenyls?\b",
        r"\bPBBs?\b",
        r"多溴聯苯|多溴联苯",
    ]),
    ("PBDE", [
        r"\bSum\s+of\s+PBDEs?\b",
        r"\bPolybrominated\s+Diphenyl\s+Ethers?\b",
        r"\bPBDEs?\b",
        r"多溴二苯醚",
    ]),
    ("DEHP", [
        r"\bDEHP\b",
        r"Di-?\(?2-?ethylhexyl\)?\s*phthalate",
        r"Bis\s*\(2-?ethylhexyl\)\s*phthalate",
        r"\b117-81-7\b",
    ]),
    ("BBP", [
        r"\bBBP\b",
        r"Butyl\s+benzyl\s+phthalate",
        r"Benzyl\s+butyl\s+phthalate",
        r"\b85-68-7\b",
    ]),
    ("DBP", [
        r"\bDBP\b",
        r"Dibutyl\s+phthalate",
        r"\b84-74-2\b",
    ]),
    ("DIBP", [
        r"\bDIBP\b",
        r"Diisobutyl\s+phthalate",
        r"Di-?isobutyl\s+phthalate",
        r"\b84-69-5\b",
    ]),
    ("Cl", [
        r"\bChlorine\s*\(Cl\)",
        r"\bChlorine\b",
        r"氯\s*\(?Cl\)?",
        r"(?:^|\s)Cl(?:\s|$|\()",
    ]),
    ("Br", [
        r"\bBromine\s*\(Br\)",
        r"\bBromine\b",
        r"溴\s*\(?Br\)?",
        r"(?:^|\s)Br(?:\s|$|\()",
    ]),
    ("F", [
        r"\bFluorine\s*\(F\)",
        r"\bFluorine\b",
        r"氟\s*\(?F\)?",
        r"(?:^|\s)F(?:\s|$|\()",
    ]),
    ("PFOA", [
        r"\bPFOA\b",
        r"Perfluorooctanoic\s+acid",
        r"全氟辛酸",
        r"\b335-67-1\b",
    ]),
    ("PFOS", [
        r"\bPFOS\b",
        r"Perfluorooctane\s+sulfonat",
        r"全氟辛烷磺酸|全氟辛烷磺酰",
        r"\b1763-23-1\b",
    ]),
    ("Be", [
        r"\bBeryllium\s*\(Be\)",
        r"\bBeryllium\b",
        r"鈹\s*\(?Be\)?|铍\s*\(?Be\)?",
        r"(?:^|\s)Be(?:\s|$|\()",
    ]),
    ("As", [
        r"\bArsenic\s*\(As\)",
        r"\bArsenic\b",
        r"砷\s*\(?As\)?",
        r"(?:^|\s)As(?:\s|$|\()",
    ]),
    ("Sb", [
        r"\bAntimony\s*\(Sb\)",
        r"\bAntimony\b",
        r"銻\s*\(?Sb\)?|锑\s*\(?Sb\)?",
        r"(?:^|\s)Sb(?:\s|$|\()",
    ]),
]


SUBSTANCE_KEYS: List[str] = [key for key, _ in SUBSTANCE_PATTERNS]


def _match_substance(line: str) -> Optional[str]:
    """判斷一行文字對應的管控物質標準鍵名。"""
    # 排除單體 PBB / PBDE 子項（如 Monobromobiphenyl），只抓總和或主項
    if re.search(r"\b(Mono|Di|Tri|Tetra|Penta|Hexa|Hepta|Octa|Nona|Deca)bromo", line, re.IGNORECASE):
        return None
    for key, pats in SUBSTANCE_PATTERNS:
        for pat in pats:
            if re.search(pat, line, re.IGNORECASE):
                return key
    return None


# 支援 mg/kg、㎎/㎏（全形方塊字）、μg/cm2、㎍/cm2 等實際報告常見寫法
_UNIT_ATOM = (
    r"(?:mg|㎎)\s*/\s*(?:kg|㎏)|ppm|(?:[uμµ]g|㎍)\s*/\s*cm[2²]|%|wt%|mg/l|[uμµ]g/l"
)
_UNIT_TOKEN_RE = re.compile(rf"(?:{_UNIT_ATOM})", re.IGNORECASE)
_UNIT_FULL_RE = re.compile(rf"^(?:{_UNIT_ATOM})$", re.IGNORECASE)

_RESULT_TOKEN_RE = re.compile(
    r"(N\.?D\.?|Not\s*Detected|Negative|Neg\.?|Absent|"
    r"[<≤>≥~]\s*-?\d+(?:\.\d+)?(?:\s*[uμµ㎍]g/cm[2²])?|"
    r"-?\d{1,3}(?:,\d{3})+(?:\.\d+)?|"
    r"-?\d+(?:\.\d+)?)",
    re.IGNORECASE,
)

# 排除明顯是 RoHS 限值欄位的值（例如 1000, 100 在 Limit 欄）
_HEADER_LINE_RE = re.compile(
    r"(Test\s+Item|Parameter|Substance|Limit|Unit|MDL|Result|測試項目|测试项目|限值|單位|单位|檢出限|检出限|結果|结果)",
    re.IGNORECASE,
)


def _detect_page_default_unit(page_text: str) -> str:
    """偵測頁面表格的預設單位（多數為 mg/kg / ppm，Cr6+ 金屬鍍層頁面可能為 μg/cm2）。"""
    if re.search(r"IEC\s*62321-7-1", page_text, re.IGNORECASE) and re.search(
        r"(?:[uμµ]g|㎍)\s*/\s*cm[2²]", page_text, re.IGNORECASE
    ):
        return "μg/cm2"
    m = re.search(rf"Unit\s*[:：]?\s*({_UNIT_ATOM})", page_text, re.IGNORECASE)
    if m:
        return normalize_unit(m.group(1))
    return "ppm"


# ---------------------------------------------------------------------------
# 依實際欄位位置解析表格（解決 Result/MDL/Limit 欄序不同）
# ---------------------------------------------------------------------------

RowToken = Tuple[float, float, str]  # (x0, x1, text)


@dataclass
class ResultColumns:
    """單頁表格中 Result / MDL / Limit / Unit 欄位的 x 座標。"""
    anchors: List[Tuple[float, str]]   # (中心 x, 欄位類型)
    value_left: float                  # 數值欄最左側邊界


def _extract_rows_with_y(page) -> List[Tuple[float, List[RowToken]]]:
    """
    依 Y 座標群組文字，保留列基準 y 與每個 token 的 x 範圍。
    """
    words = page.get_text("words")  # (x0, y0, x1, y1, word, block_no, line_no, word_no)
    if not words:
        return [
            (float(idx), [(0.0, 0.0, line)])
            for idx, line in enumerate((page.get_text("text") or "").splitlines())
            if line.strip()
        ]

    words.sort(key=lambda w: (w[1], w[0]))
    rows: List[Tuple[float, List[RowToken]]] = []
    current_y: Optional[float] = None
    current_row: List[RowToken] = []

    for w in words:
        x0, y0, x1, _, text = w[0], w[1], w[2], w[3], w[4]
        if current_y is None or abs(y0 - current_y) <= 3.5:
            current_row.append((x0, x1, text))
            if current_y is None:
                current_y = y0
        else:
            rows.append((current_y, current_row))
            current_row = [(x0, x1, text)]
            current_y = y0

    if current_row:
        rows.append((current_y or 0.0, current_row))

    for _, row in rows:
        row.sort(key=lambda item: item[0])
    return rows


def _extract_rows_with_pos(page) -> List[List[RowToken]]:
    """相容舊介面：只回傳列內 token。"""
    return [row for _, row in _extract_rows_with_y(page)]


def _extract_lines_with_layout(page) -> List[str]:
    """將頁面文字依 Y 座標還原為同一列（相容舊介面，MSDS 使用）。"""
    return [" ".join(tok for _, _, tok in row) for row in _extract_rows_with_pos(page)]


def _norm_header_token(text: str) -> str:
    t = re.sub(r"[\(（\[].*?[\)）\]]", " ", text)
    t = re.sub(r"[：:]\s*$", "", t)
    return re.sub(r"\s+", " ", t).strip().lower()


def _classify_header_token(token: str) -> Optional[str]:
    t = _norm_header_token(token)
    if re.fullmatch(r"(?:test\s*)?results?|測試結果|测试结果|結果|结果", t):
        return "result"
    if re.fullmatch(
        r"mdl|rl|dl|lod|loq|method\s+detection\s+limit|detection\s+limit|reporting\s+limit|"
        r"檢出限|检出限|方法檢出限|方法检出限|檢出極限|檢出下限|偵測極限",
        t,
    ):
        return "mdl"
    if re.fullmatch(r"limits?|threshold|限值|管制值|管制標準|管控標準|標準值|标准值", t):
        return "limit"
    if re.fullmatch(r"units?|單位|单位", t):
        return "unit"
    if re.fullmatch(
        r"(?:test\s*)?items?|items?|parameter|substance|element|elements|"
        r"測試項目|测试项目|測試物質|测试物质|項目|项目|物質|物质|元素",
        t,
    ):
        return "item"
    return None


def _detect_result_columns(rows: List[List[RowToken]]) -> Optional[ResultColumns]:
    """在頁面中尋找含 Result 與 MDL/Limit 的表頭列，回傳各欄位 x 座標。"""
    for row in rows:
        found: Dict[str, Tuple[float, float]] = {}  # kind -> (center, x0)
        prev_norm = ""
        for x0, x1, text in row:
            kind = _classify_header_token(text)
            norm = _norm_header_token(text)
            # "Detection Limit" 會被拆成 Detection + Limit，第二個視為 MDL
            if kind == "limit" and re.fullmatch(r"detection|method", prev_norm):
                kind = "mdl"
            if kind and kind not in found:
                found[kind] = ((x0 + x1) / 2.0, x0)
            prev_norm = norm

        if "result" in found and ("mdl" in found or "limit" in found):
            anchors = [((c), k) for k, (c, _) in found.items()]
            value_left = min(x0 for k, (_, x0) in found.items() if k in ("mdl", "limit", "result", "unit"))
            anchors.sort()
            return ResultColumns(anchors=anchors, value_left=value_left)
    return None


def _classify_table_label(text: str) -> Optional[str]:
    """辨識跨列式表頭標籤（不要求同一列）。"""
    t = (text or "").strip().lower()
    if not t:
        return None
    if re.search(r"test\s*result|results?|测试结果|測試結果|结果|結果", t):
        return "result"
    if re.search(
        r"mdl|报告限量值|報告限量值|限量值|检出限|檢出限|method\s*detection\s*limit|reporting\s*limit",
        t,
    ):
        return "mdl"
    if re.search(r"limits?|限值|管制值|管制標準|管控標準|标准值|標準值", t):
        return "limit"
    if re.search(r"units?|单位|單位", t):
        return "unit"
    if re.search(r"test\s*method|method|测试方法|測試方法", t):
        return "method"
    if re.search(
        r"test\s*item|items?|测试项目|測試項目|测试物质|測試物質|substance|项目|項目", t
    ):
        return "item"
    return None


def _detect_table_layout(
    rows_y: List[Tuple[float, List[RowToken]]]
) -> Optional[Tuple[Dict[str, float], float, float, float]]:
    """
    找出跨列式表頭（例如 Intertek 的「报告限量值」與「(MDL)」分成兩列）
    以及資料列範圍。回傳 (anchors, first_sub_y, stop_y, item_right)。
    """
    first_sub_y: Optional[float] = None
    for y, row in rows_y:
        text = " ".join(tok for _, _, tok in row)
        if _match_substance(text):
            first_sub_y = y
            break
    if first_sub_y is None:
        return None

    anchors: Dict[str, float] = {}
    for y, row in rows_y:
        if y > first_sub_y - 1:
            break
        for x0, x1, text in row:
            kind = _classify_table_label(text)
            if kind and kind not in anchors:
                anchors[kind] = (x0 + x1) / 2.0

    if "result" not in anchors or ("mdl" not in anchors and "limit" not in anchors):
        return None

    value_anchors = [anchors[k] for k in ("unit", "mdl", "limit", "result") if k in anchors]
    first_value_x = min(value_anchors)
    item_center = anchors.get("item")
    if item_center is not None:
        # 以「項目欄中心」與「第一個數值欄」的中點作為邊界
        item_right = (item_center + first_value_x) / 2.0
    else:
        item_right = first_value_x - 100

    stop_y: Optional[float] = None
    for y, row in rows_y:
        if y <= first_sub_y:
            continue
        text = " ".join(tok for _, _, tok in row)
        if re.search(
            r"Tested\s+by|注释|註釋|Notes|備註|备注|※|Remarks|End\s+of\s+Report",
            text,
            re.IGNORECASE,
        ):
            stop_y = y
            break
    if stop_y is None:
        stop_y = (rows_y[-1][0] + 20.0) if rows_y else first_sub_y + 1000.0

    return anchors, first_sub_y, stop_y, item_right


def _parse_page_by_bands(
    rows_y: List[Tuple[float, List[RowToken]]],
    page_num: int,
    page_unit: str,
    is_iec_7_1_ctx: bool,
    notes: List[str],
) -> Dict[str, SubstanceResult]:
    """
    依「物質名稱 → 下一個物質名稱」切出垂直區塊，再把 MDL/Result/Unit
    依欄位 x 座標分配到對應區塊。可處理名稱、方法、MDL、結果分佔多列的表。
    """
    layout = _detect_table_layout(rows_y)
    if not layout:
        return {}
    anchors, first_sub_y, stop_y, item_right = layout
    anchor_items = list(anchors.items())

    starts: List[Tuple[float, str]] = []
    for y, row in rows_y:
        if y < first_sub_y - 1 or y >= stop_y:
            continue
        item_text = " ".join(
            tok for x0, x1, tok in row if (x0 + x1) / 2.0 < item_right
        )
        if not item_text:
            continue
        key = _match_substance(item_text)
        if not key:
            continue
        if not starts or key != starts[-1][1] or y - starts[-1][0] > 20:
            starts.append((y, key))

    if not starts:
        return {}

    results: Dict[str, SubstanceResult] = {}
    for idx, (start_y, key) in enumerate(starts):
        end_y = starts[idx + 1][0] if idx + 1 < len(starts) else stop_y
        result_tokens: List[Tuple[float, float, str]] = []
        mdl_tokens: List[Tuple[float, float, str]] = []
        limit_tokens: List[Tuple[float, float, str]] = []
        unit_tokens: List[Tuple[float, float, str]] = []

        for y, row in rows_y:
            if y < start_y - 1 or y >= end_y:
                continue
            for x0, x1, text in _merge_row_tokens(row):
                center = (x0 + x1) / 2.0
                if center < item_right:
                    continue
                kind = min(anchor_items, key=lambda item: abs(item[1] - center))[0]
                value = text.strip()
                if kind == "result":
                    if value:
                        result_tokens.append((y, center, value))
                elif kind == "mdl":
                    if re.search(r"-?\d+", value):
                        mdl_tokens.append((y, center, value))
                elif kind == "limit":
                    if re.search(r"-?\d+", value):
                        limit_tokens.append((y, center, value))
                elif kind == "unit" and _UNIT_FULL_RE.match(value):
                    unit_tokens.append((y, center, value))

        if not result_tokens:
            continue
        result_tokens.sort()
        mdl_tokens.sort()
        limit_tokens.sort()
        unit_tokens.sort()

        raw_res = result_tokens[0][2]
        mdl: Optional[str] = None
        for _, _, value in (mdl_tokens or limit_tokens):
            m = re.search(r"-?\d+(?:\.\d+)?", value)
            if m:
                mdl = m.group(0)
                break

        unit: Optional[str] = None
        inline = _INLINE_UNIT_PATTERN.search(raw_res)
        if inline:
            unit = normalize_unit(inline.group(1))
        if unit is None and unit_tokens:
            unit = normalize_unit(unit_tokens[0][2])
        if unit is None:
            unit = "μg/cm2" if (key == "Cr6+" and is_iec_7_1_ctx) else page_unit

        if _ND_PATTERN.match(raw_res) and not mdl:
            _note(notes, f"P.{page_num} {key}：結果為 N.D. 但無法判定 MDL，已略過此項")
            continue

        expected = _format_expected_value(raw_res, mdl, unit)
        if not expected:
            continue

        results[key] = SubstanceResult(
            substance_key=key,
            expected_value=expected,
            raw_result=raw_res,
            mdl=mdl,
            unit=unit,
            page=page_num,
            evidence=f"{key} 結果={raw_res} MDL={mdl or '-'} 單位={unit}",
        )

    return results


def _merge_row_tokens(tokens: List[RowToken]) -> List[RowToken]:
    """合併被空白拆開的 N.D.、Not Detected、比較符號與數值。"""
    out: List[RowToken] = []
    i = 0
    while i < len(tokens):
        x0, x1, text = tokens[i]
        cur = text.strip()
        nxt = tokens[i + 1][2].strip() if i + 1 < len(tokens) else ""

        if re.fullmatch(r"N\.?", cur, re.IGNORECASE) and re.fullmatch(r"D\.?", nxt, re.IGNORECASE):
            out.append((x0, tokens[i + 1][1], "N.D."))
            i += 2
            continue
        if cur.lower() == "not" and nxt.lower() == "detected":
            out.append((x0, tokens[i + 1][1], "Not Detected"))
            i += 2
            continue
        if cur in ("<", "≤", ">", "≥", "~", "<=", ">=") and re.fullmatch(r"-?\d+(?:\.\d+)?", nxt):
            out.append((x0, tokens[i + 1][1], f"{cur}{nxt}"))
            i += 2
            continue

        out.append((x0, x1, cur))
        i += 1
    return out


def _classify_value_token(text: str) -> Optional[str]:
    """判斷 token 是數值、N.D. 還是比較式；單位另由 _UNIT_FULL_RE 判斷。"""
    t = text.strip()
    if not t:
        return None
    if _ND_PATTERN.match(t):
        return "nd"
    if re.fullmatch(r"[<≤>≥~]\s*-?\d+(?:\.\d+)?(?:[uμµ㎍]g/cm[2²])?", t, re.IGNORECASE):
        return "signed"
    if re.fullmatch(r"-?(?:\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?)", t):
        return "number"
    return None


def _first_numeric(values: List[Tuple[float, str]]) -> Optional[str]:
    for _, txt in values:
        m = re.search(r"-?\d+(?:\.\d+)?", txt)
        if m:
            return m.group(0)
    return None


def _is_common_limit(value: float) -> bool:
    return abs(value - 1000.0) < 1e-9 or abs(value - 100.0) < 1e-9


def _note(notes: List[str], text: str) -> None:
    if text not in notes:
        notes.append(text)


def _parse_with_columns(
    sub_key: str,
    line: str,
    tokens: List[RowToken],
    columns: ResultColumns,
    page_unit: str,
    page_num: int,
    is_iec_7_1_ctx: bool,
    notes: List[str],
) -> Tuple[Optional[SubstanceResult], bool]:
    """
    依表頭 x 座標把數值分配到 Result / MDL / Limit 欄位。
    回傳 (結果, 是否已找到 Result 欄位)；找到 Result 但無法解析時不應退回猜測。
    """
    assigned: Dict[str, List[Tuple[float, str]]] = {"result": [], "mdl": [], "limit": [], "unit": []}
    for x0, x1, text in tokens:
        center = (x0 + x1) / 2.0
        if center < columns.value_left - 5:
            continue
        if _UNIT_FULL_RE.match(text.strip()):
            assigned["unit"].append((center, text.strip()))
            continue
        kind = _classify_value_token(text)
        nearest = min(columns.anchors, key=lambda a: abs(a[0] - center))
        if kind is None:
            # 文字型結果（Pass / Fail / N/A）也要歸到 Result 欄，避免退回數字猜測
            if (
                nearest[1] == "result"
                and _classify_header_token(text) is None
                and re.search(r"[A-Za-z\u4e00-\u9fff]", text)
            ):
                assigned["result"].append((center, text.strip()))
            continue
        if nearest[1] in ("result", "mdl", "limit"):
            assigned[nearest[1]].append((center, text.strip()))

    if not assigned["result"]:
        # 已偵測到 Result 欄但該列為空（或只有 Limit/MDL）：
        # 不可退回數字猜測，否則會把 Limit 當成結果並被 --fix 寫入。
        return None, True

    raw_res = assigned["result"][0][1]
    mdl = _first_numeric(assigned["mdl"])

    unit: Optional[str] = None
    inline = _INLINE_UNIT_PATTERN.search(raw_res)
    if inline:
        unit = normalize_unit(inline.group(1))
    if unit is None and assigned["unit"]:
        unit = normalize_unit(assigned["unit"][0][1])
    if unit is None:
        for _, _, txt in tokens:
            if _UNIT_FULL_RE.match(txt.strip()):
                unit = normalize_unit(txt.strip())
                break
    if unit is None:
        unit = "μg/cm2" if (sub_key == "Cr6+" and is_iec_7_1_ctx) else page_unit

    if _ND_PATTERN.match(raw_res) and not mdl:
        _note(notes, f"P.{page_num} {sub_key}：結果為 N.D. 但無法判定 MDL，已略過此項")
        return None, True

    expected = _format_expected_value(raw_res, mdl, unit)
    if not expected:
        _note(notes, f"P.{page_num} {sub_key}：無法解析結果「{raw_res}」，已略過此項")
        return None, True

    return SubstanceResult(
        substance_key=sub_key,
        expected_value=expected,
        raw_result=raw_res,
        mdl=mdl,
        unit=unit,
        page=page_num,
        evidence=line.strip()[:140],
    ), True


def _parse_without_columns(
    sub_key: str,
    line: str,
    page_unit: str,
    page_num: int,
    is_iec_7_1_ctx: bool,
    notes: List[str],
) -> Optional[SubstanceResult]:
    """沒有表頭位置資訊時的容錯解析（best effort）。"""
    unit_match = _UNIT_TOKEN_RE.search(line)
    unit = normalize_unit(unit_match.group(1)) if unit_match else page_unit
    if sub_key == "Cr6+" and is_iec_7_1_ctx and not unit_match:
        unit = "μg/cm2"

    # 移除物質名稱本身以及 CAS 號，避免把 CAS 或 Cr6+ 的 "6" 誤當數值
    cleaned = line
    cleaned = re.sub(r"\b\d{2,7}-\d{2}-\d\b", " ", cleaned)          # CAS No.
    cleaned = re.sub(r"Cr\s*\(?\s*(?:VI|6\+?)\s*\)?", " ", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"IEC\s*62321(?:-\d+(?:-\d+)?)?", " ", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"EPA\s*\d+[A-Z]?", " ", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"EN\s*14582", " ", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"2-?ethylhexyl", " ", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"[uμµ㎍]g/cm[2²]", " ", cleaned, flags=re.IGNORECASE)  # 避免 cm2 的 2 被當數值

    tokens = [m.group(1).strip() for m in _RESULT_TOKEN_RE.finditer(cleaned)]
    if not tokens:
        return None

    nd_idx = None
    for idx, tok in enumerate(tokens):
        if _ND_PATTERN.match(tok):
            nd_idx = idx
            break

    lt_idx = None
    for idx, tok in enumerate(tokens):
        if tok.startswith(("<", "≤", ">", "≥", "~")):
            lt_idx = idx
            break

    mdl_val: Optional[str] = None
    raw_res: Optional[str] = None

    if nd_idx is not None:
        raw_res = tokens[nd_idx]
        num_cands = []
        for idx, tok in enumerate(tokens):
            if idx == nd_idx:
                continue
            if re.fullmatch(r"-?\d+(?:\.\d+)?", tok):
                v = float(tok)
                if _is_common_limit(v):
                    continue
                num_cands.append((abs(idx - nd_idx), idx, tok, v))
        if num_cands:
            chosen = min(num_cands, key=lambda x: (x[0], x[3]))
            mdl_val = chosen[2]
        else:
            _note(notes, f"P.{page_num} {sub_key}：結果為 N.D. 但找不到 MDL，已略過此項")
            return None
    elif lt_idx is not None:
        raw_res = tokens[lt_idx]
        mdl_val = re.sub(r"^[<≤>≥~]\s*", "", raw_res).strip().split()[0]
    else:
        nums = [t for t in tokens if re.fullmatch(r"-?(?:\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?)", t)]
        nums = [t.replace(",", "") for t in nums]
        if len(nums) >= 3:
            first_v, last_v = float(nums[0]), float(nums[-1])
            if _is_common_limit(last_v) and not _is_common_limit(first_v):
                raw_res, mdl_val = nums[0], nums[1]
            elif _is_common_limit(first_v) and not _is_common_limit(last_v):
                raw_res, mdl_val = nums[-1], nums[-2]
            else:
                raw_res, mdl_val = nums[-1], nums[-2]
                _note(notes, f"P.{page_num} {sub_key}：無表頭可判定欄位順序，已以最後兩碼推測 Result/MDL")
        elif len(nums) == 2:
            if float(nums[0]) == 0.0 and float(nums[1]) != 0.0:
                raw_res, mdl_val = nums[0], nums[1]
            elif float(nums[1]) == 0.0 and float(nums[0]) != 0.0:
                raw_res, mdl_val = nums[1], nums[0]
            elif float(nums[0]) < float(nums[1]):
                mdl_val, raw_res = nums[0], nums[1]
            else:
                mdl_val, raw_res = nums[1], nums[0]
            _note(notes, f"P.{page_num} {sub_key}：無表頭可判定欄位順序，已以數值大小推測 MDL/Result")
        elif len(nums) == 1:
            if not unit_match:
                return None
            raw_res = nums[0]
        else:
            return None

    expected = _format_expected_value(raw_res, mdl_val, unit)
    if not expected:
        return None

    return SubstanceResult(
        substance_key=sub_key,
        expected_value=expected,
        raw_result=raw_res,
        mdl=mdl_val,
        unit=unit,
        page=page_num,
        evidence=line.strip()[:140],
    )


def _parse_substance_line(
    line: str,
    page_unit: str,
    page_num: int,
    is_iec_7_1_ctx: bool,
    columns: Optional[ResultColumns] = None,
    tokens: Optional[List[RowToken]] = None,
    notes: Optional[List[str]] = None,
) -> Optional[SubstanceResult]:
    """
    從單行（或合併後的表格列）文字解析出物質的 Unit、MDL 與 Result。
    若提供表頭 columns 與 token 座標，優先依欄位位置解析。
    """
    note_list = notes if notes is not None else []
    sub_key = _match_substance(line)
    if not sub_key:
        return None

    # 排除純標題/方法說明行（如 "Test Method: With reference to IEC 62321-5..."）
    if re.search(r"Test\s+Method|With\s+reference\s+to|determined\s+by|ICP-OES|GC-MS|UV-Vis|IC\b", line, re.IGNORECASE):
        if not re.search(r"\bN\.?D\.?\b|Negative|<\s*\d", line, re.IGNORECASE):
            return None

    if columns is not None and tokens:
        res, confident = _parse_with_columns(
            sub_key, line, tokens, columns, page_unit, page_num, is_iec_7_1_ctx, note_list
        )
        if confident:
            return res

    return _parse_without_columns(sub_key, line, page_unit, page_num, is_iec_7_1_ctx, note_list)


def _store_result(report: TestReportData, res: SubstanceResult) -> None:
    """同一物質出現多次時，優先保留有 MDL／正確單位的結果，並記錄衝突。"""
    prev = report.results.get(res.substance_key)
    chosen: Optional[SubstanceResult] = None
    if prev is None:
        chosen = res
    elif prev.mdl is None and res.mdl is not None:
        chosen = res
    elif res.substance_key == "Cr6+" and res.unit == "μg/cm2" and prev.unit == "ppm":
        chosen = res

    if chosen is None:
        return

    if prev is not None and prev.expected_value != res.expected_value:
        _note(
            report.notes,
            f"P.{prev.page}/{res.page} {res.substance_key} 解析結果不一致"
            f"（{prev.expected_value} vs {res.expected_value}），"
            f"保留 {chosen.expected_value}",
        )
    report.results[res.substance_key] = chosen


def parse_test_report_pdf(pdf_bytes: bytes, filename: str, source_entry: str) -> TestReportData:
    """解析單份第三方測試報告 PDF。"""
    try:
        import pymupdf as fitz
    except ImportError:
        import fitz

    report = TestReportData(filename=filename, source_entry=source_entry)
    full_head_texts: List[str] = []

    with fitz.open(stream=pdf_bytes, filetype="pdf") as doc:
        for page_idx in range(len(doc)):
            page_num = page_idx + 1
            page = doc[page_idx]
            raw_text = page.get_text("text") or ""

            if page_idx < 2:
                full_head_texts.append(raw_text)

            if _should_skip_page(raw_text):
                report.pages_skipped.append(page_num)
                continue

            page_unit = _detect_page_default_unit(raw_text)
            is_iec_7_1 = bool(re.search(r"IEC\s*62321-7-1", raw_text, re.IGNORECASE))
            rows_y = _extract_rows_with_y(page)

            # 優先使用「欄位 + 垂直區塊」解析，可處理名稱／方法／MDL／結果分佔多列的表。
            band_results = _parse_page_by_bands(
                rows_y, page_num, page_unit, is_iec_7_1, report.notes
            )
            if band_results:
                for res in band_results.values():
                    _store_result(report, res)
                continue

            rows = [row for _, row in rows_y]
            columns = _detect_result_columns(rows)

            # 逐列解析（同時支援「物質名稱在前一行、結果在下一行」的兩行合併容錯）
            for i, row in enumerate(rows):
                row_tokens = _merge_row_tokens(row)
                line = " ".join(t[2] for t in row_tokens)
                res = _parse_substance_line(
                    line, page_unit, page_num, is_iec_7_1, columns, row_tokens, report.notes
                )
                if res is None and _match_substance(line) and i + 1 < len(rows):
                    next_tokens = _merge_row_tokens(rows[i + 1])
                    merged_tokens = row_tokens + next_tokens
                    merged = line + " " + " ".join(t[2] for t in next_tokens)
                    res = _parse_substance_line(
                        merged, page_unit, page_num, is_iec_7_1, columns, merged_tokens, report.notes
                    )

                if res:
                    _store_result(report, res)

    head_combined = "\n".join(full_head_texts)
    report.agency = _detect_agency(head_combined, filename)
    report.all_report_nos = _extract_report_nos(head_combined, filename)
    report.report_no = report.all_report_nos[0] if report.all_report_nos else ""
    norm_date, raw_date, labeled = _extract_report_date(head_combined)
    report.report_date = norm_date
    report.raw_date = raw_date
    report.date_is_labeled = labeled

    return report


# ---------------------------------------------------------------------------
# MSDS / 成份表 PDF 解析
# ---------------------------------------------------------------------------

_CAS_RE = re.compile(r"\b(\d{2,7}-\d{2}-\d)\b")
_PCT_RE = re.compile(
    r"((?:[<>≤≥~]\s*)?\d+(?:\.\d+)?(?:\s*[-~～]\s*\d+(?:\.\d+)?)?\s*%)",
    re.IGNORECASE,
)


def is_valid_cas_checksum(cas: str) -> bool:
    """驗證 CAS 號碼檢查碼（最後一位為加權和 mod 10）。"""
    digits = cas.replace("-", "")
    if not digits.isdigit() or len(digits) < 5:
        return False
    check = int(digits[-1])
    total = sum(int(d) * (i + 1) for i, d in enumerate(reversed(digits[:-1])))
    return (total % 10) == check


def parse_msds_pdf(pdf_bytes: bytes, filename: str, source_entry: str) -> MSDSData:
    """從 MSDS / 成份表 PDF 提取成分名稱、CAS No. 與含量百分比。"""
    try:
        import pymupdf as fitz
    except ImportError:
        import fitz

    msds = MSDSData(filename=filename, source_entry=source_entry)
    seen_cas = set()

    with fitz.open(stream=pdf_bytes, filetype="pdf") as doc:
        for page_idx in range(len(doc)):
            page_num = page_idx + 1
            lines = _extract_lines_with_layout(doc[page_idx])
            for line in lines:
                cas_matches = _CAS_RE.findall(line)
                if not cas_matches:
                    continue
                pct_match = _PCT_RE.search(line)
                pct_str = pct_match.group(1).strip() if pct_match else ""

                for cas in cas_matches:
                    if not is_valid_cas_checksum(cas):
                        continue
                    msds.cas_set.add(cas)
                    if cas in seen_cas:
                        continue
                    seen_cas.add(cas)

                    # 取 CAS 之前的文字作為成分名稱猜測
                    idx = line.find(cas)
                    name_part = line[:idx].strip(" \t|:-—")
                    if not name_part and idx + len(cas) < len(line):
                        name_part = line[idx + len(cas):].strip(" \t|:-—")
                    name_part = _PCT_RE.sub("", name_part).strip(" \t|:-—")[:80]

                    msds.ingredients.append(
                        MSDSIngredient(
                            cas_no=cas,
                            name=name_part,
                            content_pct=pct_str,
                            page=page_num,
                        )
                    )

    return msds
