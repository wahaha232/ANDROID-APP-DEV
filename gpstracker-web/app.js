// GPS Tracker 網頁儀表板：讀取同源的 data/manifest.json 與各筆 <trackId>.json，
// 全部使用 0 元、免金鑰的圖資（Esri 衛星 + AWS Terrarium DEM），純前端、無後端。
(function () {
  "use strict";

  var CONFIG = GPST.resolveConfig();
  var DATA_BASE = CONFIG.dataBase;

  var BASE_TILES = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}";
  var DEM_TILES = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png";
  var BASE_ATTR = "Esri, Maxar, Earthstar Geographics, GIS User Community";
  var DEM_ATTR = "Elevation: Mapzen / AWS Terrain Tiles";

  var MAX_ALL_ROUTE_POINTS = 400; // 「顯示全部」每筆最多取樣點數，避免大檔拖慢渲染
  var ALL_CONCURRENCY = 4;        // 「顯示全部」同時下載的軌跡檔數上限
  var SEARCH_DEBOUNCE_MS = 150;

  var manifest = { tracks: [] };
  var trackCache = {};
  var selected = null;
  var currentTrack = null;
  var map = null;
  var allRoutesLoaded = false;

  var MODE_EMOJI = {
    WALKING: "🚶", RUNNING: "🏃", BICYCLE: "🚲", CYCLING: "🚲", VEHICLE: "🚗", DRIVING: "🚗",
    CAR: "🚗", MOTORCYCLE: "🏍️", TRAIN: "🚆", BUS: "🚌", BOAT: "⛵", STATIONARY: "🧍", UNKNOWN: "❓"
  };
  var MODE_NAME = {
    WALKING: "步行", RUNNING: "跑步", BICYCLE: "單車", CYCLING: "單車", VEHICLE: "車輛", DRIVING: "開車",
    CAR: "開車", MOTORCYCLE: "機車", TRAIN: "火車", BUS: "公車", BOAT: "船", STATIONARY: "靜止", UNKNOWN: "未知"
  };

  // 趣味競賽／里程碑：距離以公里為單位（總里程進度）。
  var QUESTS = [
    { name: "🌕 走到月球", km: 384400 },
    { name: "☀️ 走到太陽", km: 149597870 },
    { name: "🌍 繞地球一圈", km: 40075 },
    { name: "🛰️ 爬上 ISS 軌道", km: 400 },
    { name: "🏃 跑完一場馬拉松", km: 42.195 },
    { name: "💡 光走 1 秒", km: 299792 }
  ];

  function $(id) { return document.getElementById(id); }
  function modeEmoji(m) { return MODE_EMOJI[m] || "❓"; }
  function modeName(m) { return MODE_NAME[m] || m || "未知"; }
  function fmtDate(ms) {
    var d = new Date(ms);
    return d.getFullYear() + "/" + pad(d.getMonth() + 1) + "/" + pad(d.getDate()) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes());
  }
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function fmtDur(ms) {
    var s = Math.floor(ms / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
    return h > 0 ? (h + "h " + m + "m") : (m + "m");
  }

  // ---- 地圖 ----
  function initMap() {
    map = new maplibregl.Map({
      container: "map",
      style: {
        version: 8, projection: { type: "globe" }, sky: {},
        sources: {
          base: { type: "raster", tiles: [BASE_TILES], tileSize: 256, attribution: BASE_ATTR },
          dem: { type: "raster-dem", tiles: [DEM_TILES], encoding: "terrarium", tileSize: 256, attribution: DEM_ATTR }
        },
        layers: [
          { id: "base", type: "raster", source: "base" },
          { id: "hillshade", type: "hillshade", source: "dem" }
        ]
      },
      center: [121.0, 23.5], zoom: 1.6, pitch: 0, antialias: true
    });
    map.on("load", function () {
      map.setTerrain({ source: "dem", exaggeration: 1.3 });
      map.addSource("route", { type: "geojson", data: GPST.emptyLine() });
      map.addLayer({ id: "route-line", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#00E676", "line-width": 5 } });
      map.addSource("allroutes", { type: "geojson", data: GPST.emptyFC() });
      map.addLayer({ id: "allroutes-line", type: "line", source: "allroutes", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#2F6BFF", "line-width": 3, "line-opacity": 0.8 } });
      map.addSource("photos", { type: "geojson", data: GPST.emptyFC() });
      map.addLayer({ id: "photos-dot", type: "circle", source: "photos", paint: { "circle-radius": 5, "circle-color": "#FFB300", "circle-stroke-color": "#fff", "circle-stroke-width": 1.5 } });
      loadManifest();
    });
  }

  // ---- 資料 ----
  function fetchJson(name, revalidate) {
    var opts = revalidate ? { cache: "no-cache" } : undefined;
    return fetch(DATA_BASE + name, opts).then(function (r) {
      if (!r.ok) throw new Error(name + " " + r.status);
      return r.json();
    });
  }

  function loadManifest() {
    fetchJson("manifest.json", true)
      .then(function (m) {
        manifest = m || { tracks: [] };
        manifest.tracks = (manifest.tracks || []).slice().sort(function (a, b) { return b.startTimeMs - a.startTimeMs; });
        buildModeFilter();
        renderSummary();
        renderList();
        // 支援從競賽中心以 ?track=<id> 直接開啟某筆紀錄。
        var want = new URLSearchParams(location.search).get("track");
        if (want && manifest.tracks.some(function (t) { return t.trackId === want; })) selectTrack(want);
      })
      .catch(function (e) {
        $("summary").textContent = "讀取 manifest 失敗：" + e.message;
      });
  }

  function loadTrack(trackId) {
    if (trackCache[trackId]) return Promise.resolve(trackCache[trackId]);
    return fetchJson(trackId + ".json", false).then(function (t) { trackCache[trackId] = t; return t; });
  }

  function renderSummary() {
    var totalDist = 0, totalDur = 0, totalAscent = 0;
    manifest.tracks.forEach(function (t) {
      totalDist += t.distanceMeters || 0; totalDur += t.durationMs || 0; totalAscent += t.totalAscentMeters || 0;
    });
    $("summary").textContent = manifest.tracks.length + " 筆紀錄 · 總里程 " + (totalDist / 1000).toFixed(2) +
      " km · 總時間 " + fmtDur(totalDur) + " · 總爬升 " + Math.round(totalAscent) + " m";
    renderQuests(totalDist / 1000);
  }

  function fmtKm(v) { return v.toLocaleString("zh-Hant", { maximumFractionDigits: 1 }); }

  function fmtPct(pct) {
    if (pct >= 100) return "100%";
    if (pct >= 1) return pct.toFixed(2) + "%";
    if (pct >= 0.01) return pct.toFixed(4) + "%";
    return pct.toFixed(6) + "%";
  }

  function renderQuests(totalKm) {
    var box = $("quests");
    box.innerHTML = "";
    var title = document.createElement("div");
    title.className = "quests-title";
    title.textContent = "🎮 趣味競賽（依總里程）";
    box.appendChild(title);
    QUESTS.forEach(function (q) {
      var pct = Math.min(100, (totalKm / q.km) * 100);
      var done = totalKm >= q.km;

      var el = document.createElement("div");
      el.className = "quest" + (done ? " done" : "");

      var head = document.createElement("div");
      head.className = "q-head";
      var t = document.createElement("span");
      t.className = "q-title"; t.textContent = q.name;
      var p = document.createElement("span");
      p.className = "q-pct"; p.textContent = fmtPct(pct);
      head.appendChild(t); head.appendChild(p);

      var bar = document.createElement("div");
      bar.className = "q-bar";
      var fill = document.createElement("i");
      fill.style.width = pct.toFixed(2) + "%";
      bar.appendChild(fill);

      var sub = document.createElement("div");
      sub.className = "q-sub";
      sub.textContent = done ? "已達成！🎉" : "還要 " + fmtKm(q.km - totalKm) + " km";

      el.appendChild(head); el.appendChild(bar); el.appendChild(sub);
      box.appendChild(el);
    });
  }

  function buildModeFilter() {
    var set = {};
    manifest.tracks.forEach(function (t) { if (t.dominantMode) set[t.dominantMode] = true; });
    var sel = $("modeFilter");
    sel.length = 1; // 只保留「全部模式」
    Object.keys(set).forEach(function (m) {
      var o = document.createElement("option");
      o.value = m; o.textContent = modeEmoji(m) + " " + modeName(m);
      sel.appendChild(o);
    });
  }

  function renderList() {
    var q = $("search").value.trim().toLowerCase();
    var mf = $("modeFilter").value;
    var list = $("trackList");
    list.innerHTML = "";
    var filtered = manifest.tracks.filter(function (t) {
      if (mf && t.dominantMode !== mf) return false;
      if (q && fmtDate(t.startTimeMs).toLowerCase().indexOf(q) < 0 && String(t.dominantMode).toLowerCase().indexOf(q) < 0) return false;
      return true;
    });
    filtered.forEach(function (t) {
      var li = document.createElement("li");
      li.dataset.id = t.trackId;
      li.tabIndex = 0;
      li.setAttribute("role", "button");
      if (selected === t.trackId) li.className = "active";

      var d = document.createElement("div");
      d.className = "t-date"; d.textContent = fmtDate(t.startTimeMs);
      var m = document.createElement("div");
      m.className = "t-main"; m.textContent = modeEmoji(t.dominantMode) + " " + ((t.distanceMeters || 0) / 1000).toFixed(2) + " km";
      var s = document.createElement("div");
      s.className = "t-sub"; s.textContent = fmtDur(t.durationMs || 0) + " · " + modeName(t.dominantMode);
      li.appendChild(d); li.appendChild(m); li.appendChild(s);

      li.onclick = function () { selectTrack(t.trackId); };
      li.onkeydown = function (e) {
        if (e.key === "Enter" || e.key === " ") { e.preventDefault(); selectTrack(t.trackId); }
      };
      list.appendChild(li);
    });
    $("listEmpty").style.display = filtered.length ? "none" : "block";
  }

  function selectTrack(trackId) {
    selected = trackId;
    $("hint").style.display = "none";
    renderList();
    loadTrack(trackId).then(function (t) {
      currentTrack = t;
      drawTrack(t);
      renderDetail(t);
    }).catch(function (e) {
      $("detailTitle").textContent = "讀取失敗：" + e.message;
    });
  }

  function drawTrack(t) {
    var coords = (t.points || []).map(function (p) { return [p[0], p[1]]; });
    map.getSource("route").setData({ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: coords } });
    var photos = (t.photos || []).map(function (p) {
      return { type: "Feature", properties: {}, geometry: { type: "Point", coordinates: [p[0], p[1]] } };
    });
    map.getSource("photos").setData({ type: "FeatureCollection", features: photos });
    if (coords.length) {
      var b = new maplibregl.LngLatBounds();
      coords.forEach(function (c) { b.extend(c); });
      map.fitBounds(b, { padding: 70, duration: 800, pitch: 45, bearing: 0 });
    }
  }

  function stat(v, l) { return '<div class="stat"><div class="v">' + v + '</div><div class="l">' + l + '</div></div>'; }

  function renderDetail(t) {
    $("detail").classList.remove("hidden");
    $("detailTitle").textContent = modeEmoji(t.dominantMode) + " " + fmtDate(t.startTimeMs) + " · " + modeName(t.dominantMode);
    $("statGrid").innerHTML =
      stat(((t.distanceMeters || 0) / 1000).toFixed(2), "距離 (km)") +
      stat(fmtDur(t.durationMs || 0), "持續時間") +
      stat(((t.avgSpeedMps || 0) * 3.6).toFixed(1), "平均速度 (km/h)") +
      stat(((t.maxSpeedMps || 0) * 3.6).toFixed(1), "最高速度 (km/h)") +
      stat((t.stepCount || 0), "步數") +
      stat(Math.round(t.totalAscentMeters || 0), "總爬升 (m)") +
      stat(Math.round(t.totalDescentMeters || 0), "總下降 (m)") +
      stat((t.gpsPointCount || 0), "定位點") +
      stat((t.photoCount || 0), "照片");

    drawCharts(t);

    var actions = $("detailActions");
    actions.innerHTML = "";
    var a3d = document.createElement("a");
    a3d.className = "btn";
    a3d.textContent = "▶ 3D 回放";
    a3d.href = "flythrough.html?track=" + encodeURIComponent(t.trackId) + linkOverrideParams();
    a3d.target = "_blank";
    a3d.rel = "noopener";
    actions.appendChild(a3d);
    var gpxBtn = document.createElement("button");
    gpxBtn.className = "btn secondary";
    gpxBtn.textContent = "⬇ 下載 GPX";
    gpxBtn.onclick = function () { downloadGpx(t); };
    actions.appendChild(gpxBtn);

    var strip = $("photoStrip");
    strip.innerHTML = "";
    if ((t.photos || []).length) {
      var note = document.createElement("div");
      note.style.fontSize = "12px"; note.style.color = "var(--muted)";
      note.textContent = "拍照點 " + t.photos.length + " 個（已在地圖上以黃點標示）";
      strip.appendChild(note);
    }
  }

  // 只有使用自訂 repo/branch/data 時才把參數帶給 3D 回放頁；預設同源相對路徑不需傳。
  function linkOverrideParams() {
    if (!CONFIG.repo && CONFIG.dataPath === "data" && CONFIG.branch === "main") return "";
    return "&repo=" + encodeURIComponent(CONFIG.repo) +
      "&branch=" + encodeURIComponent(CONFIG.branch) +
      "&data=" + encodeURIComponent(CONFIG.dataPath);
  }

  function drawCharts(t) {
    var pts = t.points || [];
    var speed = new Array(pts.length), alt = new Array(pts.length);
    for (var i = 0; i < pts.length; i++) { speed[i] = pts[i][3] || 0; alt[i] = pts[i][2] || 0; }
    drawChart($("speedChart"), speed, "#2F6BFF", "km/h");
    drawChart($("altChart"), alt, "#00E676", "m");
  }

  function drawChart(canvas, values, color, unit) {
    var dpr = window.devicePixelRatio || 1;
    var w = canvas.clientWidth || 600;
    var h = 140;
    canvas.width = w * dpr; canvas.height = h * dpr;
    var ctx = canvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    if (!values.length) return;
    var min = values[0], max = values[0];
    for (var i = 1; i < values.length; i++) {
      if (values[i] < min) min = values[i];
      if (values[i] > max) max = values[i];
    }
    if (max === min) max = min + 1;
    var pad = 8;
    ctx.beginPath();
    ctx.strokeStyle = color; ctx.lineWidth = 2;
    for (var j = 0; j < values.length; j++) {
      var x = pad + (w - 2 * pad) * (j / Math.max(1, values.length - 1));
      var y = h - pad - (h - 2 * pad) * ((values[j] - min) / (max - min));
      if (j === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.stroke();
    ctx.lineTo(w - pad, h - pad); ctx.lineTo(pad, h - pad); ctx.closePath();
    ctx.fillStyle = color + "22"; ctx.fill();
    ctx.fillStyle = "#9aa7bd"; ctx.font = "11px sans-serif";
    ctx.fillText(max.toFixed(0) + " " + unit, pad, 12);
    ctx.fillText(min.toFixed(0), pad, h - 3);
  }

  function downloadGpx(t) {
    var pts = t.points || [];
    var baseMs = t.startTimeMs || 0;
    var gpx = '<?xml version="1.0" encoding="UTF-8"?>\n' +
      '<gpx version="1.1" creator="GPS Tracker" xmlns="http://www.topografix.com/GPX/1/1">\n' +
      '<metadata><name>' + fmtDate(t.startTimeMs) + '</name><time>' + new Date(baseMs).toISOString() + '</time></metadata>\n' +
      '<trk><name>' + fmtDate(t.startTimeMs) + '</name><trkseg>\n';
    pts.forEach(function (p) {
      var time = new Date(baseMs + (p[4] || 0) * 1000).toISOString();
      gpx += '<trkpt lat="' + p[1] + '" lon="' + p[0] + '"><ele>' + (p[2] || 0) + '</ele><time>' + time + '</time></trkpt>\n';
    });
    gpx += '</trkseg></trk></gpx>\n';
    var blob = new Blob([gpx], { type: "application/gpx+xml" });
    var url = URL.createObjectURL(blob);
    var a = document.createElement("a");
    a.href = url;
    a.download = t.trackId + ".gpx";
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  function downsampleCoords(coords, max) {
    if (coords.length <= max) return coords;
    var out = [], step = (coords.length - 1) / (max - 1);
    for (var i = 0; i < max; i++) out.push(coords[Math.round(i * step)]);
    return out;
  }

  // 限制同時下載數量，避免一次開太多請求。
  function loadTracksLimited(ids, limit) {
    return new Promise(function (resolve) {
      var results = new Array(ids.length), i = 0, active = 0, done = 0;
      if (!ids.length) { resolve(results); return; }
      function kick() {
        while (active < limit && i < ids.length) {
          (function (idx) {
            active++;
            loadTrack(ids[idx])
              .then(function (t) { results[idx] = t; })
              .catch(function () { results[idx] = null; })
              .then(function () { active--; done++; if (done === ids.length) resolve(results); else kick(); });
          })(i++);
        }
      }
      kick();
    });
  }

  function renderAllRoutes() {
    var on = $("showAll").checked;
    $("hint").style.display = on ? "none" : (selected ? "none" : "block");
    if (!on) {
      map.getSource("allroutes").setData(GPST.emptyFC());
      return;
    }
    if (allRoutesLoaded) return;
    $("hint").textContent = "載入全部軌跡中…";
    $("hint").style.display = "block";
    var ids = manifest.tracks.map(function (t) { return t.trackId; });
    loadTracksLimited(ids, ALL_CONCURRENCY).then(function (all) {
      var features = [];
      all.forEach(function (t) {
        if (!t) return;
        var coords = downsampleCoords((t.points || []).map(function (p) { return [p[0], p[1]]; }), MAX_ALL_ROUTE_POINTS);
        if (coords.length) features.push({ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: coords } });
      });
      map.getSource("allroutes").setData({ type: "FeatureCollection", features: features });
      allRoutesLoaded = true;
      $("hint").textContent = "← 選擇左側紀錄以查看分析，或勾選「顯示全部軌跡」。";
      $("hint").style.display = selected ? "none" : "block";
    });
  }

  // 搜尋輸入 debounce，避免每個按鍵都重繪清單。
  var searchTimer = null;
  $("search").addEventListener("input", function () {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(renderList, SEARCH_DEBOUNCE_MS);
  });
  $("modeFilter").addEventListener("change", renderList);
  $("showAll").addEventListener("change", renderAllRoutes);
  $("closeDetail").addEventListener("click", function () { $("detail").classList.add("hidden"); });

  // 視窗尺寸改變時重繪圖表（圖表寬度依容器而定）。
  var resizeTimer = null;
  window.addEventListener("resize", function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      if (currentTrack && !$("detail").classList.contains("hidden")) drawCharts(currentTrack);
    }, 150);
  });

  if (typeof maplibregl === "undefined") {
    $("summary").textContent = "找不到 MapLibre（請確認 vendor/maplibre-gl.js 是否存在）";
  } else {
    initMap();
  }
})();
