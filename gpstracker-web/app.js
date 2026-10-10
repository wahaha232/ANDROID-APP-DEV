// GPS Tracker 網頁儀表板：讀取 repo 內的 data/manifest.json 與各筆 <trackId>.json，
// 全部使用 0 元、免金鑰的圖資（Esri 衛星 + AWS Terrarium DEM），純前端、無後端。
(function () {
  "use strict";

  var params = new URLSearchParams(location.search);
  var CONFIG = {
    repo: params.get("repo") || "wahaha232/ANDROID-APP-DEV",
    branch: params.get("branch") || "main",
    dataPath: params.get("data") || "gpstracker-web/data"
  };
  var RAW = "https://raw.githubusercontent.com/" + CONFIG.repo + "/" + CONFIG.branch + "/" + CONFIG.dataPath + "/";

  var BASE_TILES = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}";
  var DEM_TILES = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png";

  var manifest = { tracks: [] };
  var trackCache = {};
  var selected = null;
  var map = null;

  var MODE_EMOJI = {
    WALKING: "🚶", RUNNING: "🏃", BICYCLE: "🚲", CYCLING: "🚲", VEHICLE: "🚗", DRIVING: "🚗",
    CAR: "🚗", MOTORCYCLE: "🏍️", TRAIN: "🚆", BUS: "🚌", BOAT: "⛵", STATIONARY: "🧍", UNKNOWN: "❓"
  };
  var MODE_NAME = {
    WALKING: "步行", RUNNING: "跑步", BICYCLE: "單車", CYCLING: "單車", VEHICLE: "車輛", DRIVING: "開車",
    CAR: "開車", MOTORCYCLE: "機車", TRAIN: "火車", BUS: "公車", BOAT: "船", STATIONARY: "靜止", UNKNOWN: "未知"
  };

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
          base: { type: "raster", tiles: [BASE_TILES], tileSize: 256 },
          dem: { type: "raster-dem", tiles: [DEM_TILES], encoding: "terrarium", tileSize: 256 }
        },
        layers: [
          { id: "base", type: "raster", source: "base" },
          { id: "hillshade", type: "hillshade", source: "dem" }
        ]
      },
      center: [121.0, 23.5], zoom: 1.6, pitch: 0, attributionControl: false, antialias: true
    });
    map.on("load", function () {
      map.setTerrain({ source: "dem", exaggeration: 1.3 });
      map.addSource("route", { type: "geojson", data: emptyLine() });
      map.addLayer({ id: "route-line", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#00E676", "line-width": 5 } });
      map.addSource("allroutes", { type: "geojson", data: emptyFC() });
      map.addLayer({ id: "allroutes-line", type: "line", source: "allroutes", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#2F6BFF", "line-width": 3, "line-opacity": 0.8 } });
      map.addSource("photos", { type: "geojson", data: emptyFC() });
      map.addLayer({ id: "photos-dot", type: "circle", source: "photos", paint: { "circle-radius": 5, "circle-color": "#FFB300", "circle-stroke-color": "#fff", "circle-stroke-width": 1.5 } });
      loadManifest();
    });
  }
  function emptyLine() { return { type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: [] } }; }
  function emptyFC() { return { type: "FeatureCollection", features: [] }; }

  // ---- 資料 ----
  function loadManifest() {
    fetch(RAW + "manifest.json?_=" + Date.now())
      .then(function (r) { if (!r.ok) throw new Error("manifest " + r.status); return r.json(); })
      .then(function (m) {
        manifest = m || { tracks: [] };
        manifest.tracks = (manifest.tracks || []).slice().sort(function (a, b) { return b.startTimeMs - a.startTimeMs; });
        buildModeFilter();
        renderSummary();
        renderList();
      })
      .catch(function (e) {
        $("summary").textContent = "讀取 manifest 失敗：" + e.message + "（請確認 repo/branch/data 路徑與 PAT 設定）";
      });
  }

  function loadTrack(trackId) {
    if (trackCache[trackId]) return Promise.resolve(trackCache[trackId]);
    return fetch(RAW + trackId + ".json?_=" + Date.now())
      .then(function (r) { if (!r.ok) throw new Error("track " + r.status); return r.json(); })
      .then(function (t) { trackCache[trackId] = t; return t; });
  }

  function renderSummary() {
    var totalDist = 0, totalDur = 0, totalAscent = 0;
    manifest.tracks.forEach(function (t) {
      totalDist += t.distanceMeters || 0; totalDur += t.durationMs || 0; totalAscent += t.totalAscentMeters || 0;
    });
    $("summary").textContent = manifest.tracks.length + " 筆紀錄 · 總里程 " + (totalDist / 1000).toFixed(2) +
      " km · 總時間 " + fmtDur(totalDur) + " · 總爬升 " + Math.round(totalAscent) + " m";
  }

  function buildModeFilter() {
    var set = {};
    manifest.tracks.forEach(function (t) { if (t.dominantMode) set[t.dominantMode] = true; });
    var sel = $("modeFilter");
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
      if (selected === t.trackId) li.className = "active";
      li.innerHTML =
        '<div class="t-date">' + fmtDate(t.startTimeMs) + '</div>' +
        '<div class="t-main">' + modeEmoji(t.dominantMode) + " " + ((t.distanceMeters || 0) / 1000).toFixed(2) + ' km</div>' +
        '<div class="t-sub">' + fmtDur(t.durationMs || 0) + ' · ' + modeName(t.dominantMode) + '</div>';
      li.onclick = function () { selectTrack(t.trackId); };
      list.appendChild(li);
    });
    $("listEmpty").style.display = filtered.length ? "none" : "block";
  }

  function selectTrack(trackId) {
    selected = trackId;
    $("hint").style.display = "none";
    renderList();
    loadTrack(trackId).then(function (t) {
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

    var pts = t.points || [];
    drawChart($("speedChart"), pts.map(function (p) { return p[3] || 0; }), "#2F6BFF", "km/h");
    drawChart($("altChart"), pts.map(function (p) { return p[2] || 0; }), "#00E676", "m");

    var actions = $("detailActions");
    actions.innerHTML = "";
    var a3d = document.createElement("a");
    a3d.className = "btn";
    a3d.textContent = "▶ 3D 回放";
    a3d.href = "flythrough.html?track=" + encodeURIComponent(t.trackId) +
      "&repo=" + encodeURIComponent(CONFIG.repo) +
      "&branch=" + encodeURIComponent(CONFIG.branch) +
      "&data=" + encodeURIComponent(CONFIG.dataPath);
    a3d.target = "_blank";
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

  function drawChart(canvas, values, color, unit) {
    var dpr = window.devicePixelRatio || 1;
    var w = canvas.clientWidth || 600;
    var h = canvas.height / dpr || 140;
    canvas.width = w * dpr; canvas.height = h * dpr;
    var ctx = canvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    if (!values.length) return;
    var min = Math.min.apply(null, values), max = Math.max.apply(null, values);
    if (max === min) max = min + 1;
    var pad = 8;
    ctx.beginPath();
    ctx.strokeStyle = color; ctx.lineWidth = 2;
    values.forEach(function (v, i) {
      var x = pad + (w - 2 * pad) * (i / Math.max(1, values.length - 1));
      var y = h - pad - (h - 2 * pad) * ((v - min) / (max - min));
      if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    });
    ctx.stroke();
    // 填色
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

  function renderAllRoutes() {
    var on = $("showAll").checked;
    $("hint").style.display = on ? "none" : (selected ? "none" : "block");
    if (!on) { map.getSource("allroutes").setData(emptyFC()); return; }
    var features = [];
    var pending = manifest.tracks.map(function (t) { return loadTrack(t.trackId); });
    Promise.all(pending).then(function (all) {
      all.forEach(function (t) {
        var coords = (t.points || []).map(function (p) { return [p[0], p[1]]; });
        if (coords.length) features.push({ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: coords } });
      });
      map.getSource("allroutes").setData({ type: "FeatureCollection", features: features });
    });
  }

  $("search").addEventListener("input", renderList);
  $("modeFilter").addEventListener("change", renderList);
  $("showAll").addEventListener("change", renderAllRoutes);
  $("closeDetail").addEventListener("click", function () { $("detail").classList.add("hidden"); });

  if (typeof maplibregl === "undefined") {
    $("summary").textContent = "找不到 MapLibre（請確認 vendor/maplibre-gl.js 是否存在）";
  } else {
    initMap();
  }
})();
