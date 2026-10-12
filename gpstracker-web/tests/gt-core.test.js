// 執行：node --test gpstracker-web/tests   （Node 18+，無需安裝任何套件）
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const G = require("../gt-core.js");

const T0 = new Date(2026, 9, 11, 14, 8).getTime(); // 本地時間 2026/10/11 14:08

function track(over) {
  return Object.assign({
    trackId: "t1", startTimeMs: T0, durationMs: 2613552, distanceMeters: 14211.09, avgSpeedMps: 3.975, maxSpeedMps: 10,
    stepCount: 1521, stepSource: "GOOGLE_FIT", dominantMode: "BICYCLE", totalAscentMeters: 145.4, totalDescentMeters: 104.1,
    gpsPointCount: 1082, photoCount: 3, maxAltitudeMeters: 57.2, points: [[121.45, 25.05, 21.8, 0, 0], [121.46, 25.06, 30, 3, 60]]
  }, over || {});
}

test("格式化：日期、時間長度、時鐘", () => {
  assert.equal(G.fmtDay(T0), "2026/10/11");
  assert.equal(G.fmtDate(T0), "2026/10/11 14:08");
  assert.equal(G.fmtDur(2613552), "43m");
  assert.equal(G.fmtDur(3 * 3600e3 + 5 * 60e3), "3h 5m");
  assert.equal(G.fmtClock(75), "1:15");
  assert.equal(G.fmtClock(3725), "1:02:05");
  assert.equal(G.fmtClock(-5), "0:00");
  assert.equal(G.fmtKm(14211.09), "14.21");
  assert.equal(G.fmtKm(undefined), "0.00");
});

test("活動名稱：meta 標題優先，否則『日期 + 類型』", () => {
  const e = track();
  assert.equal(G.activityTitle(e, {}), "2026/10/11 單車");
  assert.equal(G.activityTitle(e, { t1: { title: "淡水河岸" } }), "淡水河岸");
  assert.equal(G.activityTitle(e, { t1: { title: "" } }), "2026/10/11 單車");
});

test("搜尋與模式篩選（含 meta 標題/備註）", () => {
  const a = track({ trackId: "a" }), b = track({ trackId: "b", dominantMode: "WALKING", startTimeMs: T0 - 86400e3 });
  const meta = { b: { notes: "河濱公園晨走" } };
  assert.equal(G.filterTracks([a, b], { mode: "WALKING" }).length, 1);
  assert.equal(G.filterTracks([a, b], { q: "單車" }).map(t => t.trackId).join(), "a");
  assert.equal(G.filterTracks([a, b], { q: "河濱", meta }).map(t => t.trackId).join(), "b");
  assert.equal(G.filterTracks([a, b], { q: "2026/10/10" }).map(t => t.trackId).join(), "b");
  assert.equal(G.filterTracks([a, b], { q: "不存在" }).length, 0);
  assert.equal(G.filterTracks([a, b], {}).length, 2);
});

test("排序不改動原陣列", () => {
  const a = track({ trackId: "a", distanceMeters: 5000 }), b = track({ trackId: "b", distanceMeters: 9000, startTimeMs: T0 + 1 });
  const src = [a, b];
  assert.deepEqual(G.sortTracks(src, "dist_desc").map(t => t.trackId), ["b", "a"]);
  assert.deepEqual(G.sortTracks(src, "date_asc").map(t => t.trackId), ["a", "b"]);
  assert.deepEqual(G.sortTracks(src, "date_desc").map(t => t.trackId), ["b", "a"]);
  assert.deepEqual(src.map(t => t.trackId), ["a", "b"]);
  assert.equal(G.sortTracks(src, "未知鍵").length, 2);
});

test("依月份分組", () => {
  const list = [track({ trackId: "a" }), track({ trackId: "b", startTimeMs: new Date(2026, 9, 1).getTime() }),
    track({ trackId: "c", startTimeMs: new Date(2026, 8, 30).getTime() })];
  const g = G.groupByMonth(list);
  assert.deepEqual(g.map(x => [x.key, x.items.length]), [["2026-10", 2], ["2026-09", 1]]);
  assert.equal(g[0].label, "2026 年 10 月");
  assert.deepEqual(G.groupByMonth([]), []);
});

test("路線縮圖：點數不足回傳 null；範圍在畫布內；保持比例", () => {
  assert.equal(G.routePath([], 100, 60), null);
  assert.equal(G.routePath([[121, 25, 0]], 100, 60), null);
  const pts = [];
  for (let i = 0; i <= 50; i++) pts.push([121 + i * 0.001, 25 + Math.sin(i / 8) * 0.004, 0]);
  const r = G.routePath(pts, 100, 60, 6);
  assert.ok(r.d.startsWith("M"));
  for (const m of r.d.matchAll(/[ML]([\d.]+) ([\d.]+)/g)) {
    assert.ok(+m[1] >= 5.9 && +m[1] <= 94.1, "x 超出：" + m[1]);
    assert.ok(+m[2] >= 5.9 && +m[2] <= 54.1, "y 超出：" + m[2]);
  }
  // 純東西向的線：高度應收斂在畫布中線（不會被拉伸）
  const flat = G.routePath([[121, 25], [121.01, 25]], 100, 60, 6);
  assert.equal(flat.start[1], flat.end[1]);
  // 退化（所有點相同）不應產生 NaN
  const same = G.routePath([[121, 25], [121, 25]], 100, 60);
  assert.ok(!/NaN/.test(same.d));
});

test("降採樣保留首尾", () => {
  const a = Array.from({ length: 1000 }, (_, i) => i);
  const d = G.downsample(a, 50);
  assert.equal(d.length, 50);
  assert.equal(d[0], 0);
  assert.equal(d[49], 999);
  assert.equal(G.downsample([1, 2], 50).length, 2);
});

test("可顯示欄位：只列實際有資料的欄位", () => {
  const keys = (t) => G.trackFields(t).map(f => f.key);
  assert.deepEqual(keys(track()), ["distance", "duration", "avgSpeed", "maxSpeed", "steps", "ascent", "descent", "maxAlt", "points", "photos"]);
  const bare = { trackId: "x", startTimeMs: T0, distanceMeters: 800 };
  assert.deepEqual(keys(bare), ["distance"]);
  const steps = G.trackFields(track()).find(f => f.key === "steps");
  assert.equal(steps.label, "步數（Google Fit）");
  assert.equal(steps.value, (1521).toLocaleString("zh-Hant"));
  assert.deepEqual(keys({ trackId: "y", startTimeMs: T0 }), []);
  // 沒有海拔資料就不顯示最高海拔
  assert.ok(!keys(track({ points: [[1, 1, 0, 0, 0], [1, 1, 0, 0, 1]] })).includes("maxAlt"));
});

test("海拔資料判斷", () => {
  assert.equal(G.hasAltitude(null), false);
  assert.equal(G.hasAltitude([[0, 0, 0], [0, 0, 0]]), false);
  assert.equal(G.hasAltitude([[0, 0, 5], [0, 0, 5]]), false); // 沒有起伏
  assert.equal(G.hasAltitude([[0, 0, 5], [0, 0, 9]]), true);
});

test("海拔序列：0 視為缺值並以前一個有效值補上", () => {
  assert.deepEqual(G.altitudeSeries([[0, 0, 0], [0, 0, 5], [0, 0, 0], [0, 0, 7.5]]), [5, 5, 5, 7.5]);
  assert.deepEqual(G.altitudeSeries([[0, 0, -3], [0, 0, null]]), [-3, -3]);
  assert.deepEqual(G.altitudeSeries(null), []);
  assert.ok(G.MODE_COLOR.BICYCLE && G.MODE_COLOR.WALKING);
});

test("移動方式占比（segments 為 [模式, 起秒, 迄秒]）", () => {
  const b = G.modeBreakdown([["WALKING", 0, 60], ["BICYCLE", 60, 360], ["WALKING", 360, 420], ["X", 5, 5]]);
  assert.deepEqual(b.map(x => [x.mode, x.sec]), [["BICYCLE", 300], ["WALKING", 120]]);
  assert.ok(Math.abs(b.reduce((s, x) => s + x.pct, 0) - 100) < 1e-9);
  assert.deepEqual(G.modeBreakdown(null), []);
});

test("GPX：點數、座標順序、時間與跳脫", () => {
  const t = track({ points: [[121.45, 25.05, 21.8, 0, 0], [121.46, 25.06, 30, 3, 60]] });
  const x = G.gpx(t);
  assert.equal((x.match(/<trkpt /g) || []).length, 2);
  assert.ok(x.includes('lat="25.05" lon="121.45"'));
  assert.ok(x.includes(new Date(T0 + 60000).toISOString()));
  assert.ok(x.startsWith('<?xml version="1.0"'));
  assert.equal(G.escapeHtml('<a href="x">&\''), "&lt;a href=&quot;x&quot;&gt;&amp;&#39;");
});

test("播放進度對應經過時間（依累計距離內插）", () => {
  const pts = [[0, 0, 0, 0, 0], [0, 0, 0, 0, 100], [0, 0, 0, 0, 300]];
  const cum = [0, 0.25, 1];
  assert.equal(G.elapsedAt(pts, cum, 0), 0);
  assert.equal(G.elapsedAt(pts, cum, 0.125), 50);
  assert.equal(G.elapsedAt(pts, cum, 0.25), 100);
  assert.equal(G.elapsedAt(pts, cum, 1), 300);
  assert.equal(G.elapsedAt([[0, 0]], [0], 0.5), 0);
});

test("實際資料檔：線上 manifest 與軌跡檔可被新介面的函式處理", () => {
  const dir = path.join(__dirname, "..", "data");
  const man = JSON.parse(fs.readFileSync(path.join(dir, "manifest.json"), "utf8"));
  assert.ok(Array.isArray(man.tracks));
  for (const e of man.tracks) {
    assert.ok(G.activityTitle(e, {}).length > 0);
    const t = JSON.parse(fs.readFileSync(path.join(dir, e.file || e.trackId + ".json"), "utf8"));
    assert.equal(t.trackId, e.trackId);
    assert.ok(G.routePath(t.points, 100, 60), "路線縮圖應可產生");
    assert.ok(G.trackFields(t).length > 0);
    // segments 的最大結束秒不應超過整趟長度太多（驗證『秒』的假設）
    const spanSec = (t.endTimeMs - t.startTimeMs) / 1000;
    for (const s of t.segments || []) assert.ok(s[2] <= spanSec + 2, "segments 結束秒超出整趟長度");
  }
});
