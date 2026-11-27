# -*- coding: utf-8 -*-
"""
內嵌 PDF 提取與分類模組
支援：
  - .xls  (OLE2 Compound Document: CONTENTS / \x01Ole10Native / 原始串流掃描)
  - .xlsx / .xlsm (ZIP: xl/embeddings/ 內的 .pdf 與 OLE .bin 封裝)
自動將提取出的 PDF 分類為：
  - "test_report" (第三方測試報告)
  - "msds"        (MSDS / SDS / 成份表)
  - "unknown"     (其他無法判定類別)
"""

from __future__ import annotations

import hashlib
import os
import re
import struct
import sys
import zipfile
from dataclasses import dataclass
from typing import List, Optional, Tuple

PDF_MAGIC = b"%PDF-"
PDF_EOF = b"%%EOF"

# 單一內嵌物件與全部物件的未壓縮大小上限，避免解壓縮炸彈耗盡記憶體
MAX_EMBED_ENTRY_BYTES = 200 * 1024 * 1024
MAX_EMBED_TOTAL_BYTES = 400 * 1024 * 1024


def _warn(message: str) -> None:
    print(f"[警告] {message}", file=sys.stderr)


@dataclass
class EmbeddedPDF:
    """從 Excel 提取出來的單一內嵌 PDF 檔案。"""
    source_entry: str          # OLE 路徑或 ZIP 路徑
    filename: str              # 推斷的原始檔名
    data: bytes                # PDF 原始位元組
    category: str = "unknown"  # "test_report" | "msds" | "unknown"
    sha256: str = ""

    def __post_init__(self) -> None:
        if not self.sha256:
            self.sha256 = hashlib.sha256(self.data).hexdigest()


def _extract_pdf_bytes(raw: bytes) -> Optional[bytes]:
    """從原始位元組流（可能包含 Ole10Native 標頭或尾端雜訊）切出有效 PDF 內容。"""
    start = raw.find(PDF_MAGIC)
    if start == -1:
        return None
    end = raw.rfind(PDF_EOF)
    if end != -1 and end > start:
        return raw[start : end + len(PDF_EOF)]
    return raw[start:]


def _parse_ole10native(raw: bytes) -> Tuple[Optional[str], Optional[bytes]]:
    """
    解析 OLE 1.0 Native (\x01Ole10Native) 結構。
    若標頭格式非標準，則退回直接搜尋 %PDF-。
    """
    filename: Optional[str] = None
    try:
        if len(raw) > 10:
            pos = 6
            nul1 = raw.find(b"\x00", pos)
            if nul1 != -1 and nul1 - pos <= 260:
                label_bytes = raw[pos:nul1]
                for enc in ("utf-8", "cp950", "gbk", "latin-1"):
                    try:
                        cand = label_bytes.decode(enc).strip()
                        if cand:
                            filename = os.path.basename(cand)
                            break
                    except UnicodeDecodeError:
                        continue
            if nul1 != -1:
                pos = nul1 + 1
                nul2 = raw.find(b"\x00", pos)
                if nul2 != -1:
                    pos = nul2 + 1 + 4
                    if pos + 4 <= len(raw):
                        tmp_len = struct.unpack_from("<I", raw, pos)[0]
                        pos += 4 + tmp_len
                        if pos + 4 <= len(raw):
                            data_len = struct.unpack_from("<I", raw, pos)[0]
                            pos += 4
                            if 0 < data_len <= len(raw) - pos:
                                payload = raw[pos : pos + data_len]
                                pdf = _extract_pdf_bytes(payload)
                                if pdf:
                                    return filename, pdf
    except Exception:
        pass

    pdf = _extract_pdf_bytes(raw)
    return filename, pdf


def _extract_from_ole_bytes(ole_bytes: bytes, source_prefix: str) -> List[EmbeddedPDF]:
    """使用 olefile 從 OLE2 檔案或位元組流中提取所有內嵌 PDF。"""
    import olefile

    results: List[EmbeddedPDF] = []
    if not olefile.isOleFile(ole_bytes):
        if len(ole_bytes) > MAX_EMBED_ENTRY_BYTES:
            _warn(f"略過過大的內嵌物件 {source_prefix}（{len(ole_bytes)} bytes）")
            return results
        pdf = _extract_pdf_bytes(ole_bytes)
        if pdf:
            results.append(EmbeddedPDF(source_entry=source_prefix, filename=os.path.basename(source_prefix), data=pdf))
        return results

    total_bytes = 0
    with olefile.OleFileIO(ole_bytes) as ole:
        for stream_path in ole.listdir(streams=True, storages=False):
            stream_name = "/".join(stream_path)
            leaf = stream_path[-1]
            try:
                stream_size = ole.get_size(stream_path)
            except Exception:
                stream_size = None
            if stream_size is not None and (
                stream_size > MAX_EMBED_ENTRY_BYTES
                or total_bytes + stream_size > MAX_EMBED_TOTAL_BYTES
            ):
                _warn(f"略過過大的內嵌串流 {source_prefix}::{stream_name}（{stream_size} bytes）")
                continue
            try:
                raw = ole.openstream(stream_path).read()
            except Exception:
                continue
            if len(raw) > MAX_EMBED_ENTRY_BYTES or total_bytes + len(raw) > MAX_EMBED_TOTAL_BYTES:
                _warn(f"略過過大的內嵌串流 {source_prefix}::{stream_name}")
                continue
            total_bytes += len(raw)

            if not raw or PDF_MAGIC not in raw:
                continue

            fname: Optional[str] = None
            pdf_data: Optional[bytes] = None

            if leaf == "\x01Ole10Native" or "Ole10Native" in leaf:
                fname, pdf_data = _parse_ole10native(raw)
            elif leaf.upper() == "CONTENTS":
                pdf_data = _extract_pdf_bytes(raw)
                fname = f"{stream_path[0]}.pdf" if len(stream_path) > 1 else "CONTENTS.pdf"
            else:
                pdf_data = _extract_pdf_bytes(raw)
                fname = f"{'_'.join(stream_path)}.pdf"

            if pdf_data:
                if not fname:
                    fname = f"{'_'.join(stream_path)}.pdf"
                if not fname.lower().endswith(".pdf"):
                    fname += ".pdf"
                full_src = f"{source_prefix}::{stream_name}" if source_prefix else stream_name
                results.append(EmbeddedPDF(source_entry=full_src, filename=fname, data=pdf_data))

    return results


def _extract_from_xls(excel_path: str) -> List[EmbeddedPDF]:
    """從 .xls (OLE2) 提取內嵌 PDF。"""
    with open(excel_path, "rb") as f:
        raw = f.read()
    return _extract_from_ole_bytes(raw, os.path.basename(excel_path))


def _extract_from_xlsx(excel_path: str) -> List[EmbeddedPDF]:
    """從 .xlsx / .xlsm (ZIP) 的 xl/embeddings/ 提取內嵌 PDF（含直接 PDF 與 OLE .bin 封裝）。"""
    results: List[EmbeddedPDF] = []
    total_bytes = 0
    with zipfile.ZipFile(excel_path, "r") as zf:
        for info in zf.infolist():
            name_lower = info.filename.lower()
            if not name_lower.startswith("xl/embeddings/"):
                continue
            if info.is_dir():
                continue
            if info.file_size > MAX_EMBED_ENTRY_BYTES or total_bytes + info.file_size > MAX_EMBED_TOTAL_BYTES:
                _warn(
                    f"略過過大的內嵌物件 {info.filename}"
                    f"（未壓縮 {info.file_size} bytes，累計上限 {MAX_EMBED_TOTAL_BYTES} bytes）"
                )
                continue
            try:
                with zf.open(info) as fp:
                    raw = fp.read(MAX_EMBED_ENTRY_BYTES + 1)
            except Exception:
                continue
            if not raw:
                continue
            if len(raw) > MAX_EMBED_ENTRY_BYTES or total_bytes + len(raw) > MAX_EMBED_TOTAL_BYTES:
                _warn(f"略過過大的內嵌物件 {info.filename}（解壓後超過上限）")
                continue
            total_bytes += len(raw)

            if name_lower.endswith(".pdf"):
                pdf = _extract_pdf_bytes(raw)
                if pdf:
                    results.append(
                        EmbeddedPDF(
                            source_entry=info.filename,
                            filename=os.path.basename(info.filename),
                            data=pdf,
                        )
                    )
            else:
                sub_pdfs = _extract_from_ole_bytes(raw, info.filename)
                results.extend(sub_pdfs)
    return results


# 分類關鍵字（大小寫不敏感）
_MSDS_STRONG_PATTERNS = [
    r"\bM?SDS\b",
    r"MATERIAL\s+SAFETY\s+DATA\s+SHEET",
    r"SAFETY\s+DATA\s+SHEET",
    r"物質安全資料表",
    r"安全資料表",
    r"化学品安全技术说明书",
    r"化學品安全技術說明書",
    r"安全技术说明书",
    r"成分表|成份表|材質成分表|材料成分表",
    r"COMPOSITION\s*/?\s*INFORMATION\s+ON\s+INGREDIENTS",
    r"SECTION\s*1[\s.:]+IDENTIFICATION",
    r"SECTION\s*2[\s.:]+HAZARDS?\s+IDENTIFICATION",
    r"SECTION\s*3[\s.:]+COMPOSITION",
    r"HAZARDS?\s+IDENTIFICATION",
    r"GHS\s+CLASSIFICATION",
    r"危害辨識資料|危险性概述|急救措施|消防措施|洩漏處理方法|泄漏应急处理",
]

_TEST_REPORT_STRONG_PATTERNS = [
    r"\bTEST\s+REPORT\b",
    r"檢測報告|检测报告|測試報告|测试报告",
    r"\bSGS\b",
    r"\bINTERTEK\b",
    r"\bCTI\b|CENTRE\s+TESTING\s+INTERNATIONAL|华测检测|華測檢測",
    r"\bT[UÜ]V\b|RHEINLAND|T[UÜ]V\s+S[UÜ]D",
    r"\bEUROFINS\b|\bBUREAU\s+VERITAS\b|\bPONY\b",
    r"\bIEC\s*62321\b|\bEPA\s*305[012]\b|\bEN\s*14582\b",
    r"\bROHS\b",
    r"\bREPORT\s*NO\.?",
]


def classify_pdf(pdf_obj: EmbeddedPDF, sample_text: str = "") -> str:
    """
    依檔名與前幾頁文字內容將 PDF 分類為：
      - "test_report": 第三方測試報告
      - "msds":        MSDS / SDS / 成份表
      - "unknown":     無法判定
    """
    haystack = f"{pdf_obj.filename}\n{sample_text[:6000]}"

    msds_score = sum(1 for pat in _MSDS_STRONG_PATTERNS if re.search(pat, haystack, re.IGNORECASE))
    report_score = sum(1 for pat in _TEST_REPORT_STRONG_PATTERNS if re.search(pat, haystack, re.IGNORECASE))

    # 檔名明確標示加權
    fname_upper = pdf_obj.filename.upper()
    if any(k in fname_upper for k in ("MSDS", "SDS", "成分", "成份", "COMPOSITION")):
        msds_score += 3
    if any(k in fname_upper for k in ("SGS", "INTERTEK", "CTI", "TUV", "ROHS", "REPORT", "報告", "报告")):
        report_score += 2

    if msds_score > report_score and msds_score >= 1:
        return "msds"
    if report_score >= 1:
        return "test_report"
    return "unknown"


def extract_embedded_pdfs(excel_path: str) -> List[EmbeddedPDF]:
    """
    從 .xls / .xlsx / .xlsm 提取所有內嵌 PDF 並去重與分類。
    分類會使用 PyMuPDF 讀取前 2 頁文字輔助判斷。
    """
    if not os.path.isfile(excel_path):
        raise FileNotFoundError(f"找不到 Excel 檔案：{excel_path}")

    ext = os.path.splitext(excel_path)[1].lower()
    if ext == ".xls":
        raw_pdfs = _extract_from_xls(excel_path)
    elif ext in (".xlsx", ".xlsm"):
        raw_pdfs = _extract_from_xlsx(excel_path)
    else:
        raise ValueError(f"不支援的 Excel 格式：{ext}（僅支援 .xls / .xlsx / .xlsm）")

    # 依 SHA-256 去重（避免同一檔案在 OLE 多個串流重複出現）
    deduped: List[EmbeddedPDF] = []
    seen_hashes = set()
    for pdf in raw_pdfs:
        if pdf.sha256 in seen_hashes:
            continue
        seen_hashes.add(pdf.sha256)
        deduped.append(pdf)

    # 讀取前 2 頁文字進行分類
    try:
        import pymupdf as fitz
    except ImportError:
        import fitz

    for pdf in deduped:
        sample_text = ""
        try:
            with fitz.open(stream=pdf.data, filetype="pdf") as doc:
                parts = []
                for idx in range(min(2, len(doc))):
                    parts.append(doc[idx].get_text("text") or "")
                sample_text = "\n".join(parts)
        except Exception:
            sample_text = ""
        pdf.category = classify_pdf(pdf, sample_text)

    return deduped
