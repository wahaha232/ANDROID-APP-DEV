// GPX 匯入的純邏輯測試。執行：node --test gpstracker-web/tests/gpx.test.js
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const X = require("../gt-gpx.js");
const G = require("../gt-core.js");

const MPD = 111194.93; // 1 度緯度的公尺數
function gpx(points, name) {
  const body = points.map((p) => `<trkpt lat="${p.lat}" lon="${p.lon}">${p.ele != null ? `<ele>${p.ele}</ele>` : ""}${p.t ? `<time>${p.t}</time>` : ""}</trkpt>`).join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="x" xmlns="http://www.topografix.com/GPX/1/1"><trk>${name ? `<name>${name}</name>` : ""}<trkseg>${body}</trkseg></trk></gpx>`;
}
// 沿緯度方向每秒 mps 公尺，從 2026-10-10T08:00:00Z 起
function walk(sec, mps, opts = {}) {
  return Array.from({ length: sec + 1 }, (_, s) => ({
    lat: 25 + (s * mps) / MPD, lon: 121.5, ele: opts.ele ? opts.ele(s) : null,
    t: new Date(Date.UTC(2026, 9, 10, 8, 0, s)).toISOString().replace(".000Z", "Z")
  }));
}

test("解析：屬性順序／單引號／自閉合／命名空間／多段都能處理，名稱會解碼", () => {
  const xml = `<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><name>晨跑 &amp; 散步</name>
    <trkseg><trkpt lon='121.5' lat='25.0'><ele>10.5</ele><time>2026-10-10T08:00:00Z</time></trkpt><trkpt lat="25.001" lon="121.5"/></trkseg>
    <trkseg><trkpt lat="25.002" lon="121.5"><time>2026-10-10T08:00:20Z</time></trkpt></trkseg></trk></gpx>`;
  const p = X.parse(xml);
  assert.equal(p.name, "晨跑 & 散步");
  assert.equal(p.points.length, 3);
  assert.deepEqual([p.points[0].lat, p.points[0].lon, p.points[0].ele], [25, 121.5, 10.5]);
  assert.ok(Number.isNaN(p.points[1].tMs) && p.points[1].ele === null);
  assert.equal(p.points[2].tMs, Date.UTC(2026, 9, 10, 8, 0, 20));
  assert.deepEqual(X.parse("").points, []);
  assert.deepEqual(X.parse("<gpx><trkpt lat=\"999\" lon=\"0\"></trkpt></gpx>").points, [], "不合理的座標被略過");
});

test("轉換：距離、時間、速度、爬升、海拔、點格式與統計欄位", () => {
  const r = X.build(X.parse(gpx(walk(300, 3, { ele: (s) => 20 + s * 0.1 }))), { id: "abc", mode: "BICYCLE" });
  const t = r.track;
  assert.equal(t.trackId, "abc"); assert.equal(t.dominantMode, "BICYCLE"); assert.equal(t.source, "gpx-import");
  assert.ok(Math.abs(t.distanceMeters - 900) < 1);
  assert.equal(t.durationMs, 300_000);
  assert.ok(Math.abs(t.avgSpeedMps - 3) < 0.01 && Math.abs(t.maxSpeedMps - 3) < 0.05);
  assert.ok(Math.abs(t.totalAscentMeters - 30) < 3, "海拔從 20 升到 50 m，累計約 30 m");
  assert.equal(t.totalDescentMeters, 0);
  assert.equal(t.maxAltitudeMeters, 50); assert.equal(t.minAltitudeMeters, 20);
  assert.equal(t.points.length, 301);
  const p = t.points[100];
  assert.equal(p.length, 5); assert.equal(p[4], 100); assert.ok(Math.abs(p[3] - 10.8) < 0.2, "第 4 欄是 km/h");
  assert.deepEqual(t.segments, [["BICYCLE", 0, 300]]);
  assert.equal(r.entry.file, "abc.json");
  for (const k of ["trackId", "startTimeMs", "distanceMeters", "durationMs", "avgSpeedMps", "maxSpeedMps", "stepCount", "dominantMode", "totalAscentMeters", "totalDescentMeters", "photoCount", "file"]) assert.ok(k in r.entry, k);
  assert.equal(X.build(X.parse(gpx(walk(60, 2))), {}).track.dominantMode, "UNKNOWN");
});

test("沒有海拔就不捏造：海拔欄位為 null、爬升為 0", () => {
  const t = X.build(X.parse(gpx(walk(60, 2))), { id: "n" }).track;
  assert.equal(t.maxAltitudeMeters, null); assert.equal(t.minAltitudeMeters, null); assert.equal(t.totalAscentMeters, 0);
});

test("暫停（間隔 > 120 秒）不計入活動時間；GPS 跳點（> 400 km/h）不計入距離", () => {
  const a = walk(100, 3);
  const b = walk(100, 3).map((p, s) => ({ ...p, lat: p.lat + 500 / MPD, t: new Date(Date.UTC(2026, 9, 10, 8, 0, 100 + 600 + s)).toISOString() }));
  const t = X.build(X.parse(gpx(a.concat(b))), { id: "g" }).track;
  assert.ok(t.durationMs >= 195_000 && t.durationMs <= 205_000, "兩段各約 100 秒，中間 10 分鐘暫停不算");
  assert.equal(t.points.at(-1)[4], 800, "點的時間仍是實際經過秒數（含中間的暫停）");
  const jump = walk(50, 3).concat(walk(50, 3).map((p, s) => ({ ...p, lat: p.lat + 200 / MPD * 1000, t: new Date(Date.UTC(2026, 9, 10, 8, 0, 51 + s)).toISOString() })));
  const j = X.build(X.parse(gpx(jump)), { id: "j" }).track;
  assert.ok(j.distanceMeters < 400, "200 km 的跳點沒有被算進距離");
  assert.ok(j.maxSpeedMps * 3.6 < 400);
});

test("錯誤：沒有點、沒有時間、時間資訊不足都要拒絕，並有說明文字", () => {
  assert.equal(X.build(X.parse("<gpx></gpx>")).error, "no-points");
  const noTime = walk(10, 2).map((p) => ({ ...p, t: null }));
  assert.equal(X.build(X.parse(gpx(noTime))).error, "no-time");
  const oneTimed = noTime.map((p, i) => (i === 0 ? { ...p, t: "2026-10-10T08:00:00Z" } : p));
  assert.equal(X.build(X.parse(gpx(oneTimed))).error, "too-few");
  for (const k of ["no-points", "no-time", "too-few"]) assert.ok(X.ERRORS[k].length > 5);
});

test("時間亂序與重複時間：排序並去除重複，不會出現負的時間差", () => {
  const pts = walk(30, 2); const shuffled = [pts[5], pts[0], pts[30], pts[10], pts[10], pts[20]];
  const t = X.build(X.parse(gpx(shuffled)), { id: "s" }).track;
  assert.deepEqual(t.points.map((p) => p[4]), [0, 5, 10, 20, 30]);
  assert.ok(t.distanceMeters > 0);
});

test("超過上限的點會等距抽樣（保留首尾）", () => {
  const big = walk(25_000, 0.5);
  const t = X.build(X.parse(gpx(big)), { id: "b" }).track;
  assert.equal(t.points.length, X.MAX_POINTS);
  assert.equal(t.points[0][4], 0); assert.equal(t.points.at(-1)[4], 25_000);
});

test("重複匯入偵測：開始時間與距離都接近才算重複", () => {
  const e = X.build(X.parse(gpx(walk(300, 3))), { id: "d" }).entry;
  assert.equal(X.isDuplicate([{ startTimeMs: e.startTimeMs + 2000, distanceMeters: e.distanceMeters + 5 }], e), true);
  assert.equal(X.isDuplicate([{ startTimeMs: e.startTimeMs + 60_000, distanceMeters: e.distanceMeters }], e), false);
  assert.equal(X.isDuplicate([{ startTimeMs: e.startTimeMs, distanceMeters: e.distanceMeters * 2 }], e), false);
  assert.equal(X.isDuplicate([], e), false); assert.equal(X.isDuplicate(undefined, e), false);
});

test("往返：本站匯出的 GPX 再匯入，距離與時間相符（用實際軌跡檔）", () => {
  const dir = path.join(__dirname, "..", "data");
  const man = JSON.parse(fs.readFileSync(path.join(dir, "manifest.json"), "utf8"));
  for (const e of man.tracks) {
    const t = JSON.parse(fs.readFileSync(path.join(dir, e.file || e.trackId + ".json"), "utf8"));
    const back = X.build(X.parse(G.gpx(t)), { id: "rt" });
    assert.ok(!back.error, e.trackId);
    assert.ok(back.track.points.length <= t.points.length && back.track.points.length >= t.points.length * 0.9, "同一秒的重複點會被合併，但不會少太多");
    const span = (t.points.at(-1)[4] - t.points[0][4]) * 1000;
    assert.ok(Math.abs(back.track.endTimeMs - back.track.startTimeMs - span) < 2000);
    assert.ok(back.track.distanceMeters > 0 && back.track.distanceMeters < t.distanceMeters * 1.5 + 100);
  }
});
