// 匯出：CSV 與最小 XLSX 產生器（純函式，無第三方套件）。
import { TYPES, formatTime, sourceLabel } from './lib.js';

export const EXPORT_HEADER = ['時間', '類型', '數值', '數值2', '備註', '來源'];

/** 紀錄 → 表格列。時間為台北時間 yyyy-MM-dd HH:mm（與 Android App 的 CSV 匯入格式相同，可直接匯回）。 */
export function toRows(records) {
  return records.map((r) => [
    formatTime(r.measured_at),
    TYPES[r.type]?.label ?? r.type,
    r.value,
    r.value2 ?? '',
    r.note ?? '',
    sourceLabel(r.source),
  ]);
}

/** 以 = + - @ Tab CR 開頭的文字，Excel 會當公式執行（CSV 注入）；前面加單引號讓它只是文字。 */
export function guardFormula(text) {
  return /^[=+\-@\t\r]/.test(text) ? `'${text}` : text;
}

function csvCell(v) {
  if (typeof v === 'number') return String(v);
  const s = guardFormula(String(v ?? ''));
  return /[",\r\n]/.test(s) ? `"${s.replaceAll('"', '""')}"` : s;
}

/** 回傳含 UTF-8 BOM 的 CSV 字串（Excel 直接開啟中文才不會亂碼）。 */
export function toCsv(rows, header = EXPORT_HEADER) {
  return '﻿' + [header, ...rows].map((row) => row.map(csvCell).join(',')).join('\r\n') + '\r\n';
}

// ---------- XLSX（store-only zip） ----------
const enc = new TextEncoder();

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

export function crc32(bytes) {
  let c = 0xffffffff;
  for (const b of bytes) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

export function escapeXml(s) {
  return String(s)
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '') // XML 不允許的控制字元
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
}

export function columnName(i) {
  let n = i + 1;
  let s = '';
  while (n > 0) { s = String.fromCharCode(65 + ((n - 1) % 26)) + s; n = Math.floor((n - 1) / 26); }
  return s;
}

function sheetXml(rows) {
  const body = rows.map((row, r) => {
    const cells = row.map((v, c) => {
      const ref = `${columnName(c)}${r + 1}`;
      if (typeof v === 'number' && Number.isFinite(v)) return `<c r="${ref}"><v>${v}</v></c>`;
      if (v === '' || v == null) return '';
      return `<c r="${ref}" t="inlineStr"><is><t xml:space="preserve">${escapeXml(v)}</t></is></c>`;
    }).join('');
    return `<row r="${r + 1}">${cells}</row>`;
  }).join('');
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${body}</sheetData></worksheet>`;
}

function zipStore(files) {
  const parts = [];
  const central = [];
  let offset = 0;
  for (const [name, content] of files) {
    const nameBytes = enc.encode(name);
    const data = typeof content === 'string' ? enc.encode(content) : content;
    const crc = crc32(data);
    const local = new Uint8Array(30 + nameBytes.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true); lv.setUint16(4, 20, true); lv.setUint16(6, 0x0800, true); // UTF-8 檔名
    lv.setUint16(8, 0, true); lv.setUint16(10, 0, true); lv.setUint16(12, 0x21, true); // store、固定時間
    lv.setUint32(14, crc, true); lv.setUint32(18, data.length, true); lv.setUint32(22, data.length, true);
    lv.setUint16(26, nameBytes.length, true); lv.setUint16(28, 0, true);
    local.set(nameBytes, 30);
    const cen = new Uint8Array(46 + nameBytes.length);
    const cv = new DataView(cen.buffer);
    cv.setUint32(0, 0x02014b50, true); cv.setUint16(4, 20, true); cv.setUint16(6, 20, true); cv.setUint16(8, 0x0800, true);
    cv.setUint16(10, 0, true); cv.setUint16(12, 0, true); cv.setUint16(14, 0x21, true);
    cv.setUint32(16, crc, true); cv.setUint32(20, data.length, true); cv.setUint32(24, data.length, true);
    cv.setUint16(28, nameBytes.length, true); cv.setUint32(42, offset, true);
    cen.set(nameBytes, 46);
    parts.push(local, data);
    central.push(cen);
    offset += local.length + data.length;
  }
  const centralSize = central.reduce((a, c) => a + c.length, 0);
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, files.length, true); ev.setUint16(10, files.length, true);
  ev.setUint32(12, centralSize, true); ev.setUint32(16, offset, true);
  const all = [...parts, ...central, end];
  const out = new Uint8Array(all.reduce((a, p) => a + p.length, 0));
  let pos = 0;
  for (const p of all) { out.set(p, pos); pos += p.length; }
  return out;
}

/** 產生 .xlsx 位元組。rows 第一列通常是標題；數字存成數字，文字存成文字。 */
export function buildXlsx(rows, sheetName = '健康紀錄') {
  const name = escapeXml(sheetName.replace(/[\\/?*[\]:]/g, '').slice(0, 31) || 'Sheet1');
  return zipStore([
    ['[Content_Types].xml', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>'],
    ['_rels/.rels', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>'],
    ['xl/workbook.xml', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${name}" sheetId="1" r:id="rId1"/></sheets></workbook>`],
    ['xl/_rels/workbook.xml.rels', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>'],
    ['xl/worksheets/sheet1.xml', sheetXml(rows)],
  ]);
}

/** 下載檔名：HealthTracker_<範圍>_<yyyyMMdd>.<副檔名>（日期用台北時間）。 */
export function exportFileName(label, ext, now = Date.now()) {
  const d = formatTime(now).slice(0, 10).replaceAll('-', '');
  return `HealthTracker_${label}_${d}.${ext}`;
}
