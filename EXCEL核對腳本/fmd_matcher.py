# -*- coding: utf-8 -*-
"""
三星原廠成分表（SAMSUNG FMD / Material Declaration Data Sheet）解析與配對模組

功能：
  1. 以輕量 XML 解析器直接讀取 .xlsx 內的成分表工作表，避開部分原廠檔案
     docProps/custom.xml 異常導致 openpyxl 失敗的問題，並大幅提升大檔案讀取速度。
  2. 建立本機 JSON 索引快取（.fmd_cache.json），僅在 FMD 檔案新增或修改時重新解析。
  3. 三層配對策略：
     - 第一層：依供應商產品料號解碼介電質（X5R/X6S/X7R/C0G…）與尺寸（0603/1005/1608…）預篩候選
     - 第二層：比對 FMD 內 Part Number（精確料號或萬用字元樣板如 CL03A**********、CL21B******6/P/R***）
     - 第三層：優先最新版本（如 2027_V2 > 2027_V1 > 2026_V1），若仍命中多個工藝變體
       （如 Normal(Ni) vs Cu Epoxy），依 O-193D 材料結構與總重量消歧。
"""

from __future__ import annotations

import json
import os
import re
import zipfile
from dataclasses import asdict, dataclass, field
from typing import Any, Dict, List, Optional, Sequence, Tuple

try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET  # type: ignore

DEFAULT_SAMSUNG_FMD_DIR = (
    r"\\file.ndb-group.com\公用資料\儲運本部\品保部\20170628 檢驗報告"
    r"\2026 GPM (承認日期-客戶-產品線-原廠型番)\SAMSUNG FMD"
)

_NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
_NS = {"m": _NS_MAIN}
_MAX_XML_BYTES = 20 * 1024 * 1024  # 20 MB 安全上限
_CACHE_VERSION = 1

# 三星 MLCC 料號尺寸碼（公制）與介電質碼對照
_SAMSUNG_SIZE_MAP: Dict[str, str] = {
    "02": "0402",
    "03": "0603",
    "05": "1005",
    "10": "1608",
    "21": "2012",
    "31": "3216",
    "32": "3225",
    "43": "4532",
    "55": "5750",
}

_SAMSUNG_DIELECTRIC_MAP: Dict[str, str] = {
    "C": "C0G",
    "A": "X5R",
    "X": "X6S",
    "B": "X7R",
    "Y": "X7S",
    "L": "X7T",
    "F": "Y5V",
}


@dataclass
class FMDEntry:
    """FMD 成分表中的單一物質列。"""
    part_name: str
    material_name: str
    substance_name: str
    cas_no: str
    weight_mg: Optional[float] = None
    product_wt_pct: Optional[float] = None


@dataclass
class FMDRecord:
    """單份 SAMSUNG FMD 檔案的結構化摘要。"""
    filepath: str
    filename: str
    relpath: str
    mtime: float
    size: int
    product: str = ""
    part_numbers: List[str] = field(default_factory=list)
    entries: List[FMDEntry] = field(default_factory=list)
    total_weight_mg: float = 0.0
    year: int = 0
    version: int = 0
    is_root: bool = True


def _col_letter_to_idx(col: str) -> int:
    idx = 0
    for ch in col:
        idx = idx * 26 + (ord(ch.upper()) - ord("A") + 1)
    return idx


def _parse_float(raw: Any) -> Optional[float]:
    if raw is None:
        return None
    if isinstance(raw, (int, float)) and not isinstance(raw, bool):
        return float(raw)
    s = str(raw).strip().replace(",", "").replace("，", "")
    if not s:
        return None
    try:
        return float(s)
    except ValueError:
        return None


def _extract_year_version(filename: str) -> Tuple[int, int]:
    year = 0
    ver = 0
    m_y = re.search(r"[_\s](20\d{2})(?:[_\s\.]|$)", filename)
    if m_y:
        year = int(m_y.group(1))
    m_v = re.search(r"[_\s]V(\d+)", filename, re.IGNORECASE)
    if m_v:
        ver = int(m_v.group(1))
    return year, ver


def _read_xlsx_first_sheet_rows(filepath: str, max_rows: int = 80) -> List[Tuple[int, Dict[int, str]]]:
    """以 zipfile + XML 輕量讀取 .xlsx 第一個工作表的前 max_rows 列。"""
    with zipfile.ZipFile(filepath, "r") as zf:
        names = set(zf.namelist())
        sst: List[str] = []
        if "xl/sharedStrings.xml" in names:
            info = zf.getinfo("xl/sharedStrings.xml")
            if info.file_size <= _MAX_XML_BYTES:
                root = ET.fromstring(zf.read("xl/sharedStrings.xml"))
                t_tag = f"{{{_NS_MAIN}}}t"
                for si in root.findall("m:si", _NS):
                    sst.append("".join(el.text or "" for el in si.iter(t_tag)))

        sheet_names = [
            n for n in names
            if re.match(r"^xl/worksheets/sheet\d+\.xml$", n)
        ]
        if not sheet_names:
            return []
        sheet_names.sort(key=lambda s: int(re.search(r"(\d+)\.xml$", s).group(1)))

        target = sheet_names[0]
        if zf.getinfo(target).file_size > _MAX_XML_BYTES:
            return []

        root = ET.fromstring(zf.read(target))
        t_tag = f"{{{_NS_MAIN}}}t"
        rows_out: List[Tuple[int, Dict[int, str]]] = []

        for row_el in root.findall(".//m:sheetData/m:row", _NS):
            r_idx = int(row_el.attrib.get("r", "0") or 0)
            if r_idx > max_rows:
                break
            row_map: Dict[int, str] = {}
            for c_el in row_el.findall("m:c", _NS):
                ref = c_el.attrib.get("r", "")
                m_col = re.match(r"^([A-Z]+)", ref)
                if not m_col:
                    continue
                c_idx = _col_letter_to_idx(m_col.group(1))
                cell_type = c_el.attrib.get("t", "")
                v_el = c_el.find("m:v", _NS)
                val: Optional[str] = None
                if cell_type == "s" and v_el is not None and v_el.text:
                    idx = int(v_el.text)
                    if 0 <= idx < len(sst):
                        val = sst[idx]
                elif cell_type == "inlineStr":
                    val = "".join(el.text or "" for el in c_el.iter(t_tag))
                elif v_el is not None and v_el.text is not None:
                    val = v_el.text
                if val is not None:
                    cleaned = str(val).strip()
                    if cleaned != "":
                        row_map[c_idx] = cleaned
            if row_map:
                rows_out.append((r_idx, row_map))
        return rows_out


def parse_samsung_fmd_file(filepath: str, base_dir: Optional[str] = None) -> Optional[FMDRecord]:
    """解析單份 SAMSUNG FMD .xlsx 檔案，回傳 FMDRecord；非 FMD 格式回傳 None。"""
    abs_path = os.path.abspath(filepath)
    if not os.path.isfile(abs_path):
        return None
    st = os.stat(abs_path)
    filename = os.path.basename(abs_path)
    relpath = os.path.relpath(abs_path, base_dir) if base_dir else filename
    is_root = os.path.dirname(relpath) in ("", ".")
    year, version = _extract_year_version(filename)

    try:
        rows = _read_xlsx_first_sheet_rows(abs_path, max_rows=80)
    except Exception:
        return None
    if not rows:
        return None

    # 尋找表頭列（含 Part Name 與 Substance Name / CAS No.）
    header_row_idx: Optional[int] = None
    col_map: Dict[str, int] = {}

    for r_idx, rmap in rows[:25]:
        found: Dict[str, int] = {}
        for c_idx, txt in rmap.items():
            norm = re.sub(r"\s+", " ", txt).strip().lower()
            if norm == "product" and "product" not in found:
                found["product"] = c_idx
            elif re.search(r"part\s*number|part\s*no", norm) and "part_number" not in found:
                found["part_number"] = c_idx
            elif re.search(r"part\s*name", norm) and "part_name" not in found:
                found["part_name"] = c_idx
            elif re.search(r"material\s*name", norm) and "material_name" not in found:
                found["material_name"] = c_idx
            elif re.search(r"substance\s*name", norm) and "substance_name" not in found:
                found["substance_name"] = c_idx
            elif re.search(r"cas\s*no", norm) and "cas_no" not in found:
                found["cas_no"] = c_idx
            elif re.search(r"content.*wt%|wt%", norm) and "wt_pct" not in found:
                found["wt_pct"] = c_idx
            elif re.search(r"content.*\(mg\)|weight.*\(mg\)", norm) and "weight_mg" not in found:
                found["weight_mg"] = c_idx

        if "part_name" in found and ("substance_name" in found or "cas_no" in found):
            header_row_idx = r_idx
            col_map = found
            break

    if header_row_idx is None:
        return None

    product = ""
    part_numbers: List[str] = []
    entries: List[FMDEntry] = []
    cur_part = ""
    cur_material = ""

    c_prod = col_map.get("product")
    c_pn = col_map.get("part_number")
    c_part = col_map.get("part_name")
    c_mat = col_map.get("material_name")
    c_sub = col_map.get("substance_name")
    c_cas = col_map.get("cas_no")
    c_mg = col_map.get("weight_mg")
    c_pct = col_map.get("wt_pct")

    for r_idx, rmap in rows:
        if r_idx <= header_row_idx:
            continue

        # 略過子表頭（RoHS / Cd / Pb ...）與底部註解（Figures / Notes / Total）
        first_vals = " ".join(rmap.values())
        if re.match(r"^(?:RoHS|Halogen|Cd\s+Pb)", first_vals, re.IGNORECASE):
            continue
        if any(
            re.search(r"^(?:Figures|Notes?\s*:|Total\b|Remark)", v, re.IGNORECASE)
            for v in rmap.values()
        ):
            if entries:
                break
            continue

        if c_prod and c_prod in rmap and not product:
            product = re.sub(r"\s+", " ", rmap[c_prod]).strip()

        if c_pn and c_pn in rmap:
            raw_pn = rmap[c_pn]
            for token in re.split(r"[\r\n,;]+", raw_pn):
                tok = token.strip()
                if tok and tok not in part_numbers:
                    part_numbers.append(tok)

        part_val = re.sub(r"\s+", " ", rmap[c_part]).strip() if (c_part and c_part in rmap) else ""
        mat_val = re.sub(r"\s+", " ", rmap[c_mat]).strip() if (c_mat and c_mat in rmap) else ""
        sub_val = re.sub(r"\s+", " ", rmap[c_sub]).strip() if (c_sub and c_sub in rmap) else ""
        cas_val = re.sub(r"\s+", "", rmap[c_cas]).strip() if (c_cas and c_cas in rmap) else ""
        mg_val = _parse_float(rmap.get(c_mg)) if c_mg else None
        pct_val = _parse_float(rmap.get(c_pct)) if c_pct else None

        if part_val:
            cur_part = part_val
        if mat_val:
            cur_material = mat_val

        # 若既無物質名稱、CAS、重量，則不是資料列
        if not sub_val and not cas_val and mg_val is None:
            continue
        if not cur_part and not cur_material:
            continue

        entries.append(
            FMDEntry(
                part_name=cur_part,
                material_name=cur_material,
                substance_name=sub_val,
                cas_no=cas_val,
                weight_mg=mg_val,
                product_wt_pct=pct_val,
            )
        )

    if not entries:
        return None

    # 若檔名本身包含完整料號（如 CL21B475KLB6PJE.xlsx），也納入候選 part_numbers
    stem = os.path.splitext(filename)[0].strip()
    for m_fn in re.finditer(r"\b(CL\d{2}[A-Z][A-Z0-9]{6,}|CI[A-Z]{2}\d{6,}[A-Z0-9]*)\b", stem, re.IGNORECASE):
        cand = m_fn.group(1).upper()
        if cand not in {p.upper() for p in part_numbers}:
            part_numbers.append(cand)

    total_mg = round(sum(e.weight_mg for e in entries if e.weight_mg is not None), 6)
    return FMDRecord(
        filepath=abs_path,
        filename=filename,
        relpath=relpath,
        mtime=st.st_mtime,
        size=st.st_size,
        product=product,
        part_numbers=part_numbers,
        entries=entries,
        total_weight_mg=total_mg,
        year=year,
        version=version,
        is_root=is_root,
    )


# ---------------------------------------------------------------------------
# 本機快取索引
# ---------------------------------------------------------------------------

def _default_cache_path() -> str:
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), ".fmd_cache.json")


def _record_from_dict(data: Dict[str, Any]) -> FMDRecord:
    entries = [FMDEntry(**e) for e in data.get("entries", [])]
    return FMDRecord(
        filepath=data["filepath"],
        filename=data["filename"],
        relpath=data.get("relpath", data["filename"]),
        mtime=float(data.get("mtime", 0.0)),
        size=int(data.get("size", 0)),
        product=data.get("product", ""),
        part_numbers=list(data.get("part_numbers", [])),
        entries=entries,
        total_weight_mg=float(data.get("total_weight_mg", 0.0)),
        year=int(data.get("year", 0)),
        version=int(data.get("version", 0)),
        is_root=bool(data.get("is_root", True)),
    )


def load_fmd_index(
    fmd_dir: str,
    cache_path: Optional[str] = None,
) -> Tuple[List[FMDRecord], List[str]]:
    """
    載入 fmd_dir（含第一層子資料夾，如 2026/）內所有 SAMSUNG FMD 檔案索引。
    利用本機 JSON 快取避免重複解析未異動的大型 .xlsx 檔案。
    回傳 (records, notes)。
    """
    notes: List[str] = []
    if not fmd_dir:
        return [], notes
    if not os.path.isdir(fmd_dir):
        notes.append(f"無法存取 SAMSUNG FMD 路徑：{fmd_dir}（略過 FMD 欄位 B~J 比對）。")
        return [], notes

    resolved_cache = cache_path or _default_cache_path()
    cached_by_path: Dict[str, Dict[str, Any]] = {}
    if os.path.isfile(resolved_cache):
        try:
            with open(resolved_cache, "r", encoding="utf-8") as fp:
                raw_cache = json.load(fp)
            if (
                isinstance(raw_cache, dict)
                and raw_cache.get("cache_version") == _CACHE_VERSION
                and os.path.normcase(raw_cache.get("fmd_dir", "")) == os.path.normcase(fmd_dir)
            ):
                for item in raw_cache.get("records", []):
                    cached_by_path[os.path.normcase(item["filepath"])] = item
        except Exception:
            cached_by_path = {}

    # 掃描根目錄與第一層子資料夾
    xlsx_paths: List[str] = []
    try:
        with os.scandir(fmd_dir) as entries:
            for entry in entries:
                if entry.is_file() and entry.name.lower().endswith(".xlsx") and not entry.name.startswith("~$"):
                    xlsx_paths.append(entry.path)
                elif entry.is_dir() and not entry.name.startswith("."):
                    try:
                        with os.scandir(entry.path) as sub_entries:
                            for sub in sub_entries:
                                if sub.is_file() and sub.name.lower().endswith(".xlsx") and not sub.name.startswith("~$"):
                                    xlsx_paths.append(sub.path)
                    except OSError:
                        pass
    except OSError as exc:
        notes.append(f"掃描 SAMSUNG FMD 路徑失敗：{exc}（略過 FMD 欄位 B~J 比對）。")
        return [], notes

    records: List[FMDRecord] = []
    dirty = False
    for path in sorted(xlsx_paths, key=lambda p: p.lower()):
        abs_p = os.path.abspath(path)
        key = os.path.normcase(abs_p)
        try:
            st = os.stat(abs_p)
        except OSError:
            continue
        cached = cached_by_path.get(key)
        if cached and cached.get("mtime") == st.st_mtime and cached.get("size") == st.st_size:
            records.append(_record_from_dict(cached))
            continue

        rec = parse_samsung_fmd_file(abs_p, base_dir=fmd_dir)
        if rec is not None:
            records.append(rec)
            dirty = True

    if dirty or len(records) != len(cached_by_path):
        try:
            payload = {
                "cache_version": _CACHE_VERSION,
                "fmd_dir": fmd_dir,
                "records": [asdict(r) for r in records],
            }
            with open(resolved_cache, "w", encoding="utf-8") as fp:
                json.dump(payload, fp, ensure_ascii=False)
        except OSError:
            pass

    return records, notes


# ---------------------------------------------------------------------------
# 三層配對策略：尺寸/介電質解碼 -> Part Number 樣板 -> 工藝變體消歧
# ---------------------------------------------------------------------------

def decode_samsung_mlcc_part_no(supplier_pn: str) -> Optional[Tuple[str, str]]:
    """
    從三星 MLCC 料號解碼 (介電質, 公制尺寸)，例如：
      CL03A105MQ3CSNH -> ('X5R', '0603')
      CL10B104KB8NNNC -> ('X7R', '1608')
    非標準 CL 料號回傳 None。
    """
    s = re.sub(r"\s+", "", (supplier_pn or "").upper())
    m = re.match(r"^CL(\d{2})([A-Z])", s)
    if not m:
        return None
    size_code = _SAMSUNG_SIZE_MAP.get(m.group(1))
    diel_code = _SAMSUNG_DIELECTRIC_MAP.get(m.group(2))
    if not size_code or not diel_code:
        return None
    return diel_code, size_code


def _part_number_pattern_to_regex(pattern: str) -> Optional[re.Pattern]:
    """
    將 FMD 內的 Part Number 樣板轉為正規表達式：
      - CL03A**********       -> ^CL03A.{10,}$
      - CL21B******6/P/R***   -> ^CL21B.{6}(?:6|P|R).{3,}$
      - CIGT201208EH          -> ^CIGT201208EH
    """
    p = re.sub(r"\s+", "", (pattern or "").upper())
    if len(p) < 4:
        return None

    # 處理斜線選項（例如 6/P/R）與萬用字元 *
    tokens: List[str] = []
    i = 0
    n = len(p)
    has_wildcard = "*" in p or "/" in p
    while i < n:
        # 檢查是否為單字元斜線選項群組，例如 6/P/R
        m_alt = re.match(r"([A-Z0-9](?:/[A-Z0-9])+)", p[i:])
        if m_alt:
            alts = m_alt.group(1).split("/")
            tokens.append("(?:" + "|".join(re.escape(a) for a in alts) + ")")
            i += len(m_alt.group(1))
            continue
        if p[i] == "*":
            j = i
            while j < n and p[j] == "*":
                j += 1
            count = j - i
            if j == n:
                # 尾端星號容許長度至少 count-2（部分料號尾碼略短或略長）
                min_len = max(1, count - 2)
                tokens.append(f".{{{min_len},}}")
            else:
                tokens.append(f".{{{count}}}")
            i = j
            continue
        tokens.append(re.escape(p[i]))
        i += 1

    expr = "^" + "".join(tokens) + ("$" if has_wildcard else "")
    return re.compile(expr, re.IGNORECASE)


def _match_part_number_score(supplier_pn: str, record: FMDRecord) -> int:
    """
    計算 supplier_pn 與 FMDRecord 的 Part Number 匹配分數：
      - 100：精確完全一致
      - 60~95：萬用字元樣板命中（具體非星號字元越多分數越高，例如 CL32C******6*** > CL32C**********）
      - 40：檔名含介電質與尺寸（作為備援）
      - 0：不匹配
    """
    pn_norm = re.sub(r"\s+", "", (supplier_pn or "").upper())
    if not pn_norm:
        return 0

    best = 0
    for cand in record.part_numbers:
        cand_norm = re.sub(r"\s+", "", cand.upper())
        if not cand_norm:
            continue
        if cand_norm == pn_norm:
            return 100
        rx = _part_number_pattern_to_regex(cand_norm)
        if rx and rx.match(pn_norm):
            concrete_chars = len(re.sub(r"[\*/]", "", cand_norm))
            score = min(95, 60 + concrete_chars)
            if score > best:
                best = score

    if best > 0:
        return best

    # 第一層備援：若料號可解碼為 (介電質, 尺寸)，比對檔名
    decoded = decode_samsung_mlcc_part_no(pn_norm)
    if decoded:
        diel, size = decoded
        fn_up = record.filename.upper()
        if diel in fn_up and size in fn_up:
            return 40
    return 0


def _structural_similarity(
    record: FMDRecord,
    excel_materials: Sequence[str],
    excel_total_mg: Optional[float],
) -> float:
    """計算 FMD 與 O-193D 材料結構與總重量的相似度（用於多個工藝變體消歧）。"""
    fmd_mats = {
        re.sub(r"\s+", " ", e.material_name).strip().lower()
        for e in record.entries
        if e.material_name
    }
    fmd_parts = {
        re.sub(r"\s+", " ", e.part_name).strip().lower()
        for e in record.entries
        if e.part_name
    }
    ex_mats = {
        re.sub(r"\s+", " ", m).strip().lower()
        for m in excel_materials
        if m and m.strip()
    }

    score = 0.0
    if ex_mats and fmd_mats:
        inter = len(ex_mats & fmd_mats)
        union = len(ex_mats | fmd_mats)
        score += 10.0 * (inter / union)

    # 若 O-193D 含 Epoxy 相關字樣，優先匹配含 Epoxy 的 FMD；反之優先不含 Epoxy 的 Normal(Ni)
    ex_has_epoxy = any("epoxy" in m for m in ex_mats)
    fmd_has_epoxy = any("epoxy" in m for m in fmd_mats) or any("epoxy" in p for p in fmd_parts)
    if ex_has_epoxy == fmd_has_epoxy:
        score += 5.0

    if excel_total_mg is not None and excel_total_mg > 0 and record.total_weight_mg > 0:
        rel_diff = abs(record.total_weight_mg - excel_total_mg) / max(excel_total_mg, 1e-6)
        if rel_diff <= 0.02:
            score += 6.0
        elif rel_diff <= 0.1:
            score += 3.0
        else:
            score -= min(5.0, rel_diff * 5.0)

    return score


def match_samsung_fmd(
    supplier_pn: str,
    records: Sequence[FMDRecord],
    excel_materials: Sequence[str] = (),
    excel_total_mg: Optional[float] = None,
) -> Tuple[Optional[FMDRecord], Optional[str]]:
    """
    依三層配對策略找出最適合的 SAMSUNG FMD 紀錄。
    回傳 (matched_record, warning_message)。
    """
    if not supplier_pn or not records:
        return None, None

    scored: List[Tuple[int, int, int, int, FMDRecord]] = []
    for rec in records:
        pn_score = _match_part_number_score(supplier_pn, rec)
        if pn_score <= 0:
            continue
        scored.append((
            pn_score,
            1 if rec.is_root else 0,
            rec.year,
            rec.version,
            rec,
        ))

    if not scored:
        return None, None

    # 1. 取 Part Number 匹配分數最高者（精確料號 > 具特定碼樣板 > 通用樣板 > 檔名尺寸）
    max_pn_score = max(item[0] for item in scored)
    top = [item for item in scored if item[0] == max_pn_score]

    # 2. 同一系列若同時有 2027 與 2026（舊版），只保留最新版本（依 (is_root, year, version) 篩選）
    best_ver_key = max((item[1], item[2], item[3]) for item in top)
    top_latest = [item[4] for item in top if (item[1], item[2], item[3]) == best_ver_key]

    if len(top_latest) == 1:
        return top_latest[0], None

    # 3. 仍有多份（例如 Normal(Ni) vs Cu Epoxy vs Automotive），依結構與總重量消歧
    ranked = sorted(
        (
            (_structural_similarity(rec, excel_materials, excel_total_mg), rec)
            for rec in top_latest
        ),
        key=lambda x: x[0],
        reverse=True,
    )
    if len(ranked) >= 2 and (ranked[0][0] - ranked[1][0]) >= 2.0:
        return ranked[0][1], None

    names = "、".join(sorted(r.filename for r in top_latest)[:4])
    return None, (
        f"供應商料號「{supplier_pn}」同時符合多份 SAMSUNG FMD（{names}），"
        "無法自動區分工藝版本，請人工確認。"
    )
