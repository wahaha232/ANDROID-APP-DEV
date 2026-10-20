// GPX 匯入：把 GPX 檔（Strava、Garmin、其他 App 匯出的）轉成本站的紀錄格式。
// 純邏輯、不碰 DOM、不發網路請求，可用 node 單元測試（tests/gpx.test.js）。
// 只採用 GPX 裡「實際存在」的資料：沒有時間資訊就無法算時間與速度，會拒絕匯入，不會憑空補值。
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.GTGpx = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  var MAX_POINTS = 20000;        // 與 App 上傳的上限一致
  var ACTIVE_GAP_S = 120;        // 相鄰點間隔超過此秒數視為暫停：不計入活動時間
  var SPEED_TRUST_MAX_KMH = 400; // 隱含速度超過此值視為 GPS 跳點：不計入距離與速度

  function attr(tag, name) {
    var m = new RegExp("\\b" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").exec(tag);
    return m ? (m[1] !== undefined ? m[1] : m[2]) : null;
  }
  function tagText(block, tag) {
    var m = new RegExp("<" + tag + "(?:\\s[^>]*)?>([^<]*)</" + tag + ">", "i").exec(block);
    return m ? m[1].trim() : null;
  }
  function decode(s) { return String(s).replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, "\"").replace(/&apos;/g, "'").replace(/&amp;/g, "&"); }

  // 解析 GPX 文字：所有 <trkpt>（多個 trk／trkseg 會依檔案順序合併）。
  function parse(xml) {
    xml = String(xml || "");
    var out = { name: null, points: [] }, m;
    var nm = /<trk\b[\s\S]*?<name\b[^>]*>([^<]*)<\/name>/i.exec(xml);
    if (nm) out.name = decode(nm[1].trim()) || null;
    var re = /<trkpt\b([^>]*?)(?:\/>|>([\s\S]*?)<\/trkpt>)/gi;
    while ((m = re.exec(xml))) {
      var lat = parseFloat(attr(m[1], "lat")), lon = parseFloat(attr(m[1], "lon"));
      if (!isFinite(lat) || !isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
      var body = m[2] || "", ele = tagText(body, "ele"), time = tagText(body, "time");
      var e = ele === null ? NaN : parseFloat(ele);
      out.points.push({ lat: lat, lon: lon, ele: isFinite(e) ? e : null, tMs: time ? Date.parse(time) : NaN });
    }
    return out;
  }

  function hav(aLon, aLat, bLon, bLat) {
    var R = 6371000, dLat = (bLat - aLat) * Math.PI / 180, dLon = (bLon - aLon) * Math.PI / 180;
    var x = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(aLat * Math.PI / 180) * Math.cos(bLat * Math.PI / 180) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(x)));
  }
  function round(v, n) { var k = Math.pow(10, n); return Math.round(v * k) / k; }
  function movingAvg(arr, win) {
    var half = Math.floor(win / 2);
    return arr.map(function (_, i) {
      var s = 0, n = 0;
      for (var j = Math.max(0, i - half); j <= Math.min(arr.length - 1, i + half); j++) { s += arr[j]; n++; }
      return s / n;
    });
  }
  function uuid() {
    if (typeof crypto !== "undefined" && crypto.randomUUID) return crypto.randomUUID();
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) { var r = Math.random() * 16 | 0; return (c === "x" ? r : (r & 3 | 8)).toString(16); });
  }

  // 轉成本站格式。opts: { mode: "WALKING"|…（預設 UNKNOWN）, id: 指定 trackId（測試用） }
  // 回傳 { track, entry, summary } 或 { error: "no-points" | "no-time" | "too-few" }。
  function build(parsed, opts) {
    opts = opts || {};
    var all = (parsed && parsed.points) || [];
    if (all.length < 2) return { error: "no-points" };
    var timed = all.filter(function (p) { return isFinite(p.tMs); }).sort(function (a, b) { return a.tMs - b.tMs; });
    var pts = [];
    timed.forEach(function (p) { if (!pts.length || p.tMs > pts[pts.length - 1].tMs) pts.push(p); }); // 時間必須遞增
    if (pts.length < 2) return { error: timed.length ? "too-few" : "no-time" };
    if (pts.length > MAX_POINTS) {
      var step = (pts.length - 1) / (MAX_POINTS - 1), keep = [];
      for (var k = 0; k < MAX_POINTS; k++) keep.push(pts[Math.round(k * step)]);
      pts = keep;
    }

    var n = pts.length, t0 = pts[0].tMs, distM = 0, activeSec = 0, maxKmh = 0, speeds = new Array(n).fill(0), i;
    for (i = 1; i < n; i++) {
      var dt = (pts[i].tMs - pts[i - 1].tMs) / 1000;
      var d = hav(pts[i - 1].lon, pts[i - 1].lat, pts[i].lon, pts[i].lat), kmh = d / dt * 3.6;
      if (kmh > SPEED_TRUST_MAX_KMH) continue; // GPS 跳點：不算距離、速度記 0
      if (dt > ACTIVE_GAP_S) continue;         // 暫停／GPS 遺失／搭捷運：直線跳躍不算距離，與 App 的距離一致
      distM += d; speeds[i] = kmh;
      activeSec += dt;
      if (kmh > maxKmh) maxKmh = kmh;
    }
    speeds[0] = speeds[1];
    var elapsedSec = (pts[n - 1].tMs - t0) / 1000;
    if (!(activeSec > 0)) activeSec = elapsedSec;

    // 海拔：只在有實際海拔時計算；缺值以前一個有效值補，平滑後用 2 m 門檻累計爬升／下降。
    var eles = pts.map(function (p) { return p.ele; }), valid = eles.filter(function (e) { return e !== null; });
    var asc = 0, desc = 0, maxAlt = null, minAlt = null;
    if (valid.length >= 2) {
      var last = valid[0], filled = eles.map(function (e) { if (e !== null) last = e; return last; }), sm = movingAvg(filled, 5), ref = sm[0];
      sm.forEach(function (a) { if (a - ref >= 2) { asc += a - ref; ref = a; } else if (ref - a >= 2) { desc += ref - a; ref = a; } });
      maxAlt = Math.max.apply(null, valid); minAlt = Math.min.apply(null, valid);
    }

    var id = opts.id || uuid(), mode = opts.mode || "UNKNOWN", endSec = Math.round(elapsedSec);
    var track = {
      trackId: id, startTimeMs: t0, endTimeMs: pts[n - 1].tMs, durationMs: Math.round(activeSec * 1000),
      distanceMeters: round(distM, 2), avgSpeedMps: round(distM / activeSec, 3), maxSpeedMps: round(maxKmh / 3.6, 3),
      stepCount: 0, stepSource: "NONE", dominantMode: mode, gpsPointCount: n, photoCount: 0,
      totalAscentMeters: round(asc, 1), totalDescentMeters: round(desc, 1),
      maxAltitudeMeters: maxAlt === null ? null : round(maxAlt, 1), minAltitudeMeters: minAlt === null ? null : round(minAlt, 1),
      points: pts.map(function (p, j) { return [round(p.lon, 6), round(p.lat, 6), round(p.ele === null ? 0 : p.ele, 1), round(speeds[j], 1), Math.round((p.tMs - t0) / 1000)]; }),
      photos: [], segments: [[mode, 0, endSec]], source: "gpx-import"
    };
    var entry = {
      trackId: id, startTimeMs: t0, distanceMeters: track.distanceMeters, durationMs: track.durationMs,
      avgSpeedMps: track.avgSpeedMps, maxSpeedMps: track.maxSpeedMps, stepCount: 0, dominantMode: mode,
      totalAscentMeters: track.totalAscentMeters, totalDescentMeters: track.totalDescentMeters, photoCount: 0, file: id + ".json"
    };
    return { track: track, entry: entry, summary: { name: parsed.name, points: n, skippedNoTime: all.length - timed.length, startTimeMs: t0, distanceMeters: track.distanceMeters, durationMs: track.durationMs, hasAltitude: valid.length >= 2 } };
  }

  // 已存在（開始時間相差 5 秒內、距離相差 1% 或 50 m 內）就視為重複匯入。
  function isDuplicate(manifestTracks, entry) {
    return (manifestTracks || []).some(function (t) {
      return Math.abs((t.startTimeMs || 0) - entry.startTimeMs) <= 5000 &&
        Math.abs((t.distanceMeters || 0) - entry.distanceMeters) <= Math.max(50, entry.distanceMeters * 0.01);
    });
  }

  var ERRORS = {
    "no-points": "檔案裡找不到軌跡點（需要 GPX 的 <trkpt>）。",
    "no-time": "這個 GPX 沒有時間資訊，無法計算活動時間與速度，所以不能匯入。",
    "too-few": "有時間資訊的軌跡點少於 2 個，無法匯入。"
  };

  return { parse: parse, build: build, isDuplicate: isDuplicate, ERRORS: ERRORS, MAX_POINTS: MAX_POINTS };
});
