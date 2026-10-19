// 讀 JPEG 的 EXIF 拍攝時間（純函式，不依賴瀏覽器 API，可在 Node 測試）。
// 只實作需要的部分：APP1 Exif → TIFF → IFD0 / ExifIFD 的日期欄位。

const TAG_DATETIME = 0x0132;
const TAG_EXIF_IFD = 0x8769;
const TAG_DT_ORIGINAL = 0x9003;
const TAG_DT_DIGITIZED = 0x9004;
const TAG_OFFSET_ORIGINAL = 0x9011;
const TAG_OFFSET_DIGITIZED = 0x9012;
const TAG_OFFSET_TIME = 0x9010;
const DEFAULT_OFFSET_MIN = 8 * 60; // 沒有時差資訊時，視為台北時間（和網站顯示一致）

/** "2026:10:19 09:27:03" + 可選 "+08:00" → 毫秒；格式不對回傳 null。 */
export function parseExifDate(text, offsetText) {
  const m = /^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})/.exec(text ?? '');
  if (!m) return null;
  const [y, mo, d, h, mi, s] = m.slice(1).map(Number);
  if (mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || s > 59 || y < 1990) return null;
  let offset = DEFAULT_OFFSET_MIN;
  const o = /^([+-])(\d{2}):(\d{2})/.exec((offsetText ?? '').trim());
  if (o) offset = (o[1] === '-' ? -1 : 1) * (Number(o[2]) * 60 + Number(o[3]));
  return Date.UTC(y, mo - 1, d, h, mi, s) - offset * 60000;
}

function readIfd(view, tiff, ifdOffset, little, wanted) {
  const found = {};
  if (ifdOffset <= 0 || tiff + ifdOffset + 2 > view.byteLength) return found;
  const count = view.getUint16(tiff + ifdOffset, little);
  for (let i = 0; i < count; i++) {
    const e = tiff + ifdOffset + 2 + i * 12;
    if (e + 12 > view.byteLength) break;
    const tag = view.getUint16(e, little);
    if (!wanted.includes(tag)) continue;
    const type = view.getUint16(e + 2, little);
    const n = view.getUint32(e + 4, little);
    if (type === 4 && n === 1) { found[tag] = view.getUint32(e + 8, little); continue; } // LONG（ExifIFD 指標）
    if (type !== 2) continue; // 其餘只處理 ASCII
    const dataAt = n <= 4 ? e + 8 : tiff + view.getUint32(e + 8, little);
    if (dataAt < 0 || dataAt + n > view.byteLength) continue;
    let str = '';
    for (let k = 0; k < n; k++) {
      const c = view.getUint8(dataAt + k);
      if (c === 0) break;
      str += String.fromCharCode(c);
    }
    found[tag] = str;
  }
  return found;
}

/** 從 JPEG 位元組取拍攝時間（毫秒）；沒有 EXIF 日期回傳 null。優先順序：原始拍攝 > 數位化 > 檔案修改時間欄位。 */
export function readExifTime(buffer) {
  const view = new DataView(buffer);
  if (view.byteLength < 12 || view.getUint16(0) !== 0xffd8) return null;
  let pos = 2;
  while (pos + 4 <= view.byteLength) {
    if (view.getUint8(pos) !== 0xff) return null;
    const marker = view.getUint8(pos + 1);
    if (marker === 0xda || marker === 0xd9) return null; // 影像資料開始，沒有 EXIF
    const len = view.getUint16(pos + 2);
    if (marker === 0xe1 && pos + 10 <= view.byteLength && view.getUint32(pos + 4) === 0x45786966) { // "Exif"
      const tiff = pos + 10;
      const little = view.getUint16(tiff) === 0x4949;
      if (view.getUint16(tiff + 2, little) !== 42) return null;
      const ifd0 = readIfd(view, tiff, view.getUint32(tiff + 4, little), little, [TAG_DATETIME, TAG_EXIF_IFD]);
      const exif = typeof ifd0[TAG_EXIF_IFD] === 'number'
        ? readIfd(view, tiff, ifd0[TAG_EXIF_IFD], little, [TAG_DT_ORIGINAL, TAG_DT_DIGITIZED, TAG_OFFSET_ORIGINAL, TAG_OFFSET_DIGITIZED, TAG_OFFSET_TIME])
        : {};
      return parseExifDate(exif[TAG_DT_ORIGINAL], exif[TAG_OFFSET_ORIGINAL] ?? exif[TAG_OFFSET_TIME])
        ?? parseExifDate(exif[TAG_DT_DIGITIZED], exif[TAG_OFFSET_DIGITIZED] ?? exif[TAG_OFFSET_TIME])
        ?? parseExifDate(ifd0[TAG_DATETIME], exif[TAG_OFFSET_TIME]);
    }
    pos += 2 + len;
  }
  return null;
}
