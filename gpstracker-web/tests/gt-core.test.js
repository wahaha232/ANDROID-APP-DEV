// 執行：node --test gpstracker-web/tests   （Node 18+，無需安裝任何套件）
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const G = require("../gt-core.js");
const FX = require("./fixtures");

const T0 = new Date(2026, 9, 11, 14, 8).getTime(); // 本地時間 2026/10/11 14:08
const FX_PTS = FX.track("fx-walk").points.slice(0, 2); // 共用 fixture 的前兩點（假資料）

function track(over) {
  return Object.assign({
    trackId: "t1", startTimeMs: T0, durationMs: 2613552, distanceMeters: 14211.09, avgSpeedMps: 3.975, maxSpeedMps: 10,
    stepCount: 1521, stepSource: "GOOGLE_FIT", dominantMode: "BICYCLE", totalAscentMeters: 145.4, totalDescentMeters: 104.1,
    gpsPointCount: 1082, photoCount: 3, maxAltitudeMeters: 57.2, points: FX_PTS
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

test("可編輯的移動方式：meta.mode 優先，沒有就用 App 判斷；篩選、搜尋、標題都跟著變", () => {
  const e = track({ trackId: "m1", dominantMode: "BICYCLE" });
  const meta = { m1: { mode: "TRAIN" } };
  assert.equal(G.modeOf(e, {}), "BICYCLE");
  assert.equal(G.modeOf(e, meta), "TRAIN");
  assert.equal(G.modeOf(e, { m1: { mode: "" } }), "BICYCLE");
  assert.equal(G.modeOf({ trackId: "z" }, {}), "UNKNOWN");
  assert.equal(G.modeOverridden(e, meta), true);
  assert.equal(G.modeOverridden(e, { m1: { mode: "BICYCLE" } }), false);
  assert.equal(G.activityTitle(e, meta), "2026/10/11 火車／捷運");
  assert.equal(G.filterTracks([e], { mode: "TRAIN", meta }).length, 1);
  assert.equal(G.filterTracks([e], { mode: "BICYCLE", meta }).length, 0);
  assert.equal(G.filterTracks([e], { q: "捷運", meta }).length, 1);
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

test("路線縮圖底圖：圖磚覆蓋整個畫布、數量有上限、路線點落在對應圖磚內", () => {
  const pts = [];
  for (let i = 0; i <= 60; i++) pts.push([121.52 + i * 0.002, 25.03 + Math.sin(i / 9) * 0.008, 0]);
  const W = 320, H = 180;
  const r = G.routePath(pts, W, H, 10);
  assert.ok(r.tiles.length >= 1 && r.tiles.length <= 20, "圖磚數量：" + r.tiles.length);
  const z = r.tiles[0].z;
  assert.ok(r.tiles.every((t) => t.z === z && t.pw > 0 && t.ph > 0), "同一縮放層級、尺寸為正");
  // 覆蓋：畫布四個角都落在某張圖磚內
  for (const [x, y] of [[0, 0], [W, 0], [0, H], [W, H], [W / 2, H / 2]]) {
    assert.ok(r.tiles.some((t) => x >= t.px - 0.01 && x <= t.px + t.pw + 0.01 && y >= t.py - 0.01 && y <= t.py + t.ph + 0.01), "未覆蓋：" + x + "," + y);
  }
  // 對位：起點的畫布座標，要落在「依經緯度算出的那張圖磚」的矩形內
  const n = 2 ** z, lon = pts[0][0], lat = pts[0][1];
  const tx = Math.floor((lon + 180) / 360 * n);
  const ty = Math.floor((1 - Math.log(Math.tan(lat * Math.PI / 180) + 1 / Math.cos(lat * Math.PI / 180)) / Math.PI) / 2 * n);
  const t = r.tiles.find((q) => q.x === tx && q.y === ty);
  assert.ok(t, "找不到起點所在的圖磚");
  assert.ok(r.start[0] >= t.px - 0.5 && r.start[0] <= t.px + t.pw + 0.5 && r.start[1] >= t.py - 0.5 && r.start[1] <= t.py + t.ph + 0.5, "起點未落在其圖磚內");
  // 範圍很大的路線也不會要求過多圖磚
  const big = G.routePath([[100, 10], [140, 40]], W, H, 10);
  assert.ok(big.tiles.length <= 20);
  assert.deepEqual(G.routeTiles(null, W, H), []);
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
  const t = track({ points: FX_PTS });
  const x = G.gpx(t);
  assert.equal((x.match(/<trkpt /g) || []).length, 2);
  assert.ok(x.includes(`lat="${FX_PTS[0][1]}" lon="${FX_PTS[0][0]}"`));
  assert.ok(x.includes(new Date(T0 + FX_PTS[1][4] * 1000).toISOString()));
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

test("共用 fixture：manifest、meta 與軌跡檔可被新介面的函式處理", () => {
  const man = FX.manifest(), meta = FX.meta().tracks;
  assert.deepEqual(man.tracks.map(e => e.trackId), ["fx-bike", "fx-walk"]);
  for (const e of man.tracks) {
    const t = FX.track(e.trackId);
    assert.equal(t.trackId, e.trackId);
    assert.equal(t.gpsPointCount, t.points.length);
    assert.ok(G.routePath(t.points, 100, 60));
    assert.ok(G.trackFields(t).length > 0);
    assert.ok(Math.abs(G.splits(t.points).reduce((a, s) => a + s.distanceM, 0) - t.distanceMeters) < 1, "分段總距離 ≈ 整趟距離");
  }
  assert.equal(G.activityTitle(man.tracks[0], meta), "測試用單車");
  assert.equal(G.modeOf(man.tracks[1], meta), "RUNNING", "meta.mode 覆寫 App 判斷");
  assert.equal(G.filterTracks(man.tracks, { q: "假資料", meta }).length, 1);
});

test("IMP-05：各頁模式 emoji／名稱／分組改由 GTCore 提供，顯示結果不變", () => {
  const modes = ["WALKING", "RUNNING", "BICYCLE", "CYCLING", "VEHICLE", "DRIVING", "CAR", "MOTORCYCLE", "TRAIN", "BUS", "BOAT", "STATIONARY", "PUBLIC_TRANSPORT", "AIRPLANE", "UNKNOWN", "XYZ", undefined];
  const name = { WALKING: "步行", RUNNING: "跑步", BICYCLE: "單車", CYCLING: "單車", VEHICLE: "車輛", DRIVING: "開車", CAR: "開車", MOTORCYCLE: "機車", TRAIN: "火車／捷運", BUS: "公車", BOAT: "船", STATIONARY: "靜止", PUBLIC_TRANSPORT: "大眾運輸", AIRPLANE: "飛機", UNKNOWN: "未知" };
  const group = { WALKING: "步行", RUNNING: "跑步", BICYCLE: "單車", CYCLING: "單車", MOTORCYCLE: "機車", VEHICLE: "汽車", DRIVING: "汽車", CAR: "汽車", TRAIN: "大眾運輸", BUS: "大眾運輸", BOAT: "船", STATIONARY: "其他/未知", PUBLIC_TRANSPORT: "大眾運輸", AIRPLANE: "飛機", UNKNOWN: "未知" };
  for (const m of modes) {
    assert.equal(G.modeName(m), name[m] || m || "未知");
    assert.equal(G.modeNameShort(m), m === "TRAIN" ? "火車" : (name[m] || m || "未知"));
    assert.equal(G.modeGroup(m), group[m] || "其他/未知");
  }
  for (const f of ["challenges.js", "records.js"]) {
    const src = fs.readFileSync(path.join(__dirname, "..", f), "utf8");
    assert.ok(!/var MODE_(EMOJI|NAME|GROUP)\b/.test(src), f + " 不應再自帶模式表");
    assert.ok(/GTCore\.modeEmoji/.test(src), f + " 應改用 GTCore");
  }
  for (const h of ["challenges.html", "records.html"]) {
    const html = fs.readFileSync(path.join(__dirname, "..", h), "utf8");
    const i = html.indexOf("gt-core.js"), j = html.indexOf('src="' + h.replace(".html", ".js") + "?");
    assert.ok(i >= 0 && i < j, h + " 須先載入 gt-core.js");
  }
});
