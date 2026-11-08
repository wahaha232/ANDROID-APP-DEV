import test from 'node:test';
import assert from 'node:assert/strict';
import { niceScale, buildSeries, timeTicks, layout, nearestIndex, formatTick } from '../chart.js';
import { toRows, toCsv, guardFormula, crc32, columnName, escapeXml, buildXlsx, exportFileName, EXPORT_HEADER } from '../export.js';
import { buildRecordsQuery, summarize } from '../lib.js';

const DAY = 86400000;
const rec = (t, v, extra = {}) => ({ id: String(t), type: 'WEIGHT', value: v, value2: null, measured_at: t, source: 'MANUAL', note: '', photo_object: null, ...extra });

test('niceScale：刻度落在 1/2/5 的倍數並涵蓋範圍', () => {
  const s = niceScale(68.6, 71.3);
  assert.ok(s.min <= 68.6 && s.max >= 71.3);
  assert.ok(s.ticks.length >= 3 && s.ticks.length <= 8);
  const step = s.ticks[1] - s.ticks[0];
  assert.ok([0.5, 1, 2, 5].includes(Number(step.toFixed(6))));
});

test('niceScale：全部相同的值也有可畫的範圍', () => {
  const s = niceScale(70, 70);
  assert.ok(s.max > s.min);
});

test('niceScale：無效輸入不丟錯', () => {
  assert.deepEqual(niceScale(NaN, 3).ticks, [0, 1]);
});

test('buildSeries：舊到新排序；血壓拆成收縮壓與舒張壓兩條', () => {
  const s = buildSeries('WEIGHT', [rec(300, 70), rec(100, 71), rec(200, 69)]);
  assert.equal(s.length, 1);
  assert.deepEqual(s[0].points.map((p) => p.t), [100, 200, 300]);
  const bp = buildSeries('BLOOD_PRESSURE', [
    rec(200, 120, { type: 'BLOOD_PRESSURE', value2: 80 }),
    rec(100, 118, { type: 'BLOOD_PRESSURE', value2: null }),
  ]);
  assert.deepEqual(bp.map((x) => x.label), ['收縮壓', '舒張壓']);
  assert.equal(bp[0].points.length, 2);
  assert.equal(bp[1].points.length, 1);
  assert.equal(bp[1].points[0].v, 80);
});

test('timeTicks：落在台北午夜，數量適中且遞增', () => {
  const t0 = Date.UTC(2026, 8, 1, 3, 0); // 台北 9/1 11:00
  const ticks = timeTicks(t0, t0 + 90 * DAY);
  assert.ok(ticks.length >= 3 && ticks.length <= 9);
  for (const t of ticks) assert.equal((t + 8 * 3600000) % DAY, 0);
  for (let i = 1; i < ticks.length; i++) assert.ok(ticks[i] > ticks[i - 1]);
  assert.match(formatTick(ticks[0]), /^\d\d\/\d\d$/);
});

test('timeTicks：只有一天資料也不會無窮迴圈', () => {
  const t0 = Date.UTC(2026, 8, 1, 3, 0);
  assert.ok(timeTicks(t0, t0).length <= 3);
});

test('layout：座標落在繪圖區內、較新的在右、較大的值在上', () => {
  const series = buildSeries('WEIGHT', [rec(0, 68), rec(10 * DAY, 72)]);
  const g = layout(series, { width: 720, height: 300, margin: { top: 16, right: 64, bottom: 28, left: 48 } });
  assert.equal(g.x(0), 48);
  assert.equal(g.x(10 * DAY), 720 - 64);
  assert.ok(g.yPos(72) < g.yPos(68));
  assert.ok(g.yPos(g.y.min) <= 300 - 28 + 0.001);
  assert.ok(g.yPos(g.y.max) >= 16 - 0.001);
});

test('layout：單一時間點置中', () => {
  const g = layout(buildSeries('WEIGHT', [rec(5, 70)]), { width: 720, height: 300, margin: { top: 16, right: 64, bottom: 28, left: 48 } });
  assert.equal(g.x(5), 48 + (720 - 48 - 64) / 2);
});

test('nearestIndex：挑最近的點', () => {
  assert.equal(nearestIndex([10, 20, 30], 26), 2);
  assert.equal(nearestIndex([10, 20, 30], 14), 0);
  assert.equal(nearestIndex([10], 999), 0);
});

test('summarize：區間變化 = 最新 − 最早', () => {
  assert.equal(summarize([{ value: 70 }, { value: 72 }, { value: 71 }]).change, -1);
  assert.equal(summarize([{ value: 70 }]).change, 0);
});

test('buildRecordsQuery：offset 只在大於 0 時帶上', () => {
  assert.equal(new URLSearchParams(buildRecordsQuery({ offset: 1000 })).get('offset'), '1000');
  assert.equal(new URLSearchParams(buildRecordsQuery()).has('offset'), false);
});

test('toRows：台北時間、中文類型、數字保持數字', () => {
  const rows = toRows([rec(Date.UTC(2026, 9, 18, 22, 33), 70, { source: 'MIJIA', note: '晨間' })]);
  assert.deepEqual(rows[0], ['2026-10-19 06:33', '體重', 70, '', '', '晨間', '米家畫面']);
});

test('guardFormula：公式開頭字元加單引號，一般文字不動', () => {
  for (const s of ['=1+1', '+cmd', '-2', '@SUM(A1)']) assert.equal(guardFormula(s), `'${s}`);
  assert.equal(guardFormula('早餐前'), '早餐前');
});

test('toCsv：BOM、標題、CRLF、逗號與引號跳脫、公式防護', () => {
  const csv = toCsv([['2026-10-19 06:33', '體重', 70.5, '', '', '含,逗號與"引號"', '手動輸入'], ['t', '體重', 1, '', '', '=HYPERLINK("x")', '']]);
  assert.ok(csv.startsWith('﻿時間,類型,數值,數值2,情境,備註,來源\r\n'));
  assert.ok(csv.includes('"含,逗號與""引號"""'));
  assert.ok(csv.includes(`"'=HYPERLINK(""x"")"`));
  assert.ok(csv.endsWith('\r\n'));
});

test('crc32：標準測試向量', () => {
  assert.equal(crc32(new TextEncoder().encode('123456789')), 0xcbf43926);
});

test('columnName 與 escapeXml', () => {
  assert.deepEqual([0, 25, 26, 27, 701, 702].map(columnName), ['A', 'Z', 'AA', 'AB', 'ZZ', 'AAA']);
  assert.equal(escapeXml('a<b>&"c"\u0001'), 'a&lt;b&gt;&amp;&quot;c&quot;');
});

test('buildXlsx：產出合法 zip（PK 開頭、結尾記錄筆數正確）', () => {
  const bytes = buildXlsx([EXPORT_HEADER, ...toRows([rec(1000, 70)])]);
  assert.equal(bytes[0], 0x50);
  assert.equal(bytes[1], 0x4b);
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  assert.equal(dv.getUint32(bytes.length - 22, true), 0x06054b50);
  assert.equal(dv.getUint16(bytes.length - 22 + 10, true), 5);
});

test('exportFileName：含範圍與台北日期', () => {
  assert.equal(exportFileName('體重_90天', 'csv', Date.UTC(2026, 9, 19, 12)), 'HealthTracker_體重_90天_20261019.csv');
});
