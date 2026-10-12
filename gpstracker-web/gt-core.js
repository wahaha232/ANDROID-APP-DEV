// GPS Tracker 新版介面共用的「純函式」：格式化、標題、搜尋/排序/分組、路線縮圖、可顯示欄位、GPX。
// 不碰 DOM、不發網路請求，所以可以直接用 node 單元測試（tests/gt-core.test.js）。
// 不包含任何里程／挑戰判定邏輯：那些仍由既有的 solar-core / earth-core / taiwan-core / progress-core / challenges.js 負責。
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.GTCore = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  var MODE_EMOJI = {
    WALKING: "🚶", RUNNING: "🏃", BICYCLE: "🚲", CYCLING: "🚲", VEHICLE: "🚗", DRIVING: "🚗",
    CAR: "🚗", MOTORCYCLE: "🏍️", TRAIN: "🚆", BUS: "🚌", BOAT: "⛵", STATIONARY: "🧍", UNKNOWN: "❓"
  };
  var MODE_NAME = {
    WALKING: "步行", RUNNING: "跑步", BICYCLE: "單車", CYCLING: "單車", VEHICLE: "車輛", DRIVING: "開車",
    CAR: "開車", MOTORCYCLE: "機車", TRAIN: "火車", BUS: "公車", BOAT: "船", STATIONARY: "靜止", UNKNOWN: "未知"
  };
  var STEP_SOURCE = { ZEPP_LIFE: "Zepp Life", GOOGLE_FIT: "Google Fit", SENSOR: "手機感測器", SENSOR_FALLBACK: "手機感測器・待補同步" };

  var MODE_COLOR = {
    WALKING: "#34d399", RUNNING: "#fbbf24", BICYCLE: "#22d3ee", CYCLING: "#22d3ee", MOTORCYCLE: "#fb923c",
    VEHICLE: "#a78bfa", DRIVING: "#a78bfa", CAR: "#a78bfa", TRAIN: "#60a5fa", BUS: "#f472b6", BOAT: "#38bdf8",
    STATIONARY: "#64748b", UNKNOWN: "#475569"
  };

  // 海拔序列：0 視為缺值（與既有回放頁 HUD 一致），缺值用前一個有效值補，避免剖面圖出現掉到 0 的尖刺。
  function altitudeSeries(points) {
    var out = [], last = null, firstValid = null, i;
    for (i = 0; i < (points || []).length; i++) {
      var a = points[i][2];
      if (typeof a === "number" && isFinite(a) && a !== 0) { firstValid = a; break; }
    }
    last = firstValid == null ? 0 : firstValid;
    for (i = 0; i < (points || []).length; i++) {
      var v = points[i][2];
      if (typeof v === "number" && isFinite(v) && v !== 0) last = v;
      out.push(last);
    }
    return out;
  }

  function modeEmoji(m) { return MODE_EMOJI[m] || "❓"; }
  function modeName(m) { return MODE_NAME[m] || m || "未知"; }
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function num(v) { return typeof v === "number" && isFinite(v) ? v : 0; }

  function fmtDay(ms) { var d = new Date(ms); return d.getFullYear() + "/" + pad(d.getMonth() + 1) + "/" + pad(d.getDate()); }
  function fmtTime(ms) { var d = new Date(ms); return pad(d.getHours()) + ":" + pad(d.getMinutes()); }
  function fmtDate(ms) { return fmtDay(ms) + " " + fmtTime(ms); }
  function fmtDur(ms) {
    var s = Math.floor(num(ms) / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
    return h > 0 ? h + "h " + m + "m" : m + "m";
  }
  // 播放進度用的時鐘：mm:ss 或 h:mm:ss
  function fmtClock(sec) {
    sec = Math.max(0, Math.floor(num(sec)));
    var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    return (h > 0 ? h + ":" + pad(m) : String(m)) + ":" + pad(s);
  }
  function fmtKm(meters, digits) { return (num(meters) / 1000).toFixed(digits == null ? 2 : digits); }
  function fmtInt(v) { return Math.round(num(v)).toLocaleString("zh-Hant"); }
  function kmh(mps) { return num(mps) * 3.6; }
  function stepSourceLabel(src) { return STEP_SOURCE[src] || ""; }

  function escapeHtml(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  // 活動名稱：使用者在「紀錄管理」填的標題（meta.json overlay）優先；沒有就用「日期 + 類型」。
  function activityTitle(entry, metaTracks) {
    var m = metaTracks && entry && metaTracks[entry.trackId];
    if (m && m.title) return String(m.title);
    return entry ? fmtDay(entry.startTimeMs) + " " + modeName(entry.dominantMode) : "";
  }

  function filterTracks(list, opt) {
    opt = opt || {};
    var q = (opt.q || "").trim().toLowerCase(), mode = opt.mode || "", meta = opt.meta || {};
    return (list || []).filter(function (t) {
      if (mode && t.dominantMode !== mode) return false;
      if (!q) return true;
      var m = meta[t.trackId] || {};
      var hay = [fmtDate(t.startTimeMs), fmtDay(t.startTimeMs), t.dominantMode, modeName(t.dominantMode), m.title, m.notes]
        .filter(Boolean).join(" ").toLowerCase();
      return hay.indexOf(q) >= 0;
    });
  }

  var SORTERS = {
    date_desc: function (a, b) { return b.startTimeMs - a.startTimeMs; },
    date_asc: function (a, b) { return a.startTimeMs - b.startTimeMs; },
    dist_desc: function (a, b) { return num(b.distanceMeters) - num(a.distanceMeters); },
    dur_desc: function (a, b) { return num(b.durationMs) - num(a.durationMs); },
    steps_desc: function (a, b) { return num(b.stepCount) - num(a.stepCount); }
  };
  function sortTracks(list, key) {
    return (list || []).slice().sort(SORTERS[key] || SORTERS.date_desc);
  }

  // 依月份分組（保留傳入順序）。
  function groupByMonth(list) {
    var out = [], idx = {};
    (list || []).forEach(function (t) {
      var d = new Date(t.startTimeMs), key = d.getFullYear() + "-" + pad(d.getMonth() + 1);
      if (!(key in idx)) { idx[key] = out.length; out.push({ key: key, label: d.getFullYear() + " 年 " + (d.getMonth() + 1) + " 月", items: [] }); }
      out[idx[key]].items.push(t);
    });
    return out;
  }

  function downsample(arr, max) {
    if (!arr || arr.length <= max) return arr || [];
    var out = [], step = (arr.length - 1) / (max - 1);
    for (var i = 0; i < max; i++) out.push(arr[Math.round(i * step)]);
    return out;
  }

  // 路線縮圖：把 [lng,lat,...] 轉成 SVG path（等距圓柱投影，經度依緯度縮放，保持比例）。
  // 這是 2D 縮圖，只用在清單/卡片；不是 3D 功能的替代品。
  function routePath(points, w, h, padding, maxPts) {
    var pts = downsample((points || []).filter(function (p) { return p && isFinite(p[0]) && isFinite(p[1]); }), maxPts || 160);
    if (pts.length < 2) return null;
    var minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity, i;
    for (i = 0; i < pts.length; i++) {
      if (pts[i][0] < minX) minX = pts[i][0]; if (pts[i][0] > maxX) maxX = pts[i][0];
      if (pts[i][1] < minY) minY = pts[i][1]; if (pts[i][1] > maxY) maxY = pts[i][1];
    }
    var k = Math.cos(((minY + maxY) / 2) * Math.PI / 180);
    var spanX = Math.max((maxX - minX) * k, 1e-9), spanY = Math.max(maxY - minY, 1e-9);
    padding = padding == null ? 8 : padding;
    var scale = Math.min((w - 2 * padding) / spanX, (h - 2 * padding) / spanY);
    var offX = (w - spanX * scale) / 2, offY = (h - spanY * scale) / 2;
    function px(p) { return [offX + (p[0] - minX) * k * scale, h - (offY + (p[1] - minY) * scale)]; }
    var d = "", first = null, last = null;
    for (i = 0; i < pts.length; i++) {
      var q = px(pts[i]);
      if (!first) first = q;
      last = q;
      d += (i === 0 ? "M" : "L") + q[0].toFixed(1) + " " + q[1].toFixed(1);
    }
    return { d: d, start: first, end: last, w: w, h: h };
  }

  // 只列「實際有資料」的欄位，沒資料的不顯示（不以 0 或假資料填充）。
  function trackFields(t) {
    var f = [];
    function add(key, label, value, unit) { f.push({ key: key, label: label, value: value, unit: unit || "" }); }
    if (num(t.distanceMeters) > 0) add("distance", "距離", fmtKm(t.distanceMeters), "km");
    if (num(t.durationMs) > 0) add("duration", "活動時間", fmtDur(t.durationMs));
    if (num(t.avgSpeedMps) > 0) add("avgSpeed", "平均速度", kmh(t.avgSpeedMps).toFixed(1), "km/h");
    if (num(t.maxSpeedMps) > 0) add("maxSpeed", "最高速度", kmh(t.maxSpeedMps).toFixed(1), "km/h");
    if (num(t.stepCount) > 0) {
      var src = stepSourceLabel(t.stepSource);
      add("steps", "步數" + (src ? "（" + src + "）" : ""), fmtInt(t.stepCount), "步");
    }
    if (num(t.totalAscentMeters) > 0) add("ascent", "總爬升", fmtInt(t.totalAscentMeters), "m");
    if (num(t.totalDescentMeters) > 0) add("descent", "總下降", fmtInt(t.totalDescentMeters), "m");
    if (hasAltitude(t.points) && isFinite(t.maxAltitudeMeters)) add("maxAlt", "最高海拔", fmtInt(t.maxAltitudeMeters), "m");
    if (num(t.gpsPointCount) > 0) add("points", "定位點", fmtInt(t.gpsPointCount));
    if (num(t.photoCount) > 0) add("photos", "照片", fmtInt(t.photoCount), "張");
    return f;
  }

  // 有沒有可用的海拔資料（點的第 3 欄；0 視為缺值，與既有回放頁一致）。
  function hasAltitude(points) {
    if (!points || points.length < 2) return false;
    var min = Infinity, max = -Infinity, n = 0;
    for (var i = 0; i < points.length; i++) {
      var a = points[i][2];
      if (typeof a === "number" && isFinite(a) && a !== 0) { n++; if (a < min) min = a; if (a > max) max = a; }
    }
    return n >= 2 && max > min;
  }

  // segments 實測為 [模式, 起秒, 迄秒]（相對於開始時間的秒數；以本專案實際資料驗證：最後一段結束秒 ≈ endTimeMs - startTimeMs）。
  function modeBreakdown(segments) {
    var by = {}, total = 0;
    (segments || []).forEach(function (s) {
      var sec = num(s[2]) - num(s[1]);
      if (!(sec > 0)) return;
      by[s[0]] = (by[s[0]] || 0) + sec; total += sec;
    });
    return Object.keys(by).map(function (m) { return { mode: m, sec: by[m], pct: total ? by[m] / total * 100 : 0 }; })
      .sort(function (a, b) { return b.sec - a.sec; });
  }

  function gpx(t) {
    var base = t.startTimeMs || 0, name = fmtDate(base);
    var out = '<?xml version="1.0" encoding="UTF-8"?>\n' +
      '<gpx version="1.1" creator="GPS Tracker" xmlns="http://www.topografix.com/GPX/1/1">\n' +
      "<metadata><name>" + escapeHtml(name) + "</name><time>" + new Date(base).toISOString() + "</time></metadata>\n" +
      "<trk><name>" + escapeHtml(name) + "</name><trkseg>\n";
    (t.points || []).forEach(function (p) {
      out += '<trkpt lat="' + p[1] + '" lon="' + p[0] + '"><ele>' + (p[2] || 0) + "</ele><time>" +
        new Date(base + (p[4] || 0) * 1000).toISOString() + "</time></trkpt>\n";
    });
    return out + "</trkseg></trk></gpx>\n";
  }

  // 依「距離進度」在已知的 [秒] 序列中內插經過時間。cumFrac[i] = 第 i 點的累計距離比例（0~1，遞增）。
  function elapsedAt(points, cumFrac, f) {
    if (!points || points.length < 2) return 0;
    var lo = 1, hi = cumFrac.length - 1;
    while (lo < hi) { var mid = (lo + hi) >> 1; if (cumFrac[mid] < f) lo = mid + 1; else hi = mid; }
    var i = Math.min(lo, points.length - 1);
    var span = cumFrac[i] - cumFrac[i - 1];
    var t = span > 0 ? Math.min(1, Math.max(0, (f - cumFrac[i - 1]) / span)) : 0;
    var a = num(points[i - 1][4]), b = num(points[i][4]);
    return a + (b - a) * t;
  }

  return {
    MODE_EMOJI: MODE_EMOJI, MODE_NAME: MODE_NAME, MODE_COLOR: MODE_COLOR, altitudeSeries: altitudeSeries,
    modeEmoji: modeEmoji, modeName: modeName, pad: pad,
    fmtDay: fmtDay, fmtTime: fmtTime, fmtDate: fmtDate, fmtDur: fmtDur, fmtClock: fmtClock, fmtKm: fmtKm, fmtInt: fmtInt,
    kmh: kmh, stepSourceLabel: stepSourceLabel, escapeHtml: escapeHtml,
    activityTitle: activityTitle, filterTracks: filterTracks, sortTracks: sortTracks, groupByMonth: groupByMonth,
    downsample: downsample, routePath: routePath, trackFields: trackFields, hasAltitude: hasAltitude,
    modeBreakdown: modeBreakdown, gpx: gpx, elapsedAt: elapsedAt
  };
});
