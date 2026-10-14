// 路線上色（速度／海拔）、自動亮點、路線投影的純邏輯測試。
// 執行：node --test gpstracker-web/tests/route-features.test.js   （Node 18+，無需安裝任何套件）
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const G = require("../gt-core.js");

// 合成路線：點格式 [lon, lat, alt, speed(km/h), elapsedSec]（與 App 上傳檔一致：第 4 欄就是 km/h）
function line(n, f) {
  const pts = [];
  for (let i = 0; i < n; i++) pts.push([121.5 + i * 0.0005, 25.0 + i * 0.0002, ...f(i), i * 5]);
  return pts;
}
const flatSlow = line(60, () => [0, 1.2]); // 沒有海拔(0 視為缺值)、慢速

test("valueSeries：速度直接用檔案的 km/h（不再乘 3.6）；異常高速不參與；沒資料回傳 null（不捏造）", () => {
  const pts = line(40, (i) => [10 + i, i === 20 ? 1800 : 18]); // 18 km/h；第 20 點 1800 km/h 是 GPS 異常
  const v = G.valueSeries(pts, "speed");
  assert.equal(v.length, 40);
  assert.ok(Math.abs(v[5] - 18) < 1e-9);
  assert.ok(Math.max(...v) < 25, "異常尖峰被排除，不會出現 1800 km/h");
  assert.equal(G.valueSeries(line(30, () => [0, 0]), "speed"), null, "全部速度為 0 → 沒有可用資料");
  assert.equal(G.valueSeries(flatSlow, "altitude"), null, "沒有海拔 → null");
  assert.equal(G.valueSeries([[121, 25, 5, 1, 0]], "speed"), null);
  assert.equal(G.valueSeries(flatSlow, "bogus"), null);
});

test("色階：端點顏色固定、全部是合法 #rrggbb；範圍用百分位，單一尖峰不會洗掉整條路線", () => {
  for (const mode of Object.keys(G.RAMPS)) {
    assert.equal(G.rampColor(mode, 0), G.RAMPS[mode][0]);
    assert.equal(G.rampColor(mode, 1), G.RAMPS[mode][G.RAMPS[mode].length - 1]);
    for (let t = -0.5; t <= 1.5; t += 0.07) assert.match(G.rampColor(mode, t), /^#[0-9a-f]{6}$/);
  }
  const vals = Array.from({ length: 100 }, (_, i) => 10 + (i % 10)); // 10..19
  vals[50] = 1000;                                                  // 單一尖峰
  const r = G.valueRange(vals);
  assert.ok(r.hi < 30, "第 95 百分位不受單一尖峰影響");
  const flat = G.valueRange([5, 5, 5, 5]);
  assert.ok(flat.hi > flat.lo, "數值全相同也不會除以 0");
});

test("colorSegments：連續同色合併、GPS 中斷段不上色、座標首尾相接", () => {
  const coords = Array.from({ length: 10 }, (_, i) => [121 + i * 0.001, 25]);
  const values = [1, 1, 1, 1, 9, 9, 9, 9, 9, 9];
  const flags = []; flags[6] = true;                                 // 邊 5→6 是中斷估算段
  const r = G.colorSegments(coords, values, flags, "speed", 8);
  assert.ok(r.segments.length >= 2);
  const colors = new Set(r.segments.map((s) => s.color));
  assert.ok(colors.size >= 2, "慢與快是不同顏色");
  let edges = 0;
  for (const s of r.segments) { assert.ok(s.coords.length >= 2); edges += s.coords.length - 1; }
  assert.equal(edges, coords.length - 1 - 1, "9 條邊扣掉 1 條中斷段 = 8 條");
  assert.equal(r.legend.length, 5);
  assert.ok(r.legend[0].value <= r.legend[4].value);
  assert.equal(r.lo, 1); assert.equal(r.hi, 9);
});

test("highlights：最高點、最大連續爬升、最快速度；資料不足就不列", () => {
  // 先上升 0→60 m（第 0~30 點），再下降到 10 m（第 60 點），最後平緩；速度在第 45 點最快
  const pts = line(80, (i) => {
    const alt = i <= 30 ? 20 + i * (40 / 30) : i <= 60 ? 60 - (i - 30) * (50 / 30) : 10 + (i - 60) * 0.1;
    return [alt, i === 45 ? 12 : 4];
  });
  const hl = G.highlights(pts);
  const by = Object.fromEntries(hl.map((h) => [h.key, h]));
  assert.ok(by.maxAlt && Math.abs(by.maxAlt.idx - 30) <= 3 && by.maxAlt.unit === "m" && by.maxAlt.value > 50);
  assert.ok(by.climb && by.climb.prefix === "+" && by.climb.value >= 30 && by.climb.from < by.climb.idx);
  assert.ok(by.maxSpeed && Math.abs(by.maxSpeed.idx - 45) <= 2 && by.maxSpeed.unit === "km/h");
  for (const h of hl) assert.ok(Number.isFinite(h.lon) && Number.isFinite(h.lat) && h.idx >= 0 && h.idx < pts.length);
  assert.deepEqual(hl.map((h) => h.idx), hl.map((h) => h.idx).slice().sort((a, b) => a - b), "依路線順序排列");
  assert.equal(G.highlights(flatSlow).some((h) => h.key === "maxAlt" || h.key === "climb"), false, "沒有海拔就不列高度亮點");
  assert.deepEqual(G.highlights([]), []);
  assert.deepEqual(G.highlights([[121, 25, 0, 0, 0], [121.1, 25, 0, 0, 5]]), []);
  // 平坦路線（起伏不到 10 m）不會硬湊出「爬升」
  assert.equal(G.highlights(line(60, (i) => [30 + (i % 3), 4])).some((h) => h.key === "climb"), false);
});

test("routeProject：保留原始索引、座標在畫布內；routePath 仍與投影一致", () => {
  const pts = line(500, (i) => [i, 3]);
  pts[10] = [NaN, 25, 0, 0, 0]; // 無效點會被略過，但索引仍指向原陣列
  const r = G.routeProject(pts, 400, 300, 10, 120);
  assert.ok(r.pts.length <= 120 && r.pts.length >= 2);
  for (const q of r.pts) {
    assert.ok(q.x >= 10 - 1e-6 && q.x <= 390 + 1e-6 && q.y >= 10 - 1e-6 && q.y <= 290 + 1e-6);
    assert.ok(Number.isFinite(pts[q.i][0]), "索引不會指到被略過的無效點");
  }
  assert.ok(r.pts.every((q, i) => i === 0 || q.i > r.pts[i - 1].i), "索引遞增");
  const d = G.routePath(pts, 400, 300, 10, 120);
  assert.ok(d.d.startsWith("M") && !/NaN/.test(d.d));
  assert.equal(G.routeProject([[121, 25]], 100, 100), null);
});

test("實際軌跡檔：上色與亮點都能處理，結果合理", () => {
  const dir = path.join(__dirname, "..", "data");
  const man = JSON.parse(fs.readFileSync(path.join(dir, "manifest.json"), "utf8"));
  let checked = 0;
  for (const e of man.tracks) {
    const t = JSON.parse(fs.readFileSync(path.join(dir, e.file || e.trackId + ".json"), "utf8"));
    const pts = t.points;
    for (const mode of ["speed", "altitude"]) {
      const v = G.valueSeries(pts, mode);
      if (!v) continue;
      assert.equal(v.length, pts.length);
      assert.ok(v.every(Number.isFinite), mode);
      const coords = pts.map((p) => [p[0], p[1]]);
      const r = G.colorSegments(coords, v, null, mode);
      assert.ok(r.segments.length >= 1 && r.hi > r.lo);
      assert.ok(r.segments.every((s) => /^#[0-9a-f]{6}$/.test(s.color)));
    }
    for (const h of G.highlights(pts)) { assert.ok(h.idx < pts.length && Number.isFinite(h.value)); checked++; }
  }
  assert.ok(man.tracks.length > 0);
});
