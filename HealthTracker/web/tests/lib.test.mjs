import test from 'node:test';
import assert from 'node:assert/strict';
import {
  fetchAllPages,
  rangeStart, normalizeBaseUrl, buildRecordsQuery, sessionNeedsRefresh,
  formatValue, formatTime, sourceLabel, summarize, isSafePhotoPath,
} from '../lib.js';

const NOW = Date.UTC(2026, 9, 19, 12, 0, 0);

test('rangeStart：天數換算與「全部」不限', () => {
  assert.equal(rangeStart('30', NOW), NOW - 30 * 86400000);
  assert.equal(rangeStart('365', NOW), NOW - 365 * 86400000);
  assert.equal(rangeStart('all', NOW), null);
  assert.equal(rangeStart('bogus', NOW), null);
});

test('normalizeBaseUrl：只接受 supabase.co，去掉路徑與結尾斜線', () => {
  assert.equal(normalizeBaseUrl('https://abcd1234.supabase.co/'), 'https://abcd1234.supabase.co');
  assert.equal(normalizeBaseUrl(' https://abcd1234.supabase.co/rest/v1 '), 'https://abcd1234.supabase.co');
});

test('normalizeBaseUrl：拒絕不明網址、http、假冒網域', () => {
  assert.throws(() => normalizeBaseUrl('https://evil.example.com'));
  assert.throws(() => normalizeBaseUrl('http://abcd.supabase.co'));
  assert.throws(() => normalizeBaseUrl('https://abcd.supabase.co.evil.com'));
  assert.throws(() => normalizeBaseUrl('https://evil.com/?x=.supabase.co'));
  assert.throws(() => normalizeBaseUrl('not a url'));
});

test('normalizeBaseUrl：localhost 只在頁面本身也是 localhost 時才接受', () => {
  assert.equal(normalizeBaseUrl('http://localhost:8765', 'localhost'), 'http://localhost:8765');
  assert.throws(() => normalizeBaseUrl('http://localhost:8765', 'wahaha232.github.io'));
});

test('buildRecordsQuery：排除已刪除、依時間新到舊、帶類型與起算時間', () => {
  const q = new URLSearchParams(buildRecordsQuery({ type: 'WEIGHT', since: 1000.9, limit: 50 }));
  assert.equal(q.get('deleted_at'), 'is.null');
  assert.equal(q.get('type'), 'eq.WEIGHT');
  assert.equal(q.get('measured_at'), 'gte.1000');
  assert.equal(q.get('order'), 'measured_at.desc,id.desc');
  assert.equal(q.get('limit'), '50');
  assert.ok(q.get('select').includes('photo_object'));
});

test('buildRecordsQuery：不指定類型與時間就不帶這兩個條件', () => {
  const q = new URLSearchParams(buildRecordsQuery());
  assert.equal(q.has('type'), false);
  assert.equal(q.has('measured_at'), false);
});

test('sessionNeedsRefresh：一分鐘內到期或沒有 session 就需要續期', () => {
  assert.equal(sessionNeedsRefresh(null, NOW), true);
  assert.equal(sessionNeedsRefresh({ expiresAt: NOW + 30000 }, NOW), true);
  assert.equal(sessionNeedsRefresh({ expiresAt: NOW + 120000 }, NOW), false);
});

test('formatValue：各類型的位數與單位', () => {
  assert.equal(formatValue({ type: 'WEIGHT', value: 70 }), '70.0 kg');
  assert.equal(formatValue({ type: 'BODY_FAT', value: 23 }), '23.0 %');
  assert.equal(formatValue({ type: 'BLOOD_GLUCOSE', value: 112.4 }), '112 mg/dL');
  assert.equal(formatValue({ type: 'BLOOD_PRESSURE', value: 120, value2: 80 }), '120/80 mmHg');
  assert.equal(formatValue({ type: 'UNKNOWN', value: 5 }), '5');
});

test('formatTime：固定用台北時間（UTC+8）', () => {
  assert.equal(formatTime(Date.UTC(2026, 9, 18, 22, 33)), '2026-10-19 06:33');
  assert.equal(formatTime(Date.UTC(2026, 11, 31, 16, 5)), '2027-01-01 00:05');
});

test('sourceLabel：已知來源翻成中文、未知原樣顯示', () => {
  assert.equal(sourceLabel('MIJIA'), '米家畫面');
  assert.equal(sourceLabel('OCR'), '拍照辨識');
  assert.equal(sourceLabel('FUTURE'), 'FUTURE');
  assert.equal(sourceLabel(undefined), '');
});

test('summarize：最新、筆數、平均、最低、最高', () => {
  assert.equal(summarize([]), null);
  const s = summarize([{ value: 70 }, { value: 72 }, { value: 68 }]);
  assert.equal(s.count, 3);
  assert.equal(s.latest.value, 70);
  assert.equal(s.min, 68);
  assert.equal(s.max, 72);
  assert.equal(s.avg, 70);
});

test('normalizeBaseUrl：127.0.0.1 與 localhost 都算本機開發', () => {
  assert.equal(normalizeBaseUrl('http://127.0.0.1:8791', '127.0.0.1'), 'http://127.0.0.1:8791');
  assert.equal(normalizeBaseUrl('http://localhost:8791', '127.0.0.1'), 'http://localhost:8791');
  assert.throws(() => normalizeBaseUrl('http://127.0.0.1:8791', 'wahaha232.github.io'));
  assert.throws(() => normalizeBaseUrl('http://127.0.0.2:8791', '127.0.0.2'));
});

test('summarize：血壓的舒張壓統計', () => {
  const s = summarize([{ value: 120, value2: 80 }, { value: 130, value2: 90 }, { value: 110, value2: null }]);
  assert.deepEqual(s.diastolic, { min: 80, max: 90, avg: 85 });
  assert.equal(summarize([{ value: 70 }]).diastolic, null);
});

// 假的分頁來源：總共 total 筆，依 offset/limit 回傳
const source = (total) => async (offset, limit) =>
  Array.from({ length: Math.max(0, Math.min(limit, total - offset)) }, (_, i) => offset + i);

for (const [total, truncated] of [[0, false], [999, false], [1000, false], [1001, false], [20000, false], [20001, false]]) {
  test(`fetchAllPages：${total} 筆（預設 50 頁上限）完整取回，不截斷`, async () => {
    const r = await fetchAllPages(source(total));
    assert.equal(r.rows.length, total);
    assert.equal(r.truncated, truncated);
    assert.equal(new Set(r.rows).size, total);
  });
}

test('fetchAllPages：超過上限標示截斷，剛好等於上限不算截斷', async () => {
  const exact = await fetchAllPages(source(5000), { pageSize: 1000, maxPages: 5 });
  assert.deepEqual([exact.rows.length, exact.truncated], [5000, false]);
  const over = await fetchAllPages(source(5001), { pageSize: 1000, maxPages: 5 });
  assert.deepEqual([over.rows.length, over.truncated], [5000, true]);
});

test('fetchAllPages：第一頁不滿就只請求一次', async () => {
  let calls = 0;
  await fetchAllPages(async (o, l) => { calls++; return source(10)(o, l); });
  assert.equal(calls, 1);
});

test('isSafePhotoPath：只接受 <uuid>/<uuid>.jpg', () => {
  const id = '123e4567-e89b-12d3-a456-426614174000';
  assert.equal(isSafePhotoPath(`${id}/${id}.jpg`), true);
  assert.equal(isSafePhotoPath(`../${id}.jpg`), false);
  assert.equal(isSafePhotoPath(`${id}/${id}.jpg?x=1`), false);
  assert.equal(isSafePhotoPath(`${id}/../${id}.jpg`), false);
  assert.equal(isSafePhotoPath(null), false);
});
