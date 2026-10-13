// 年度／月度回顧的純邏輯測試。執行：node --test gpstracker-web/tests/recap.test.js
const test = require("node:test");
const assert = require("node:assert/strict");
const R = require("../gt-recap.js");

// 本地時間建立活動；distKm、持續分鐘、最高速度(m/s)
let seq = 0;
function trk(y, m, d, distKm, over) {
  return Object.assign({ trackId: "t" + (++seq), startTimeMs: new Date(y, m - 1, d, 9, 0).getTime(), distanceMeters: distKm * 1000,
    durationMs: 3600e3, totalAscentMeters: 10, stepCount: 100, maxSpeedMps: 5, dominantMode: "WALKING" }, over || {});
}
const tracks = [
  trk(2025, 12, 31, 5), trk(2025, 6, 1, 20),
  trk(2026, 1, 1, 10), trk(2026, 1, 2, 12), trk(2026, 1, 3, 8), trk(2026, 1, 10, 30, { dominantMode: "BICYCLE", maxSpeedMps: 9 }),
  trk(2026, 3, 5, 15, { maxSpeedMps: 500 }), // 500 m/s = 1800 km/h：GPS 異常，不能列為「最快」
];

test("years：有活動的年份，新到舊", () => assert.deepEqual(R.years(tracks), [2026, 2025]));

test("整年：總量、活動天數、12 個月分組、與上一年比較", () => {
  const r = R.compute(tracks, { year: 2026, month: 0 });
  assert.equal(r.totals.count, 5);
  assert.equal(r.totals.km, 75);
  assert.equal(r.totals.activeDays, 5);
  assert.equal(r.buckets.length, 12);
  assert.equal(r.buckets[0].km, 60); assert.equal(r.buckets[0].count, 4);
  assert.equal(r.buckets[2].km, 15);
  assert.equal(r.buckets.reduce((s, b) => s + b.km, 0), r.totals.km, "分組加總 = 總量");
  assert.deepEqual(r.previous.period, { year: 2025, month: 0 });
  assert.equal(r.previous.totals.km, 25);
  const d = R.delta(r.totals.km, r.previous.totals.km);
  assert.equal(d.diff, 50); assert.equal(d.pct, 200);
});

test("單月：以每天分組；上一期是上個月（1 月 → 去年 12 月）", () => {
  const r = R.compute(tracks, { year: 2026, month: 1 });
  assert.equal(r.buckets.length, 31);
  assert.equal(r.buckets[0].km, 10); assert.equal(r.buckets[9].km, 30);
  assert.deepEqual(r.previous.period, { year: 2025, month: 12 });
  assert.equal(r.previous.totals.km, 5);
  assert.equal(R.compute(tracks, { year: 2026, month: 2 }).buckets.length, 28);
  assert.equal(R.compute(tracks, { year: 2024, month: 2 }).buckets.length, 29, "閏年 2 月");
  assert.deepEqual(R.previousPeriod(2026, 7), { year: 2026, month: 6 });
});

test("上一期沒有活動時不算百分比（不顯示無限大）；沒有活動的期間回傳 0 與空資料", () => {
  assert.equal(R.delta(10, 0).pct, null);
  const empty = R.compute(tracks, { year: 2020, month: 0 });
  assert.equal(empty.totals.count, 0); assert.equal(empty.totals.km, 0);
  assert.equal(empty.longest, null); assert.equal(empty.fastest, null); assert.equal(empty.busiestDay, null);
  assert.deepEqual(empty.top, []); assert.deepEqual(empty.modes, []); assert.equal(empty.streak, 0);
  assert.deepEqual(R.compute([], { year: 2026, month: 0 }).totals, { count: 0, km: 0, durMs: 0, ascent: 0, steps: 0, activeDays: 0 });
});

test("亮點：最長一趟、最快速度（排除異常值）、里程最多的一天、最長連續天數、前 5 名", () => {
  const r = R.compute(tracks, { year: 2026, month: 0 });
  assert.equal(r.longest.distanceMeters, 30000);
  assert.equal(r.fastest.maxSpeedMps, 9, "500 m/s 的異常值被排除");
  assert.equal(r.busiestDay.km, 30); assert.equal(r.busiestDay.day, "2026-01-10");
  assert.equal(r.streak, 3, "1/1、1/2、1/3 連續");
  assert.deepEqual(r.top.map((t) => t.distanceMeters / 1000), [30, 15, 12, 10, 8]);
  assert.equal(R.longestStreak([trk(2026, 2, 28, 1), trk(2026, 3, 1, 1)]), 2, "跨月也算連續（2026 非閏年）");
  assert.equal(R.longestStreak([trk(2026, 5, 1, 1), trk(2026, 5, 1, 2)]), 1, "同一天多筆只算一天");
});

test("移動方式占比：套用 modeOf（紀錄管理改過的方式生效），百分比加總為 100", () => {
  const r = R.compute(tracks, { year: 2026, month: 0, modeOf: (t) => (t.dominantMode === "BICYCLE" ? "TRAIN" : t.dominantMode) });
  assert.deepEqual(r.modes.map((m) => m.mode), ["WALKING", "TRAIN"], "依里程由大到小：走路 45 km、改成火車的 30 km");
  assert.equal(Math.round(r.modes.reduce((s, m) => s + m.pct, 0)), 100);
  assert.equal(r.modes[0].km, 45); assert.equal(r.modes[1].km, 30);
});
