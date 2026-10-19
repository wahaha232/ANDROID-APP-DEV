import test from 'node:test';
import assert from 'node:assert/strict';
import { readExifTime, parseExifDate } from '../exif.js';
import { glucoseCandidates, fixDigitLookalikes } from '../ocr.js';
import { toLocalInput, fromLocalInput, isDuplicate, validateRow } from '../batch.js';

/** 手工組一個只有 EXIF 日期的最小 JPEG（little-endian TIFF）。 */
function jpegWithExif({ original, offset, ifd0Date, big = false }) {
  const bytes = [];
  const le = !big;
  const u16 = (n) => (le ? [n & 255, n >> 8] : [n >> 8, n & 255]);
  const u32 = (n) => (le ? [n & 255, (n >> 8) & 255, (n >> 16) & 255, n >>> 24] : [n >>> 24, (n >> 16) & 255, (n >> 8) & 255, n & 255]);
  const ascii = (s) => [...s].map((c) => c.charCodeAt(0)).concat(0);
  const entry = (tag, type, n, val) => [...u16(tag), ...u16(type), ...u32(n), ...val];

  const dates = [];
  const exifEntries = [];
  const ifd0Len = 2 + 12 * (ifd0Date ? 2 : 1) + 4;
  const exifIfdAt = 8 + ifd0Len;
  const exifCount = (original ? 1 : 0) + (offset ? 1 : 0);
  let dataAt = exifIfdAt + 2 + 12 * exifCount + 4;
  const place = (s) => { const at = dataAt; dates.push(...ascii(s)); dataAt += s.length + 1; return at; };
  const ifd0DataAt = ifd0Date ? null : null;
  void ifd0DataAt;
  if (original) exifEntries.push(entry(0x9003, 2, original.length + 1, u32(place(original))));
  if (offset) exifEntries.push(entry(0x9011, 2, offset.length + 1, [...ascii(offset), ...Array(Math.max(0, 4 - (offset.length + 1)))].slice(0, 4)));
  const ifd0 = [...u16(ifd0Date ? 2 : 1)];
  ifd0.push(...entry(0x8769, 4, 1, u32(exifIfdAt)));
  if (ifd0Date) ifd0.push(...entry(0x0132, 2, ifd0Date.length + 1, u32(place(ifd0Date))));
  ifd0.push(...u32(0));
  const tiff = [...(le ? [0x49, 0x49] : [0x4d, 0x4d]), ...u16(42), ...u32(8), ...ifd0,
    ...u16(exifCount), ...exifEntries.flat(), ...u32(0), ...dates];
  const app1 = [0x45, 0x78, 0x69, 0x66, 0, 0, ...tiff];
  bytes.push(0xff, 0xd8, 0xff, 0xe1, (app1.length + 2) >> 8, (app1.length + 2) & 255, ...app1, 0xff, 0xda, 0, 2, 0xff, 0xd9);
  return new Uint8Array(bytes).buffer;
}

const taipei = (y, mo, d, h, mi, s) => Date.UTC(y, mo - 1, d, h - 8, mi, s);

test('parseExifDate：無時差視為台北；有時差照時差；格式錯誤為 null', () => {
  assert.equal(parseExifDate('2026:10:19 09:27:03'), taipei(2026, 10, 19, 9, 27, 3));
  assert.equal(parseExifDate('2026:10:19 09:27:03', '+00:00'), Date.UTC(2026, 9, 19, 9, 27, 3));
  assert.equal(parseExifDate('0000:00:00 00:00:00'), null);
  assert.equal(parseExifDate(''), null);
  assert.equal(parseExifDate(undefined), null);
});

test('readExifTime：讀原始拍攝時間（little / big endian）', () => {
  assert.equal(readExifTime(jpegWithExif({ original: '2026:10:19 09:27:03' })), taipei(2026, 10, 19, 9, 27, 3));
  assert.equal(readExifTime(jpegWithExif({ original: '2026:10:19 09:27:03', big: true })), taipei(2026, 10, 19, 9, 27, 3));
});

test('readExifTime：原始拍攝時間優先於 IFD0 的修改時間', () => {
  const buf = jpegWithExif({ original: '2026:10:19 09:27:03', ifd0Date: '2026:10:20 10:00:00' });
  assert.equal(readExifTime(buf), taipei(2026, 10, 19, 9, 27, 3));
});

test('readExifTime：沒有 EXIF 或不是 JPEG 回傳 null', () => {
  assert.equal(readExifTime(new Uint8Array([0xff, 0xd8, 0xff, 0xda, 0, 2, 0xff, 0xd9, 0, 0, 0, 0]).buffer), null);
  assert.equal(readExifTime(new Uint8Array(20).buffer), null);
  assert.equal(readExifTime(new ArrayBuffer(3)), null);
});

test('glucoseCandidates：挑最大字、靠近 mg/dL 的數字，排除日期時間', () => {
  const lines = [
    { text: '10/19 09:27', height: 20 },
    { text: '104', height: 120 },
    { text: 'mg/dL', height: 30 },
    { text: '2026', height: 25 },
  ];
  const c = glucoseCandidates(lines);
  assert.equal(c[0].value, 104);
  assert.ok(!c.some((x) => x.value === 2026 || x.value === 19));
});

test('glucoseCandidates：空輸入與範圍外的數字', () => {
  assert.deepEqual(glucoseCandidates([]), []);
  assert.deepEqual(glucoseCandidates([{ text: '15', height: 10 }, { text: '999', height: 10 }]), []);
});

test('fixDigitLookalikes：含數字的片段修字母，純字母不動', () => {
  assert.equal(fixDigitLookalikes('1O4'), '104');
  assert.equal(fixDigitLookalikes('l2O'), '120');
  assert.equal(fixDigitLookalikes('Ill'), 'Ill');
});

test('datetime-local 與毫秒互轉（台北時間）', () => {
  const ms = taipei(2026, 10, 19, 9, 27, 0);
  assert.equal(toLocalInput(ms), '2026-10-19T09:27');
  assert.equal(fromLocalInput('2026-10-19T09:27'), ms);
  assert.ok(Number.isNaN(fromLocalInput('')));
});

test('isDuplicate：數值相同且 ±2 分鐘內才算重複', () => {
  const ex = [{ value: 104, measured_at: 1_000_000 }];
  assert.equal(isDuplicate(ex, 104, 1_000_000 + 119_000), true);
  assert.equal(isDuplicate(ex, 104, 1_000_000 + 121_000), false);
  assert.equal(isDuplicate(ex, 105, 1_000_000), false);
});

test('validateRow：數值範圍、時間合理性', () => {
  const now = 2_000_000_000_000;
  assert.equal(validateRow(104, now - 1000, now), null);
  assert.match(validateRow(10, now, now), /20–800/);
  assert.match(validateRow(900, now, now), /20–800/);
  assert.match(validateRow(NaN, now, now), /20–800/);
  assert.match(validateRow(100, NaN, now), /時間/);
  assert.match(validateRow(100, now + 3_600_000, now), /未來/);
});
