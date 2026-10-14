// 每公里分段與時間分析的純邏輯測試（與 App 的 SplitsAnalysisTest 同樣的情境）。
// 執行：node --test gpstracker-web/tests/splits.test.js
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const G = require("../gt-core.js");

const MPD = 111194.93; // 1 度緯度的公尺數（地球半徑 6,371,000 m）
// 點格式 [lon, lat, alt, speed, elapsedSec]；沿緯度方向每秒前進 mps 公尺
function walk(seconds, mps, startSec = 0, startLat = 25) {
  return Array.from({ length: seconds + 1 }, (_, s) => [121.5, startLat + (s * mps) / MPD, 0, mps * 3.6, startSec + s]);
}

test("等速：每公里秒數相同，最後的餘段標為 partial", () => {
  const s = G.splits(walk(650, 4)); // 2600 m
  assert.equal(s.length, 3);
  assert.ok(Math.abs(s[0].seconds - 250) < 0.5 && Math.abs(s[1].seconds - 250) < 0.5);
  assert.equal(s[0].distanceM, 1000);
  assert.equal(s[2].partial, true);
  assert.ok(Math.abs(s[2].distanceM - 600) < 2);
  assert.ok(Math.abs(s[0].avgKmh - 14.4) < 0.1);
  assert.ok(Math.abs(s[0].paceSec - 250) < 0.5);
});

test("太短的餘段（< 100 m）不列；點太少回傳空", () => {
  assert.equal(G.splits(walk(260, 4)).some((s) => s.partial), false);
  assert.deepEqual(G.splits([]), []);
  assert.deepEqual(G.splits(undefined), []);
  assert.deepEqual(G.splits(walk(1, 4)), []);
});

test("超過 120 秒的間隔（暫停／GPS 遺失／搭捷運）不計入，不會產生不可能的分段", () => {
  const a = walk(125, 4);                                            // 500 m
  const jumpLat = a[a.length - 1][1] + 3000 / MPD;                   // 中斷期間跳了 3 km
  const b = Array.from({ length: 151 }, (_, s) => [121.5, jumpLat + (s * 4) / MPD, 0, 14.4, 125 + 600 + s]);
  const s = G.splits(a.concat(b));
  assert.equal(s.filter((x) => !x.partial).length, 1);
  assert.ok(Math.abs(s.find((x) => !x.partial).seconds - 250) < 2);
  assert.ok(s.reduce((t, x) => t + x.distanceM, 0) < 1200, "跳躍的 3 km 沒有被算進去");
});

test("最快／最慢：完整公里至少 3 個才比較", () => {
  const fast = walk(125, 8), mid = walk(250, 4, 125, fast[fast.length - 1][1]), slow = walk(505, 2, 375, mid[mid.length - 1][1]);
  const s = G.splits(fast.concat(mid.slice(1), slow.slice(1)));
  assert.equal(s.filter((x) => !x.partial).length, 3);
  const e = G.fastestSlowest(s);
  assert.equal(e.fastest.index, 1);
  assert.equal(e.slowest.index, 3);
  assert.equal(G.fastestSlowest(s.slice(0, 2)), null);
  assert.equal(G.fastestSlowest([]), null);
});

test("移動時間：站著不動與長間隔不算", () => {
  const a = walk(100, 3);
  const still = Array.from({ length: 60 }, (_, s) => [121.5, a[a.length - 1][1], 0, 0, 101 + s]);
  const c = Array.from({ length: 41 }, (_, s) => [121.5, a[a.length - 1][1] + (s * 3) / MPD, 0, 10.8, 160 + s]);
  assert.ok(Math.abs(G.movingSeconds(a.concat(still, c)) - 140) < 2);
  assert.equal(G.movingSeconds([a[0], [a[1][0], a[1][1], 0, 0, 100]]), 0, "間隔超過 30 秒的那段不算");
  assert.equal(G.movingSeconds([]), 0);
});

test("時間分析：經過／活動／移動／暫停／停等，不會出現負數", () => {
  const b = G.timeBreakdown({ startTimeMs: 0, endTimeMs: 1_000_000, durationMs: 800_000 }, 600);
  assert.deepEqual(b, { elapsedSec: 1000, activeSec: 800, movingSec: 600, pausedSec: 200, stoppedSec: 200 });
  const odd = G.timeBreakdown({ startTimeMs: 0, durationMs: 100_000 }, 500);
  assert.ok(odd.pausedSec >= 0 && odd.stoppedSec >= 0 && odd.movingSec <= odd.activeSec && odd.elapsedSec >= odd.activeSec);
});

test("實際軌跡檔：分段與移動時間合理", () => {
  const dir = path.join(__dirname, "..", "data");
  const man = JSON.parse(fs.readFileSync(path.join(dir, "manifest.json"), "utf8"));
  for (const e of man.tracks) {
    const t = JSON.parse(fs.readFileSync(path.join(dir, e.file || e.trackId + ".json"), "utf8"));
    const sp = G.splits(t.points);
    for (const s of sp) assert.ok(s.seconds > 0 && Number.isFinite(s.avgKmh) && s.avgKmh < 400, e.trackId + " 第 " + s.index + " 段 " + s.avgKmh);
    const totalKm = sp.reduce((a, s) => a + s.distanceM, 0) / 1000;
    assert.ok(totalKm <= t.distanceMeters / 1000 * 1.05 + 0.2, "分段距離不會比整趟還多");
    const tb = G.timeBreakdown(t, G.movingSeconds(t.points));
    assert.ok(tb.movingSec <= tb.activeSec + 1e-9 && tb.elapsedSec >= tb.activeSec - 1e-9);
  }
});
