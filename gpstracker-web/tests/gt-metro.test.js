// 執行：node --test gpstracker-web/tests/gt-metro.test.js
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const M = require("../gt-metro.js");

const DATA = path.join(__dirname, "..", "data");
const metro = JSON.parse(fs.readFileSync(path.join(DATA, "metro.json"), "utf8"));
const g = M.buildGraph(metro);
const byName = (n) => metro.stations.find((s) => s.n === n);

test("metro.json：有站點、路線、邊，且每條邊的兩端都存在", () => {
  assert.ok(metro.stations.length > 100 && metro.edges.length > 100);
  assert.ok(metro.source.includes("OpenStreetMap"));
  const ids = new Set(metro.stations.map((s) => s.id));
  for (const e of metro.edges) { assert.ok(ids.has(e.a) && ids.has(e.b)); assert.ok(e.g.length >= 2 && e.m > 0); }
});

test("最近站點：頂埔站附近 → 頂埔；太遠 → null", () => {
  const d = byName("頂埔");
  assert.equal(M.nearestStation(g, d.x + 0.0005, d.y, 800).station.n, "頂埔");
  assert.equal(M.nearestStation(g, 120.0, 23.0, 800), null);
});

test("路線：新埔 → 頂埔 沿藍線（含中間 6 站），長度合理", () => {
  const r = M.route(g, byName("新埔").id, byName("頂埔").id);
  assert.deepEqual(r.lines, ["BL"]);
  assert.deepEqual(r.stations.map((id) => g.stations[id].n), ["新埔", "板橋", "府中", "亞東醫院", "海山", "土城", "永寧", "頂埔"]);
  assert.ok(r.lengthM > 8500 && r.lengthM < 10500, "length " + r.lengthM);
});

test("路線：淡水 → 頂埔 需要轉乘（紅線＋藍線）", () => {
  const r = M.route(g, byName("淡水").id, byName("頂埔").id);
  assert.ok(r, "應找得到路徑");
  assert.ok(r.lines.includes("R") && r.lines.includes("BL"), r.lines.join());
  assert.ok(r.lengthM > 20000 && r.lengthM < 40000, "length " + r.lengthM);
});

test("路線：同一站、不存在的站 → null", () => {
  assert.equal(M.route(g, byName("頂埔").id, byName("頂埔").id), null);
  assert.equal(M.route(g, 999999, byName("頂埔").id), null);
});

test("主要捷運線互相連通（不會有孤立的路網）", () => {
  const main = metro.stations.filter((s) => ["台北車站", "淡水", "南港展覽館", "松山", "動物園", "南勢角", "頂埔", "象山", "新店"].includes(s.n));
  assert.ok(main.length >= 8);
  for (const s of main) assert.ok(M.route(g, main[0].id, s.id) || s.id === main[0].id, "到不了 " + s.n);
});

test("實際軌跡：偵測到 1 段 GPS 中斷（約 20 分鐘、8.5 km 直線），其餘不誤判", () => {
  const t = JSON.parse(fs.readFileSync(path.join(DATA, "40cd1987-3d41-4406-a4bf-c9bcec40111d.json"), "utf8"));
  const gaps = M.findGaps(t.points);
  assert.equal(gaps.length, 1);
  assert.ok(gaps[0].dtSec > 1200 && gaps[0].dtSec < 1250);
  assert.ok(gaps[0].straightM > 8400 && gaps[0].straightM < 8600);
});

test("實際軌跡：中斷段被辨識為 新埔 → 頂埔（藍線），估算路徑約 9.3 km、平均速度合理", () => {
  const t = JSON.parse(fs.readFileSync(path.join(DATA, "40cd1987-3d41-4406-a4bf-c9bcec40111d.json"), "utf8"));
  const [e] = M.annotate(t.points, metro);
  assert.equal(e.kind, "metro");
  assert.equal(e.fromName, "新埔"); assert.equal(e.toName, "頂埔");
  assert.deepEqual(e.lines.map((l) => l.ref), ["BL"]);
  assert.ok(e.lengthM > 9000 && e.lengthM < 10000, "len " + e.lengthM);
  assert.ok(e.speedKmh > 20 && e.speedKmh < 40, "speed " + e.speedKmh);
  assert.deepEqual(e.coords[0], e.from); assert.deepEqual(e.coords[e.coords.length - 1], e.to);
});

test("沒有捷運資料時：中斷段仍被標出，但為 unknown（不亂補）", () => {
  const t = JSON.parse(fs.readFileSync(path.join(DATA, "40cd1987-3d41-4406-a4bf-c9bcec40111d.json"), "utf8"));
  const [e] = M.annotate(t.points, null);
  assert.equal(e.kind, "unknown"); assert.ok(e.dtSec > 1200);
});

test("兩端不在站點附近 → unknown；同一站進出 → unknown；速度不合理 → unknown", () => {
  const far = { i: 0, from: [121.0, 23.5], to: [121.05, 23.55], dtSec: 1200, straightM: 7500 };
  assert.equal(M.estimate(g, far).kind, "unknown");
  const d = byName("頂埔"), u = byName("永寧");
  const same = { i: 0, from: [d.x, d.y], to: [d.x + 0.001, d.y + 0.001], dtSec: 1200, straightM: 1500 };
  assert.equal(M.estimate(g, same).kind, "unknown");
  const tooFast = { i: 0, from: [d.x, d.y], to: [u.x, u.y], dtSec: 20, straightM: M.hav([d.x, d.y], [u.x, u.y]) };
  assert.equal(M.estimate(g, tooFast).kind, "unknown");
});

test("一般騎乘（密集取樣）不會被當成中斷", () => {
  const pts = []; for (let i = 0; i < 300; i++) pts.push([121 + i * 0.0003, 25, 10, 20, i * 5]);
  assert.deepEqual(M.findGaps(pts), []);
  // 長時間停留（時間差大但幾乎沒移動）也不是中斷
  assert.deepEqual(M.findGaps([[121, 25, 0, 0, 0], [121.0001, 25, 0, 0, 600]]), []);
});

test("插入估算點：時間與座標單調、端點不變、旗標正確", () => {
  const t = JSON.parse(fs.readFileSync(path.join(DATA, "40cd1987-3d41-4406-a4bf-c9bcec40111d.json"), "utf8"));
  const gaps = M.annotate(t.points, metro);
  const r = M.insertEstimated(t.points, gaps);
  assert.ok(r.points.length > t.points.length);
  assert.equal(r.gapEdge.length, r.points.length);
  for (let i = 1; i < r.points.length; i++) assert.ok(r.points[i][4] >= r.points[i - 1][4], "時間倒退 @" + i);
  const gi = gaps[0].i;
  assert.deepEqual(r.points[gi], t.points[gi]);                       // 中斷起點不變
  const flagged = r.gapEdge.reduce((n, f) => n + (f ? 1 : 0), 0);
  assert.equal(flagged, r.points.length - t.points.length + 1);      // 插入點 + 最後一條邊
  assert.equal(r.gapEdge.slice(0, gi + 1).some(Boolean), false);     // 中斷之前沒有旗標
  // 沿路徑累積距離約等於估算長度
  let len = 0, from = gi, to = gi + (r.points.length - t.points.length) + 1;
  for (let i = from + 1; i <= to; i++) len += M.hav(r.points[i - 1], r.points[i]);
  assert.ok(Math.abs(len - gaps[0].lengthM) < 50, `${len} vs ${gaps[0].lengthM}`);
});

test("gapFlags：沒有插入時也能標記中斷的那條邊", () => {
  const f = M.gapFlags([[0, 0], [0, 0], [0, 0], [0, 0]], [{ i: 1 }]);
  assert.deepEqual(f, [false, false, true, false]);
});
