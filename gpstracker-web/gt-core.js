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
    CAR: "🚗", MOTORCYCLE: "🏍️", TRAIN: "🚆", BUS: "🚌", BOAT: "⛵", STATIONARY: "🧍", PUBLIC_TRANSPORT: "🚌", AIRPLANE: "✈️", UNKNOWN: "❓"
  };
  var MODE_NAME = {
    WALKING: "步行", RUNNING: "跑步", BICYCLE: "單車", CYCLING: "單車", VEHICLE: "車輛", DRIVING: "開車",
    CAR: "開車", MOTORCYCLE: "機車", TRAIN: "火車／捷運", BUS: "公車", BOAT: "船", STATIONARY: "靜止", PUBLIC_TRANSPORT: "大眾運輸", AIRPLANE: "飛機", UNKNOWN: "未知"
  };
  // 競賽中心（challenges.js）沿用的分組與短名稱：TRAIN 顯示「火車」而非「火車／捷運」。
  var MODE_GROUP = {
    WALKING: "步行", RUNNING: "跑步", BICYCLE: "單車", CYCLING: "單車", MOTORCYCLE: "機車",
    VEHICLE: "汽車", DRIVING: "汽車", CAR: "汽車", TRAIN: "大眾運輸", BUS: "大眾運輸",
    BOAT: "船", STATIONARY: "其他/未知", PUBLIC_TRANSPORT: "大眾運輸", AIRPLANE: "飛機", UNKNOWN: "未知"
  };
  var MODE_NAME_SHORT = { TRAIN: "火車" };
  var STEP_SOURCE = { ZEPP_LIFE: "Zepp Life", GOOGLE_FIT: "Google Fit", SENSOR: "手機感測器", SENSOR_FALLBACK: "手機感測器・待補同步" };

  var MODE_COLOR = {
    WALKING: "#34d399", RUNNING: "#fbbf24", BICYCLE: "#22d3ee", CYCLING: "#22d3ee", MOTORCYCLE: "#fb923c",
    VEHICLE: "#a78bfa", DRIVING: "#a78bfa", CAR: "#a78bfa", TRAIN: "#60a5fa", BUS: "#f472b6", BOAT: "#38bdf8",
    STATIONARY: "#64748b", PUBLIC_TRANSPORT: "#f472b6", AIRPLANE: "#e879f9", UNKNOWN: "#475569"
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

  // 活動的移動方式：使用者在「紀錄管理」改過（meta.json 的 mode）就以它為準，否則用 App 判斷的 dominantMode。
  // 只影響網站的顯示、篩選與搜尋；原始紀錄與挑戰統計仍用 App 判斷的值。
  function modeOf(t, metaTracks) {
    var m = metaTracks && t && metaTracks[t.trackId];
    return (m && m.mode) || (t && t.dominantMode) || "UNKNOWN";
  }
  function modeOverridden(t, metaTracks) {
    var m = metaTracks && t && metaTracks[t.trackId];
    return !!(m && m.mode && m.mode !== t.dominantMode);
  }

  function modeEmoji(m) { return MODE_EMOJI[m] || "❓"; }
  // 沒登記的模式名稱只接受單純的英數字詞；其他（例如被竄改成 HTML 的字串）一律顯示「未知」。
  function modeName(m) { return MODE_NAME[m] || (/^[A-Za-z0-9_ -]{1,30}$/.test(String(m || "")) ? String(m) : "未知"); }
  function modeNameShort(m) { return MODE_NAME_SHORT[m] || modeName(m); }
  function modeGroup(m) { return MODE_GROUP[m] || "其他/未知"; }
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
    return entry ? fmtDay(entry.startTimeMs) + " " + modeName(modeOf(entry, metaTracks)) : "";
  }

  // 裝置名稱：App 上傳時標記的來源手機；舊紀錄沒有這個欄位時歸類為「未命名裝置」。
  function deviceNameOf(entry) {
    var name = entry && entry.deviceName;
    return name ? String(name) : "未命名裝置";
  }

  // ───── 挑戰裝置切換（探索挑戰、征服計畫共用）─────
  // 挑戰進度必須依裝置分開計算；沒有「全部裝置」選項，避免兩支手機的里程混合。
  var DEVICE_KEY = "gpst_device";

  function deviceNames(tracks) {
    var seen = {};
    (tracks || []).forEach(function (t) { seen[deviceNameOf(t)] = true; });
    return Object.keys(seen).sort(function (a, b) { return a.localeCompare(b, "zh-Hant"); });
  }

  function getRequestedDevice() {
    try {
      var q = new URLSearchParams(location.search).get("device");
      if (q) return q;
    } catch (e) { /* 舊瀏覽器或非瀏覽器環境 */ }
    try { return localStorage.getItem(DEVICE_KEY) || ""; } catch (e) { return ""; }
  }

  function setSelectedDevice(name) {
    try { if (name) localStorage.setItem(DEVICE_KEY, name); } catch (e) { /* 無痕模式 */ }
  }

  function chooseDevice(tracks) {
    var names = deviceNames(tracks), want = getRequestedDevice();
    if (want && names.indexOf(want) >= 0) { setSelectedDevice(want); return want; }
    // 沒有指定裝置時，預設挑「PC 以外」的最新一筆；PC 模擬紀錄不搶手機的預設視圖。
    // 只有 PC 一台裝置時才退回 PC；使用者用 ?device=PC 或選單切到 PC 仍照常顯示。
    var newestNonPc = null, newestAny = null;
    (tracks || []).forEach(function (t) {
      var start = +t.startTimeMs || 0;
      if (!newestAny || start > (+newestAny.startTimeMs || 0)) newestAny = t;
      if (deviceNameOf(t) !== "PC" && (!newestNonPc || start > (+newestNonPc.startTimeMs || 0))) newestNonPc = t;
    });
    var fallback = newestNonPc ? deviceNameOf(newestNonPc)
      : newestAny ? deviceNameOf(newestAny)
      : (names[0] || "");
    if (fallback) setSelectedDevice(fallback);
    return fallback;
  }

  function filterByDevice(tracks, device) {
    if (!device) return (tracks || []).slice();
    return (tracks || []).filter(function (t) { return deviceNameOf(t) === device; });
  }

  // 在指定容器建立裝置切換列；切換後把裝置寫入網址並重新載入，讓所有挑戰同步重算。
  // knownDevices 可包含尚無紀錄的裝置（例如新手機），讓它先以 0 進度出現在選單中。
  function initDeviceBar(bar, tracks, selected, knownDevices) {
    if (typeof bar === "string") bar = document.getElementById(bar);
    if (!bar) return selected || "";
    var names = deviceNames(tracks);
    (knownDevices || []).forEach(function (n) {
      if (n && names.indexOf(n) < 0) names.push(n);
    });
    names.sort(function (a, b) { return a.localeCompare(b, "zh-Hant"); });
    if (!names.length) { bar.hidden = true; return ""; }
    if (!selected) {
      var requested = getRequestedDevice();
      selected = (requested && names.indexOf(requested) >= 0) ? requested : chooseDevice(tracks);
    }
    if (names.indexOf(selected) < 0) selected = names[0];
    setSelectedDevice(selected);
    try {
      var current = new URL(location.href);
      if (current.searchParams.get("device") !== selected) {
        current.searchParams.set("device", selected);
        history.replaceState(null, "", current.toString());
      }
    } catch (e) { /* 非瀏覽器環境 */ }
    bar.hidden = false;
    bar.innerHTML = '<span class="gt-device-label">挑戰裝置</span><select aria-label="選擇挑戰裝置">' +
      names.map(function (n) { return '<option value="' + escapeHtml(n) + '">' + escapeHtml(n) + "</option>"; }).join("") +
      '</select><span class="gt-device-note">各裝置分開累積，不混合</span>';
    var sel = bar.querySelector("select");
    sel.value = selected;
    sel.addEventListener("change", function () {
      setSelectedDevice(sel.value);
      try {
        var u = new URL(location.href);
        u.searchParams.set("device", sel.value);
        location.href = u.toString();
      } catch (e) { location.reload(); }
    });
    return selected;
  }

  function filterTracks(list, opt) {
    opt = opt || {};
    var q = (opt.q || "").trim().toLowerCase(), mode = opt.mode || "", device = opt.device || "", meta = opt.meta || {};
    return (list || []).filter(function (t) {
      if (mode && modeOf(t, meta) !== mode) return false;
      if (device && deviceNameOf(t) !== device) return false;
      if (!q) return true;
      var m = meta[t.trackId] || {};
      var eff = modeOf(t, meta);
      var hay = [fmtDate(t.startTimeMs), fmtDay(t.startTimeMs), eff, modeName(eff), deviceNameOf(t), m.title, m.notes]
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

  // 路線投影：把 [lng,lat,...] 轉成畫布座標（等距圓柱投影，經度依緯度縮放，保持比例）。
  // 每個投影點保留原始索引 i（過濾掉無效點、降採樣之前的位置），讓呼叫端能對應回逐點的速度／海拔。
  function routeProject(points, w, h, padding, maxPts) {
    var items = [];
    (points || []).forEach(function (p, i) { if (p && isFinite(p[0]) && isFinite(p[1])) items.push({ p: p, i: i }); });
    items = downsample(items, maxPts || 160);
    if (items.length < 2) return null;
    var minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity, i;
    for (i = 0; i < items.length; i++) {
      var q = items[i].p;
      if (q[0] < minX) minX = q[0]; if (q[0] > maxX) maxX = q[0];
      if (q[1] < minY) minY = q[1]; if (q[1] > maxY) maxY = q[1];
    }
    var k = Math.cos(((minY + maxY) / 2) * Math.PI / 180);
    var spanX = Math.max((maxX - minX) * k, 1e-9), spanY = Math.max(maxY - minY, 1e-9);
    padding = padding == null ? 8 : padding;
    var scale = Math.min((w - 2 * padding) / spanX, (h - 2 * padding) / spanY);
    var offX = (w - spanX * scale) / 2, offY = (h - spanY * scale) / 2;
    var pts = items.map(function (it) {
      return { x: offX + (it.p[0] - minX) * k * scale, y: h - (offY + (it.p[1] - minY) * scale), i: it.i };
    });
    // tf：經緯度 → 畫布座標的線性換算，底圖圖磚要用同一組參數才對得準路線。
    return { pts: pts, w: w, h: h, tf: { minX: minX, minY: minY, k: k, scale: scale, offX: offX, offY: offY } };
  }

  // 路線縮圖底圖：算出覆蓋整個畫布的 Web Mercator 圖磚，並換算每張圖磚在畫布上的位置與大小。
  // 每張圖磚各自用四個角換算（不是整片等比縮放），所以小範圍內投影差異不會累積。
  var MAX_TILES = 20;
  function lonToTileX(lon, z) { return (lon + 180) / 360 * Math.pow(2, z); }
  function latToTileY(lat, z) {
    var r = lat * Math.PI / 180;
    return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * Math.pow(2, z);
  }
  function tileXToLon(x, z) { return x / Math.pow(2, z) * 360 - 180; }
  function tileYToLat(y, z) { var n = Math.PI - 2 * Math.PI * y / Math.pow(2, z); return Math.atan(Math.sinh(n)) * 180 / Math.PI; }

  function routeTiles(tf, w, h) {
    if (!tf || !(tf.scale > 0)) return [];
    var pxPerLon = tf.k * tf.scale;
    var lonW = tf.minX - tf.offX / pxPerLon, lonE = tf.minX + (w - tf.offX) / pxPerLon;
    var latN = tf.minY + (h - tf.offY) / tf.scale, latS = tf.minY - tf.offY / tf.scale;
    latN = Math.min(latN, 85); latS = Math.max(latS, -85);
    // 圖磚在畫布上約 200 單位寬時，原生 256px 圖磚剛好夠清楚（高 DPI 螢幕顯示也不糊）。
    var z = Math.min(17, Math.max(2, Math.ceil(Math.log(360 * pxPerLon / 200) / Math.LN2)));
    var x0, x1, y0, y1;
    for (;;) {
      x0 = Math.floor(lonToTileX(lonW, z)); x1 = Math.floor(lonToTileX(lonE, z));
      y0 = Math.floor(latToTileY(latN, z)); y1 = Math.floor(latToTileY(latS, z));
      if ((x1 - x0 + 1) * (y1 - y0 + 1) <= MAX_TILES || z <= 2) break;
      z--;
    }
    var n = Math.pow(2, z), tiles = [];
    for (var ty = y0; ty <= y1; ty++) {
      for (var tx = x0; tx <= x1; tx++) {
        if (ty < 0 || ty >= n) continue;
        var wx = ((tx % n) + n) % n;
        var px0 = tf.offX + (tileXToLon(tx, z) - tf.minX) * pxPerLon;
        var px1 = tf.offX + (tileXToLon(tx + 1, z) - tf.minX) * pxPerLon;
        var py0 = h - (tf.offY + (tileYToLat(ty, z) - tf.minY) * tf.scale);
        var py1 = h - (tf.offY + (tileYToLat(ty + 1, z) - tf.minY) * tf.scale);
        tiles.push({ z: z, x: wx, y: ty, px: px0, py: py0, pw: px1 - px0, ph: py1 - py0 });
      }
    }
    return tiles;
  }

  // 路線縮圖：把 [lng,lat,...] 轉成 SVG path。這是 2D 縮圖，只用在清單/卡片；不是 3D 功能的替代品。
  function routePath(points, w, h, padding, maxPts) {
    var r = routeProject(points, w, h, padding, maxPts);
    if (!r) return null;
    var d = "";
    r.pts.forEach(function (q, i) { d += (i === 0 ? "M" : "L") + q.x.toFixed(1) + " " + q.y.toFixed(1); });
    var f = r.pts[0], l = r.pts[r.pts.length - 1];
    return { d: d, start: [f.x, f.y], end: [l.x, l.y], w: w, h: h, tiles: routeTiles(r.tf, w, h) };
  }

  // ───── 路線上色（速度／海拔）與活動亮點 ─────
  var SPEED_TRUST_MAX_KMH = 400; // 超過視為 GPS 異常，不參與上色與亮點（與競賽中心「最高速度」同一門檻）
  var RAMPS = {
    speed: ["#38bdf8", "#34d399", "#fbbf24", "#f87171"],
    altitude: ["#3b82f6", "#22d3ee", "#a3e635", "#fde047", "#fb923c"]
  };
  function movingAvg(arr, win) {
    var half = Math.floor(win / 2), out = new Array(arr.length), i, j, sum, n;
    for (i = 0; i < arr.length; i++) {
      sum = 0; n = 0;
      for (j = Math.max(0, i - half); j <= Math.min(arr.length - 1, i + half); j++) { sum += arr[j]; n++; }
      out[i] = sum / n;
    }
    return out;
  }
  // 逐點數值：speed＝km/h（上傳檔每個點的第 4 欄本來就是 km/h，不再換算；異常高速當 0）；altitude＝公尺（沿用 altitudeSeries：0 視為缺值）。
  // 沒有可用資料回傳 null（呼叫端就不要提供該上色選項，不拿假資料填）。
  function valueSeries(points, mode) {
    points = points || [];
    if (points.length < 2) return null;
    if (mode === "altitude") return hasAltitude(points) ? movingAvg(altitudeSeries(points), 5) : null;
    if (mode === "speed") {
      var v = points.map(function (p) { var k = num(p[3]); return k <= SPEED_TRUST_MAX_KMH ? k : 0; });
      return v.some(function (x) { return x > 0.5; }) ? movingAvg(v, 5) : null;
    }
    return null;
  }
  function hexRgb(h) { return [1, 3, 5].map(function (i) { return parseInt(h.slice(i, i + 2), 16); }); }
  function rampColor(mode, t) {
    var stops = RAMPS[mode] || RAMPS.speed, x = Math.min(1, Math.max(0, t)) * (stops.length - 1);
    var i = Math.min(stops.length - 2, Math.floor(x)), f = x - i, a = hexRgb(stops[i]), b = hexRgb(stops[i + 1]);
    return "#" + [0, 1, 2].map(function (c) { return ("0" + Math.round(a[c] + (b[c] - a[c]) * f).toString(16)).slice(-2); }).join("");
  }
  // 色階範圍取第 5～95 百分位，個別尖峰不會把整條路線洗成同一個顏色。
  function valueRange(values) {
    var s = values.slice().sort(function (a, b) { return a - b; });
    var lo = s[Math.floor((s.length - 1) * 0.05)], hi = s[Math.ceil((s.length - 1) * 0.95)];
    if (!(hi - lo > 1e-6)) { lo = s[0]; hi = s[s.length - 1]; }
    if (!(hi - lo > 1e-6)) hi = lo + 1;
    return { lo: lo, hi: hi };
  }
  // 把路線切成「同色」的連續線段（最多 buckets 種顏色）。coords 與 values 等長；flags[j] 為真表示邊 j-1→j 是 GPS 中斷的估算段（不上色）。
  function colorSegments(coords, values, flags, mode, buckets) {
    buckets = buckets || 16;
    var r = valueRange(values), segs = [], cur = null, j;
    for (j = 1; j < coords.length; j++) {
      if (flags && flags[j]) { cur = null; continue; }
      var t = Math.min(1, Math.max(0, ((values[j - 1] + values[j]) / 2 - r.lo) / (r.hi - r.lo)));
      var b = Math.round(t * (buckets - 1));
      if (cur && cur.bucket === b) cur.coords.push(coords[j]);
      else { cur = { bucket: b, color: rampColor(mode, b / (buckets - 1)), coords: [coords[j - 1], coords[j]] }; segs.push(cur); }
    }
    var legend = [0, 0.25, 0.5, 0.75, 1].map(function (t) { return { value: r.lo + (r.hi - r.lo) * t, color: rampColor(mode, t) }; });
    return { segments: segs.map(function (x) { return { color: x.color, coords: x.coords }; }), lo: r.lo, hi: r.hi, legend: legend };
  }
  // 自動亮點：最高點、最大連續爬升、最快速度。資料不足的項目不列出（不捏造）。idx 是 points 的索引。
  function highlights(points) {
    var out = [];
    points = points || [];
    if (points.length < 3) return out;
    function add(key, icon, label, idx, value, unit, extra) {
      var p = points[idx];
      out.push(Object.assign({ key: key, icon: icon, label: label, idx: idx, value: value, unit: unit, lon: p[0], lat: p[1], tSec: num(p[4]) }, extra || {}));
    }
    var alt = valueSeries(points, "altitude"), spd = valueSeries(points, "speed"), i;
    if (alt) {
      var top = 0;
      for (i = 1; i < alt.length; i++) if (alt[i] > alt[top]) top = i;
      add("maxAlt", "🏔️", "最高點", top, Math.round(alt[top]), "m");
      var minIdx = 0, best = 0, bi = 0, bj = 0;
      for (i = 1; i < alt.length; i++) {
        if (alt[i] < alt[minIdx]) minIdx = i;
        if (alt[i] - alt[minIdx] > best) { best = alt[i] - alt[minIdx]; bi = minIdx; bj = i; }
      }
      if (best >= 10) add("climb", "⛰️", "最大連續爬升", bj, Math.round(best), "m", { prefix: "+", from: bi });
    }
    if (spd) {
      var fast = 0;
      for (i = 1; i < spd.length; i++) if (spd[i] > spd[fast]) fast = i;
      if (spd[fast] >= 3) add("maxSpeed", "⚡", "最快速度", fast, Math.round(spd[fast] * 10) / 10, "km/h");
    }
    return out.sort(function (a, b) { return a.idx - b.idx; });
  }

  // ───── 時間分析與每公里分段（規則與 App 的 SplitsAnalysis 相同）─────
  // 相鄰兩點間隔超過 120 秒視為暫停或 GPS 遺失：該段的距離與時間都不計入分段（避免把搭捷運、暫停等整段跳躍算成不可能的速度）；
  // 所以各分段的距離加總可能略少於整趟距離。移動時間＝間隔 ≤ 30 秒且速度 ≥ 0.6 m/s 的時間總和。
  var MAX_MOVING_GAP_S = 30, MAX_SPLIT_GAP_S = 120, STOP_SPEED_MPS = 0.6, MIN_PARTIAL_M = 100;
  function havMeters(a, b) {
    var R = 6371000, dLat = (b[1] - a[1]) * Math.PI / 180, dLon = (b[0] - a[0]) * Math.PI / 180;
    var x = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(a[1] * Math.PI / 180) * Math.cos(b[1] * Math.PI / 180) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(x)));
  }
  function validPt(p) { return p && isFinite(p[0]) && isFinite(p[1]); }
  function splits(points) {
    var out = [], cumDist = 0, cumTime = 0, lastB = 0, nextKm = 1, i;
    function mk(index, dist, sec, partial) {
      return { index: index, distanceM: dist, seconds: sec, partial: partial, avgKmh: sec > 0 ? (dist / 1000) / (sec / 3600) : 0, paceSec: dist > 0 ? sec / (dist / 1000) : 0 };
    }
    points = points || [];
    for (i = 1; i < points.length; i++) {
      var a = points[i - 1], b = points[i];
      if (!validPt(a) || !validPt(b)) continue;
      var dt = num(b[4]) - num(a[4]);
      if (!(dt > 0) || dt > MAX_SPLIT_GAP_S) continue;
      var remD = havMeters(a, b), remT = dt, target = nextKm * 1000;
      while (remD > 0 && cumDist + remD >= target - 1e-9) {
        var needD = target - cumDist, usedT = remT * (needD / remD);
        cumTime += usedT; cumDist = target;
        out.push(mk(nextKm, 1000, cumTime - lastB, false));
        lastB = cumTime; remD -= needD; remT -= usedT; nextKm++; target = nextKm * 1000;
      }
      cumDist += remD; cumTime += remT;
    }
    var rest = cumDist - (nextKm - 1) * 1000;
    if (rest >= MIN_PARTIAL_M) out.push(mk(nextKm, rest, cumTime - lastB, true));
    return out;
  }
  // 完整公里中最快與最慢；完整公里少於 3 個時不比較（資料太少沒有意義）。
  function fastestSlowest(list) {
    var full = (list || []).filter(function (s) { return !s.partial && s.seconds > 0; });
    if (full.length < 3) return null;
    var fast = full[0], slow = full[0];
    full.forEach(function (s) { if (s.seconds < fast.seconds) fast = s; if (s.seconds > slow.seconds) slow = s; });
    return { fastest: fast, slowest: slow };
  }
  function movingSeconds(points) {
    var sum = 0, i;
    points = points || [];
    for (i = 1; i < points.length; i++) {
      var a = points[i - 1], b = points[i];
      if (!validPt(a) || !validPt(b)) continue;
      var dt = num(b[4]) - num(a[4]);
      if (!(dt > 0) || dt > MAX_MOVING_GAP_S) continue;
      if (havMeters(a, b) / dt >= STOP_SPEED_MPS) sum += dt;
    }
    return sum;
  }
  // 經過時間 ＞ 活動時間（扣掉暫停）＞ 移動時間（再扣掉停等）。
  function timeBreakdown(track, movingSec) {
    var start = num(track.startTimeMs), end = track.endTimeMs != null ? num(track.endTimeMs) : start;
    var elapsed = Math.max(0, (end - start) / 1000), active = Math.max(0, num(track.durationMs) / 1000);
    var moving = Math.min(Math.max(0, movingSec), active);
    return { elapsedSec: Math.max(elapsed, active), activeSec: active, movingSec: moving, pausedSec: Math.max(0, elapsed - active), stoppedSec: Math.max(0, active - moving) };
  }

  // ───── 照片縮圖（App 使用者選擇上傳時，track.photos 每筆 [lon, lat, sec, 路徑]；沒有第 4 欄就只有拍照位置）─────
  var PHOTO_PATH_RE = /^(?!.*\.\.)(?!.*\/\/)photos\/[A-Za-z0-9._\/-]+$/; // 不允許 .. 與 //（避免路徑穿越）
  function isPhotoPath(s) { return typeof s === "string" && PHOTO_PATH_RE.test(s); }
  // 拍照點在路線上的進度（0~1，依拍照時間對應到軌跡點）。只列有縮圖的照片，依進度排序；n 是該照片在 photos 裡的序號（從 1 起）。
  function photoMarks(points, photos, cumFrac) {
    var out = [];
    points = points || [];
    (photos || []).forEach(function (p, i) {
      if (!points.length || !isPhotoPath(p[3])) return;
      var sec = num(p[2]), lo = 0, hi = points.length - 1;
      while (lo < hi) { var mid = (lo + hi) >> 1; if (num(points[mid][4]) < sec) lo = mid + 1; else hi = mid; }
      out.push({ frac: (cumFrac && cumFrac[lo]) || 0, path: p[3], n: i + 1, lon: p[0], lat: p[1] });
    });
    return out.sort(function (a, b) { return a.frac - b.frac; });
  }
  // 回放進度從 prev 走到 cur 時要顯示哪些照片。正常播放（小步前進）才彈出；拖曳／重播／倒退（大幅變動）
  // 只把指標移到目前位置、不連續彈出，並要求收起正在顯示的照片（reset）。回傳 { show, next, reset }。
  function photoStep(marks, next, prev, cur, replayOn) {
    function after(f) { var i = 0; while (i < marks.length && marks[i].frac <= f) i++; return i; }
    var jump = cur < prev || cur - prev > 0.03;
    if (jump) return { show: [], next: after(cur), reset: true };
    if (!replayOn) return { show: [], next: after(cur), reset: false };
    var show = [];
    while (next < marks.length && marks[next].frac <= cur) { show.push(marks[next]); next++; }
    return { show: show, next: next, reset: false };
  }

  // 桌面版可以注入 GT_FEATURES 關閉特定功能；沒有注入時（手機、GitHub Pages）全部維持啟用。
  // 這裡不依賴 window，讓 node 單元測試可以直接 require。
  function featureEnabled(name) {
    var features = typeof window !== "undefined" ? window.GT_FEATURES : null;
    return !features || features[name] !== false;
  }

  // 只列「實際有資料」的欄位，沒資料的不顯示（不以 0 或假資料填充）。
  function trackFields(t) {
    var f = [];
    function add(key, label, value, unit) { f.push({ key: key, label: label, value: value, unit: unit || "" }); }
    if (num(t.distanceMeters) > 0) add("distance", "距離", fmtKm(t.distanceMeters), "km");
    if (num(t.durationMs) > 0) add("duration", "活動時間", fmtDur(t.durationMs));
    if (num(t.avgSpeedMps) > 0) add("avgSpeed", "平均速度", kmh(t.avgSpeedMps).toFixed(1), "km/h");
    if (num(t.maxSpeedMps) > 0) add("maxSpeed", "最高速度", kmh(t.maxSpeedMps).toFixed(1), "km/h");
    if (featureEnabled("steps") && num(t.stepCount) > 0) {
      var src = stepSourceLabel(t.stepSource);
      add("steps", "步數" + (src ? "（" + src + "）" : ""), fmtInt(t.stepCount), "步");
    }
    if (num(t.totalAscentMeters) > 0) add("ascent", "總爬升", fmtInt(t.totalAscentMeters), "m");
    if (num(t.totalDescentMeters) > 0) add("descent", "總下降", fmtInt(t.totalDescentMeters), "m");
    if (hasAltitude(t.points) && isFinite(t.maxAltitudeMeters)) add("maxAlt", "最高海拔", fmtInt(t.maxAltitudeMeters), "m");
    if (num(t.gpsPointCount) > 0) add("points", "定位點", fmtInt(t.gpsPointCount));
    if (featureEnabled("photos") && num(t.photoCount) > 0) add("photos", "照片", fmtInt(t.photoCount), "張");
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
    MODE_GROUP: MODE_GROUP, MODE_NAME_SHORT: MODE_NAME_SHORT, modeNameShort: modeNameShort, modeGroup: modeGroup,
    modeEmoji: modeEmoji, modeName: modeName, modeOf: modeOf, modeOverridden: modeOverridden, pad: pad,
    fmtDay: fmtDay, fmtTime: fmtTime, fmtDate: fmtDate, fmtDur: fmtDur, fmtClock: fmtClock, fmtKm: fmtKm, fmtInt: fmtInt,
    kmh: kmh, stepSourceLabel: stepSourceLabel, escapeHtml: escapeHtml,
    deviceNameOf: deviceNameOf, DEVICE_KEY: DEVICE_KEY, deviceNames: deviceNames,
    getRequestedDevice: getRequestedDevice, setSelectedDevice: setSelectedDevice, chooseDevice: chooseDevice,
    filterByDevice: filterByDevice, initDeviceBar: initDeviceBar,
    activityTitle: activityTitle, filterTracks: filterTracks, sortTracks: sortTracks, groupByMonth: groupByMonth,
    downsample: downsample, routeProject: routeProject, routePath: routePath, routeTiles: routeTiles,
    isPhotoPath: isPhotoPath, photoMarks: photoMarks, photoStep: photoStep,
    splits: splits, fastestSlowest: fastestSlowest, movingSeconds: movingSeconds, timeBreakdown: timeBreakdown,
    valueSeries: valueSeries, rampColor: rampColor, valueRange: valueRange, colorSegments: colorSegments, highlights: highlights, RAMPS: RAMPS, trackFields: trackFields, hasAltitude: hasAltitude,
    modeBreakdown: modeBreakdown, gpx: gpx, elapsedAt: elapsedAt
  };
});
