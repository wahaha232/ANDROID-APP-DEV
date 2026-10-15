// 3D 路線工作區：同一個 MapLibre 地圖，三種模式 —— 自由探索 / 旅程回放 / 活動數據。
// 「旅程回放」的路線計算（hav / bearing / pointAt / applyFrame 的鏡頭參數 / 速度倍率）沿用原本 flythrough.html 的實作，
// 這裡只在外面加上：模式切換、鏡頭跟隨/鳥瞰、手動操作時暫停跟隨、起終點、剖面圖、資訊面板。
(function () {
  "use strict";

  var C = GTCore, esc = C.escapeHtml;
  var params = new URLSearchParams(location.search);
  var trackId = params.get("track");
  var MODES = ["explore", "replay", "data"];
  var initialMode = MODES.indexOf(params.get("mode")) >= 0 ? params.get("mode") : "replay"; // 沒帶 mode＝舊連結：照舊自動回放
  var SPEEDS = [1, 2, 4, 8, 16];
  var DISPLAY_MAX_POINTS = 2000; // 軌跡「顯示線」的最大點數；位置／HUD 仍用完整資料
  var FOLLOW = { pitch: 60, zoom: 15.2 };      // 跟隨鏡頭（與原本相同）
  var DEFAULT_PITCH = 55;                      // 自由探索的預設傾斜角
  var reduceMotion = !!(window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches);
  var narrowMq = window.matchMedia("(max-width: 899px)");

  var track = null, meta = {};
  var coords = [], hudData = [], cum = [], cumFrac = [], total = 0, totalSec = 0;
  var dispCoords = [], dispStep = 1;
  var progress = 0, playing = false, speed = 4, lastTs = 0, lastTravel = 0, rafId = 0;
  var mode = null, camMode = "follow";
  var map = null, bounds = null, profAlt = null, profSpd = null;
  var hasAlt = false;
  // GPS 中斷（捷運）：rawTrack＝原始軌跡（匯出用）；track＝插入估算路徑後、只供顯示／回放的版本。
  var rawTrack = null, gapInfo = { gaps: [], gapEdge: null, usedMetro: false }, dispGap = [];

  function $(id) { return document.getElementById(id); }
  function emptyLine() { return GPST.emptyLine(); }
  function emptyFC() { return GPST.emptyFC(); }
  function lineOf(cs) { return { type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: cs } }; }
  // 實線路線在「GPS 中斷」邊上斷開（中斷段另用虛線圖層畫）。
  function runFeature(cs, flags) { return { type: "Feature", properties: {}, geometry: { type: "MultiLineString", coordinates: GT.splitRuns(cs, flags) } }; }
  // 顯示線（已抽樣）每個頂點對應的「到此點的邊是否屬於中斷段」。
  function buildDispGap() {
    var ge = gapInfo.gapEdge, last = 0, n = dispCoords.length;
    dispGap = [];
    for (var j = 0; j < n; j++) {
      var orig = j === n - 1 ? coords.length - 1 : Math.min(j * dispStep, coords.length - 1), f = false;
      if (ge && j > 0) for (var m = last + 1; m <= orig; m++) if (ge[m]) { f = true; break; }
      dispGap.push(f); last = orig;
    }
  }

  // ===== 路線計算（原 flythrough.html，未更動）=====
  function hav(a, b) {
    var R = 6371000, dLat = (b[1] - a[1]) * Math.PI / 180, dLon = (b[0] - a[0]) * Math.PI / 180;
    var la1 = a[1] * Math.PI / 180, la2 = b[1] * Math.PI / 180;
    var h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
  }
  function bearing(a, b) {
    var la1 = a[1] * Math.PI / 180, la2 = b[1] * Math.PI / 180, dLon = (b[0] - a[0]) * Math.PI / 180;
    var y = Math.sin(dLon) * Math.cos(la2);
    var x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dLon);
    return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
  }
  function pointAt(f) {
    if (!coords.length) return null;
    if (coords.length === 1 || total === 0) return { pos: coords[0], bearing: 0, i: 0, t: 0 };
    var target = f * total;
    // 二分搜尋：找出第一個 cum[i] >= target 的 i（cum 為累積距離，遞增）。
    var lo = 1, hi = cum.length - 1;
    while (lo < hi) { var mid = (lo + hi) >> 1; if (cum[mid] < target) lo = mid + 1; else hi = mid; }
    var i = Math.min(lo, coords.length - 1);
    var a = coords[i - 1], b = coords[i], segLen = cum[i] - cum[i - 1];
    var t = segLen > 0 ? (target - cum[i - 1]) / segLen : 0;
    return { pos: [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t], bearing: bearing(a, b), i: i, t: t };
  }
  function lerp(a, b, t) { return a + (b - a) * t; }

  function prepareTrack(t) {
    var pts = t.points || [];
    coords = pts.map(function (p) { return [p[0], p[1]]; });
    hudData = pts.map(function (p) { return [p[3] || 0, p[2] || 0]; });
    cum = [0]; total = 0;
    for (var i = 1; i < coords.length; i++) { total += hav(coords[i - 1], coords[i]); cum.push(total); }
    cumFrac = cum.map(function (v) { return total > 0 ? v / total : 0; });
    totalSec = pts.length ? (pts[pts.length - 1][4] || 0) : 0;
    // 長軌跡（上萬點）每幀重建並序列化整條線很吃 CPU：顯示用的線抽樣到最多 DISPLAY_MAX_POINTS 點。
    dispStep = Math.max(1, Math.ceil(coords.length / DISPLAY_MAX_POINTS));
    dispCoords = [];
    for (var j = 0; j < coords.length; j += dispStep) dispCoords.push(coords[j]);
    if (coords.length && dispCoords[dispCoords.length - 1] !== coords[coords.length - 1]) dispCoords.push(coords[coords.length - 1]);
    hasAlt = C.hasAltitude(pts);
  }

  // ===== 狀態顯示 =====
  function showStatus(html, isErr) {
    var s = $("status");
    s.className = "ws-status" + (isErr ? " err" : "");
    s.innerHTML = html;
    s.style.display = "";
  }
  function hideStatus() { $("status").style.display = "none"; }
  var toastTimer = 0;
  function toast(msg) {
    if (!msg) return;
    var t = $("toast");
    t.textContent = msg; t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.hidden = true; }, 2600);
  }

  // ===== 面板內容 =====
  var MODE_COLOR = C.MODE_COLOR || {};
  function renderStats() {
    var fields = C.trackFields(track);
    if (mode !== "data") fields = fields.filter(function (f) { return ["distance", "duration", "steps", "ascent"].indexOf(f.key) >= 0; });
    $("pStats").innerHTML = fields.map(function (f) {
      return '<div class="gt-stat"><div class="v">' + esc(f.value) + (f.unit ? "<small>" + esc(f.unit) + "</small>" : "") +
        '</div><div class="l">' + esc(f.label) + "</div></div>";
    }).join("");
  }

  function fillPanel() {
    var title = C.activityTitle(track, meta);
    document.title = title + " · 3D 路線工作區";
    $("pTitle").textContent = title;
    $("hTitle").textContent = title;
    var sub = C.fmtDate(track.startTimeMs) + "・" + C.modeName(C.modeOf(track, meta)) +
      (track.distanceMeters > 0 ? "・" + C.fmtKm(track.distanceMeters) + " km" : "");
    $("hSub").textContent = sub;
    $("pMeta").innerHTML = '<span class="gt-chip">' + C.modeEmoji(C.modeOf(track, meta)) + " " + esc(C.modeName(C.modeOf(track, meta))) + "</span><span>" + esc(C.fmtDate(track.startTimeMs)) + "</span>";
    renderStats();

    // 海拔剖面：只有真的有海拔資料才顯示
    if (hasAlt) {
      $("blkAlt").hidden = false;
      profAlt = GTProfile.create($("profAlt"), { color: "#22d3ee", unit: "m", label: "海拔剖面",
        onHover: function (f) { if (mode === "data") previewAt(f); },
        onSelect: function (f) { seekTo(f); } });
      profAlt.setData(cumFrac, C.altitudeSeries(track.points));
    }
    var hasSpeed = hudData.some(function (h) { return h[0] > 0; });
    if (hasSpeed) {
      $("blkSpd").hidden = false;
      profSpd = GTProfile.create($("profSpd"), { color: "#4c8dff", unit: "km/h", label: "速度剖面",
        onHover: function (f) { if (mode === "data") previewAt(f); },
        onSelect: function (f) { seekTo(f); } });
      profSpd.setData(cumFrac, hudData.map(function (h) { return h[0]; }));
    }

    var mb = C.modeBreakdown(track.segments);
    if (mb.length) {
      $("blkModes").hidden = false;
      $("modeBar").innerHTML = mb.map(function (m) {
        return '<i style="width:' + m.pct.toFixed(2) + "%;background:" + (MODE_COLOR[m.mode] || "#94a3b8") + '" title="' + esc(C.modeName(m.mode)) + '"></i>';
      }).join("");
      $("modeLegend").innerHTML = mb.map(function (m) {
        return '<li><i style="background:' + (MODE_COLOR[m.mode] || "#94a3b8") + '"></i>' + C.modeEmoji(m.mode) + " " + esc(C.modeName(m.mode)) + " " + C.fmtDur(m.sec * 1000) + "（" + m.pct.toFixed(0) + "%）</li>";
      }).join("");
    }
    var photos = track.photos || [];
    if (photos.length) {
      $("blkPhotos").hidden = false;
      $("photoNote").textContent = "共 " + photos.length + " 個拍照點，已在地圖上以黃點標示。網站只保存拍攝位置，照片檔案留在手機。";
    }

    $("aStory").href = GT.activityUrl(trackId);
    $("back").href = GT.activityUrl(trackId);
    // 匯出一律用原始軌跡（估算路徑只用於顯示，不寫進 GPX）。
    $("aGpx").onclick = function () { GT.saveBlob(trackId + ".gpx", C.gpx(rawTrack || track), "application/gpx+xml"); };
    if (gapInfo.gaps.length) {
      $("blkGaps").hidden = false;
      $("gapBody").innerHTML = GT.gapSummaryHtml(gapInfo.gaps, gapInfo.usedMetro, track.distanceMeters);
    }
    $("aShare").onclick = function () { GT.share(GT.url("activity.html", { track: trackId }), title).then(toast); };
  }

  // ===== 地圖 =====
  function routeBounds() {
    var b = new maplibregl.LngLatBounds();
    coords.forEach(function (c) { b.extend(c); });
    return b;
  }
  function viewPadding() {
    var narrow = narrowMq.matches, bottom = 44;
    if (mode === "replay") { var c = $("controls"); bottom = (c && c.offsetHeight ? c.offsetHeight : 96) + 44; }
    // 手機的底部面板展開時會蓋住地圖下半部：把路線讓到上方可見區域。
    if (narrow && document.body.classList.contains("sheet-open")) bottom += Math.round(window.innerHeight * 0.64 - 84) + 28;
    return { top: narrow ? 64 : 72, bottom: bottom, left: 40, right: 64 };
  }
  function viewRoute(o) {
    o = o || {};
    var opt = { padding: viewPadding(), maxZoom: 17, duration: (reduceMotion || o.instant) ? 0 : 700 };
    if (o.pitch != null) opt.pitch = o.pitch;
    if (o.bearing != null) opt.bearing = o.bearing;
    map.fitBounds(bounds, opt);
  }
  function defaultView(o) { viewRoute({ pitch: DEFAULT_PITCH, bearing: 0, instant: o && o.instant }); }
  function overview() { viewRoute({ pitch: 0, bearing: 0 }); }

  function createMap() {
    bounds = routeBounds();
    var replayFirst = initialMode === "replay";
    var opts = {
      container: "map",
      style: GT.mapStyle({ hillshade: true }),
      antialias: true,
      fadeDuration: 0, // 圖磚不做淡入動畫：畫面更快就緒
      attributionControl: false
    };
    // 回放：直接從起點視角開始（與播放第一幀相同）；其他模式：直接框住整條路線。避免先載入全球視角的大量圖磚。
    if (replayFirst) {
      opts.center = coords[0]; opts.zoom = FOLLOW.zoom; opts.pitch = FOLLOW.pitch; opts.bearing = bearing(coords[0], coords[1]);
    } else {
      opts.bounds = bounds; opts.pitch = DEFAULT_PITCH; opts.bearing = 0;
      opts.fitBoundsOptions = { padding: { top: 72, bottom: 60, left: 40, right: 64 }, maxZoom: 17 };
    }
    try {
      map = new maplibregl.Map(opts);
    } catch (e) {
      showStatus("<div>此瀏覽器或裝置無法使用 WebGL，無法顯示 3D 地圖。</div><a class=\"btn gt-btn\" href=\"" + GT.activityUrl(trackId) + "\">回活動故事</a>", true);
      return;
    }
    map.addControl(new maplibregl.AttributionControl({ compact: true }), "bottom-right");
    map.on("load", onMapLoad);
    map.on("error", function (e) {
      // 圖磚偶發失敗不打斷使用；只有整個地圖壞掉時才提示。
      if (e && e.error && /WebGL|context/i.test(String(e.error.message))) showStatus("3D 地圖發生錯誤：" + esc(e.error.message), true);
    });
    // 使用者手動操作地圖（拖曳/旋轉/傾斜/縮放）→ 停止自動跟隨，改為自由視角；播放仍繼續。
    // 跟隨播放每幀都會 jumpTo，單靠 movestart 事件會被它蓋掉（實測拖曳時 movestart 不一定帶 originalEvent），
    // 所以直接偵測畫布上的使用者輸入：指標移動超過 5px、滾輪、雙擊。單純點一下不會中斷跟隨。
    map.on("movestart", function (e) { if (e.originalEvent) takeOver(); });
    var box = map.getCanvasContainer(), down = null;
    box.addEventListener("pointerdown", function (e) { down = { x: e.clientX, y: e.clientY }; }, true);
    box.addEventListener("pointermove", function (e) {
      if (down && Math.hypot(e.clientX - down.x, e.clientY - down.y) > 5) takeOver();
    }, true);
    function release() { down = null; }
    box.addEventListener("pointerup", release, true);
    box.addEventListener("pointercancel", release, true);
    box.addEventListener("wheel", takeOver, { capture: true, passive: true });
    box.addEventListener("dblclick", takeOver, true);
  }
  function takeOver() { if (mode === "replay" && camMode === "follow") setCam("free"); }

  function ViewControl() {}
  ViewControl.prototype.onAdd = function () {
    var d = document.createElement("div");
    d.className = "maplibregl-ctrl maplibregl-ctrl-group";
    function btn(txt, label, fn) {
      var b = document.createElement("button");
      b.type = "button"; b.textContent = txt; b.title = label; b.setAttribute("aria-label", label);
      b.style.fontSize = "12px"; b.style.fontWeight = "700";
      b.addEventListener("click", fn); d.appendChild(b);
    }
    btn("置中", "路線置中（保留目前的旋轉與傾斜）", function () { if (mode === "replay") setCam("free"); viewRoute({}); });
    btn("預設", "恢復預設視角", function () { if (mode === "replay") setCam("free"); defaultView(); });
    btn("鳥瞰", "全路線鳥瞰", function () { if (mode === "replay") setCam("overview"); else overview(); });
    this._el = d;
    return d;
  };
  ViewControl.prototype.onRemove = function () { if (this._el && this._el.parentNode) this._el.parentNode.removeChild(this._el); };

  function markerEl(cls, txt, label) {
    var el = document.createElement("div");
    el.className = "ws-mk " + cls; el.textContent = txt;
    el.setAttribute("role", "img"); el.setAttribute("aria-label", label);
    return el;
  }

  // 起點／終點：帶文字標籤的圖釘（與 App 的 3D 飛越同樣式）。
  function pinEl(label, color) {
    var wrap = document.createElement("div");
    wrap.className = "ws-pin-wrap"; wrap.setAttribute("role", "img"); wrap.setAttribute("aria-label", label);
    var l = document.createElement("div"); l.className = "ws-pin-label"; l.textContent = label;
    var pin = document.createElement("div"); pin.className = "ws-pin"; pin.style.setProperty("--c", color);
    pin.appendChild(document.createElement("i"));
    wrap.appendChild(l); wrap.appendChild(pin);
    return wrap;
  }

  // 有上傳縮圖的拍照點：地圖上顯示圓形照片泡泡，回放經過時放大一下（與右上角彈出的照片同步）。
  var bubbles = {};
  function addPhotoBubbles() {
    photoMarks.forEach(function (m) {
      var el = document.createElement("div");
      el.className = "ws-bubble";
      var img = document.createElement("img");
      img.alt = "照片 " + m.n; img.loading = "lazy"; img.src = GT.cfg.dataBase + m.path;
      el.appendChild(img);
      new maplibregl.Marker({ element: el, anchor: "center" }).setLngLat([m.lon, m.lat]).addTo(map);
      bubbles[m.n] = { el: el, timer: 0 };
    });
  }
  function popBubble(n) {
    var b = bubbles[n]; if (!b) return;
    b.el.classList.add("pop"); b.el.style.zIndex = 5;
    clearTimeout(b.timer);
    b.timer = setTimeout(function () { b.el.classList.remove("pop"); b.el.style.zIndex = ""; }, 4000);
  }
  function unpopBubbles() {
    Object.keys(bubbles).forEach(function (n) { clearTimeout(bubbles[n].timer); bubbles[n].el.classList.remove("pop"); bubbles[n].el.style.zIndex = ""; });
  }

  function onMapLoad() {
    GT.collapseAttribution(map);
    map.setTerrain({ source: "dem", exaggeration: 1.3 });
    map.addSource("planned", { type: "geojson", data: emptyLine() });
    // 整條路線先以較淡的黃色畫出（底下加深色描邊讓線在衛星圖上更清楚），走過的部分再疊上亮黃色。
    map.addLayer({ id: "planned-casing", type: "line", source: "planned", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#000000", "line-width": 9, "line-opacity": 0.35, "line-blur": 1.5 } });
    map.addLayer({ id: "planned-line", type: "line", source: "planned", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#FFD400", "line-width": 5, "line-opacity": 0.5 } });
    map.addSource("traveled", { type: "geojson", data: emptyLine() });
    map.addLayer({ id: "traveled-line", type: "line", source: "traveled", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#FFD400", "line-width": 6.5 } });
    // GPS 中斷段（估算）：虛線，顏色＝捷運路線色（沒有對應路線＝灰）。畫在實線之上、照片點之下。
    map.addSource("gaps", { type: "geojson", data: GT.gapFeatures(gapInfo.gaps) });
    map.addLayer({ id: "gaps-casing", type: "line", source: "gaps", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#04121f", "line-width": 9, "line-opacity": 0.55 } });
    map.addLayer({ id: "gaps-line", type: "line", source: "gaps", layout: { "line-cap": "butt", "line-join": "round" }, paint: { "line-color": ["get", "color"], "line-width": 5, "line-dasharray": [1.4, 1.2] } });
    map.addSource("photos", { type: "geojson", data: emptyFC() });
    map.addLayer({ id: "photos-dot", type: "circle", source: "photos", paint: { "circle-radius": 6, "circle-color": "#FFB300", "circle-stroke-color": "#fff", "circle-stroke-width": 1.5 } });
    map.addSource("marker", { type: "geojson", data: emptyFC() });
    map.addLayer({ id: "marker-dot", type: "circle", source: "marker", paint: { "circle-radius": 9, "circle-color": "#FF3D00", "circle-stroke-color": "#fff", "circle-stroke-width": 3 } });

    map.getSource("planned").setData(runFeature(dispCoords, dispGap));
    gapInfo.gaps.forEach(function (g) {
      var el = document.createElement("div");
      el.className = "gt-gap-label"; el.textContent = GT.gapLabel(g);
      new maplibregl.Marker({ element: el, anchor: "center" }).setLngLat(GT.mid(g.kind === "metro" ? g.coords : [g.from, g.to])).addTo(map);
    });
    map.getSource("photos").setData({ type: "FeatureCollection", features: (track.photos || []).map(function (p) {
      return { type: "Feature", properties: {}, geometry: { type: "Point", coordinates: [p[0], p[1]] } };
    }) });
    new maplibregl.Marker({ element: pinEl("起點", "#00C853"), anchor: "bottom" }).setLngLat(coords[0]).addTo(map);
    new maplibregl.Marker({ element: pinEl("終點", "#FF3D00"), anchor: "bottom" }).setLngLat(coords[coords.length - 1]).addTo(map);
    addPhotoBubbles();
    map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), "top-right");
    map.addControl(new ViewControl(), "top-right");

    buildChips();
    $("controls").hidden = false;
    hideStatus();
    setMode(initialMode, true);
    if (initialMode === "replay" && coords.length > 1 && !reduceMotion && !params.get("pause")) play();
  }

  // ===== 照片：回放經過拍照點時跳出縮圖（只有上傳了縮圖的照片；沒有就只顯示黃點）=====
  var photoMarks = [], photoNext = 0, lastProgress = 0, photoTimer = 0;
  function buildPhotoMarks(t) { photoMarks = C.photoMarks(t.points, t.photos, cumFrac); photoNext = 0; lastProgress = 0; }
  function showPhoto(p) {
    var box = $("photoPop");
    $("photoImg").src = GT.cfg.dataBase + p.path; $("photoCap").textContent = "📷 照片 " + p.n;
    box.hidden = false;
    popBubble(p.n);
    clearTimeout(photoTimer); photoTimer = setTimeout(function () { box.hidden = true; }, 4000);
  }
  function checkPhotos() {
    if (!photoMarks.length) return;
    var r = C.photoStep(photoMarks, photoNext, lastProgress, progress, mode === "replay");
    lastProgress = progress; photoNext = r.next;
    if (r.reset) { $("photoPop").hidden = true; unpopBubbles(); }
    r.show.forEach(showPhoto);
  }

  // ===== 錄影：把回放的地圖畫面錄成影片（只含地圖，不含數據面板）=====
  var recorder = null, recChunks = [], recMime = "";
  function pickMime() {
    var c = ["video/mp4;codecs=avc1.42E01E", "video/webm;codecs=vp9", "video/webm;codecs=vp8", "video/webm"];
    for (var i = 0; i < c.length; i++) if (window.MediaRecorder && MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(c[i])) return c[i];
    return "";
  }
  function recUi(on) { var b = $("recBtn"); b.textContent = on ? "⏹ 停止錄影" : "🎬 錄影"; b.setAttribute("aria-pressed", on ? "true" : "false"); }
  function startRecording() {
    var cv = map && map.getCanvas && map.getCanvas();
    if (!window.MediaRecorder || !cv || !cv.captureStream) { toast("這個瀏覽器不支援錄影（建議用電腦版 Chrome 或 Edge）"); return; }
    var mime = pickMime();
    if (!mime) { toast("這個瀏覽器沒有可用的影片格式"); return; }
    setMode("replay"); progress = 0; applyFrame(true);
    try { recorder = new MediaRecorder(cv.captureStream(30), { mimeType: mime, videoBitsPerSecond: 6000000 }); }
    catch (e) { recorder = null; toast("無法開始錄影：" + e.message); return; }
    recChunks = []; recMime = mime;
    recorder.ondataavailable = function (e) { if (e.data && e.data.size) recChunks.push(e.data); };
    recorder.onstop = finishRecording;
    recorder.start(1000);
    recUi(true); toast("錄影中：回放播完會自動存檔");
    play();
  }
  function stopRecording() { if (recorder && recorder.state !== "inactive") recorder.stop(); }
  function finishRecording() {
    var type = recMime.split(";")[0], blob = new Blob(recChunks, { type: type }), ext = type === "video/mp4" ? "mp4" : "webm";
    recorder = null; recChunks = []; recUi(false);
    if (!blob.size) { toast("沒有錄到畫面"); return; }
    var a = document.createElement("a");
    a.href = URL.createObjectURL(blob); a.download = "gps-tracker-" + (trackId || "replay").slice(0, 8) + "." + ext;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 4000);
    toast("已儲存影片（約 " + (Math.round(blob.size / 104857.6) / 10) + " MB）");
  }
  $("recBtn").addEventListener("click", function () { if (recorder) stopRecording(); else startRecording(); });

  // ===== 播放 =====
  function updateTravel(p, force) {
    var now = performance.now();
    if (!force && now - lastTravel <= 60) return;
    lastTravel = now;
    if (mode === "replay") {
      var k = Math.ceil(p.i / dispStep);
      var inGap = !!(gapInfo.gapEdge && gapInfo.gapEdge[p.i]);
      map.getSource("traveled").setData(runFeature(dispCoords.slice(0, k).concat([p.pos]), dispGap.slice(0, k).concat([inGap])));
    }
    map.getSource("marker").setData(mode === "explore" ? emptyFC() : { type: "Feature", geometry: { type: "Point", coordinates: p.pos }, properties: {} });
  }

  function applyFrame(force) {
    var p = pointAt(progress);
    if (!p) return;
    if (mode === "replay" && camMode === "follow") map.jumpTo({ center: p.pos, bearing: p.bearing, pitch: FOLLOW.pitch, zoom: FOLLOW.zoom });
    updateTravel(p, force);
    $("hud-dist").textContent = ((progress * total) / 1000).toFixed(2);
    var spd = 0, alt = null;
    if (hudData.length >= 2 && p.i >= 1) {
      var s0 = hudData[p.i - 1][0], s1 = hudData[p.i][0], a0 = hudData[p.i - 1][1], a1 = hudData[p.i][1];
      spd = lerp(s0, s1, p.t);
      if (a0 && a1) alt = lerp(a0, a1, p.t);
    }
    $("hud-speed").textContent = Math.round(spd);
    $("hud-alt").textContent = alt === null ? "--" : Math.round(alt);
    $("hud-gap").hidden = !(gapInfo.gapEdge && gapInfo.gapEdge[p.i]);
    var seek = $("seek");
    seek.value = String(Math.round(progress * 1000));
    seek.setAttribute("aria-valuetext", "距離 " + ((progress * total) / 1000).toFixed(2) + " 公里");
    $("clock").textContent = C.fmtClock(C.elapsedAt(track.points, cumFrac, progress)) + " / " + C.fmtClock(totalSec);
    var cur = mode === "explore" ? null : progress;
    if (profAlt) profAlt.setCursor(cur);
    if (profSpd) profSpd.setCursor(cur);
    checkPhotos();
  }

  function tick(ts) {
    if (!playing) return;
    if (!lastTs) lastTs = ts;
    var dt = (ts - lastTs) / 1000; lastTs = ts; // 與原版相同：依實際經過時間推進（慢裝置不會變慢動作）
    progress = Math.min(1, progress + dt * 0.035 * speed);
    applyFrame(false);
    if (progress < 1) rafId = requestAnimationFrame(tick);
    else { playing = false; rafId = 0; updatePlayBtn(); applyFrame(true); if (recorder) setTimeout(stopRecording, 900); }
  }
  function play() {
    if (coords.length < 2) return;
    if (progress >= 1) progress = 0;
    playing = true; lastTs = 0; updatePlayBtn();
    cancelAnimationFrame(rafId); rafId = requestAnimationFrame(tick);
  }
  function pause() { playing = false; cancelAnimationFrame(rafId); rafId = 0; updatePlayBtn(); }
  function updatePlayBtn() {
    var b = $("play");
    b.textContent = playing ? "⏸ 暫停" : "▶ 播放";
    b.setAttribute("aria-label", playing ? "暫停" : "播放");
  }
  // 跳到某個進度（拖曳時間軸、點選剖面圖）：暫停播放，不移動鏡頭以外的東西。
  function seekTo(f) { pause(); progress = Math.min(1, Math.max(0, f)); applyFrame(true); }
  // 活動數據模式：滑過剖面圖只預覽位置（不改變播放進度的暫停狀態）。
  function previewAt(f) { if (f == null) return; progress = Math.min(1, Math.max(0, f)); applyFrame(false); }

  function buildChips() {
    var box = $("spdchips");
    box.innerHTML = "";
    SPEEDS.forEach(function (s) {
      var b = document.createElement("button");
      b.type = "button"; b.className = "ws-chipbtn"; b.textContent = s + "×";
      b.setAttribute("aria-pressed", s === speed ? "true" : "false");
      b.setAttribute("aria-label", "播放速度 " + s + " 倍");
      b.onclick = function () {
        speed = s;
        Array.prototype.forEach.call(box.children, function (c) { c.setAttribute("aria-pressed", "false"); });
        b.setAttribute("aria-pressed", "true");
      };
      box.appendChild(b);
    });
  }

  // ===== 鏡頭與模式 =====
  function setCam(c) {
    camMode = c;
    $("camFollow").setAttribute("aria-pressed", c === "follow");
    $("camOverview").setAttribute("aria-pressed", c === "overview");
    if (c === "follow") applyFrame(true);
    else if (c === "overview") overview();
  }

  function setSheet(open) {
    document.body.classList.toggle("sheet-open", open);
    $("sheetBtn").setAttribute("aria-expanded", open);
  }
  function setPanel(open) {
    document.body.classList.toggle("panel-closed", !open);
    $("panelToggle").setAttribute("aria-expanded", open);
    if (map) { setTimeout(function () { map.resize(); }, 280); }
  }

  function urlFor(m) {
    var q = new URLSearchParams(location.search);
    q.set("mode", m);
    q.delete("pause");
    return location.pathname + "?" + q.toString();
  }

  function setMode(m, initial) {
    if (m === mode) return;
    mode = m;
    document.body.dataset.mode = m;
    Array.prototype.forEach.call(document.querySelectorAll(".ws-modes button"), function (b) { b.setAttribute("aria-selected", b.dataset.mode === m); });
    try { history.replaceState(null, "", urlFor(m)); } catch (e) {}
    if (m !== "replay") pause();
    renderStats();

    // 路線圖層：回放＝逐步走過的綠線；探索／數據＝整條路線完整顯示
    if (m === "replay") map.getSource("traveled").setData(emptyLine());
    else map.getSource("traveled").setData(runFeature(dispCoords, dispGap));

    if (m === "replay") {
      setSheet(false);
      camMode = "follow";
      $("camFollow").setAttribute("aria-pressed", "true"); $("camOverview").setAttribute("aria-pressed", "false");
      applyFrame(true);
    } else {
      if (m === "data") { setSheet(true); if (!narrowMq.matches) setPanel(true); }
      else setSheet(false);
      applyFrame(true);
      if (!initial) defaultView();
    }
  }

  // ===== 事件 =====
  Array.prototype.forEach.call(document.querySelectorAll(".ws-modes button"), function (b) {
    b.addEventListener("click", function () { if (map && mode) setMode(b.dataset.mode); });
  });
  $("play").addEventListener("click", function () { if (playing) pause(); else play(); });
  $("restart").addEventListener("click", function () { progress = 0; applyFrame(true); if (camMode === "free") setCam("follow"); play(); });
  $("seek").addEventListener("input", function () { seekTo($("seek").value / 1000); });
  $("camFollow").addEventListener("click", function () { setCam("follow"); });
  $("camOverview").addEventListener("click", function () { setCam("overview"); });
  $("panelToggle").addEventListener("click", function () { setPanel(document.body.classList.contains("panel-closed")); });
  $("sheetBtn").addEventListener("click", function () { setSheet(!document.body.classList.contains("sheet-open")); });
  $("backToMap").addEventListener("click", function () { setSheet(false); });

  document.addEventListener("keydown", function (e) {
    if (!map || !mode || e.ctrlKey || e.metaKey || e.altKey) return;
    var tag = (e.target && e.target.tagName) || "";
    if (/^(INPUT|TEXTAREA|SELECT|BUTTON|A|CANVAS)$/.test(tag) || (e.target && e.target.isContentEditable)) return;
    if (e.key === " " && mode === "replay") { e.preventDefault(); if (playing) pause(); else play(); }
    else if ((e.key === "ArrowLeft" || e.key === "ArrowRight") && mode !== "explore") { e.preventDefault(); seekTo(progress + (e.key === "ArrowRight" ? 0.01 : -0.01)); }
    else if (e.key === "Escape") setSheet(false);
  });
  window.addEventListener("pagehide", pause);
  // 分頁切到背景時 rAF 會停；回來時重設計時基準，避免一次跳過整段背景時間。
  document.addEventListener("visibilitychange", function () { lastTs = 0; });

  // 測試用：網址加 ?debug=1 時才暴露內部狀態（正常使用不會有任何影響）。
  if (params.get("debug")) {
    window.__ws = {
      get map() { return map; }, get mode() { return mode; }, get cam() { return camMode; }, get playing() { return playing; },
      get progress() { return progress; }, setMode: setMode, play: play, pause: pause, seekTo: seekTo, setCam: setCam
    };
  }

  // ===== 啟動 =====
  function init() {
    $("home").href = GT.url("index.html", {}); // 帶著自訂資料來源參數回首頁
    // 先反映網址指定的模式（地圖載入前面板與頂列就已是正確的模式）。
    document.body.dataset.mode = initialMode;
    Array.prototype.forEach.call(document.querySelectorAll(".ws-modes button"), function (b) { b.setAttribute("aria-selected", b.dataset.mode === initialMode); });
    if (!trackId) {
      showStatus('<div>缺少 ?track= 參數，請從歷史紀錄選擇一筆活動。</div><a class="btn gt-btn" href="history.html">前往歷史紀錄</a>', true);
      $("hTitle").textContent = "沒有指定活動";
      return;
    }
    if (typeof maplibregl === "undefined") {
      showStatus("<div>找不到地圖引擎（vendor/maplibre-gl.js），請重新整理頁面。</div>", true);
      return;
    }
    $("back").href = GT.activityUrl(trackId);
    Promise.all([GT.loadTrack(trackId), GT.loadMeta()]).then(function (r) {
      rawTrack = r[0]; meta = r[1];
      // GPS 中斷（例如捷運內）：有中斷段才會去載入 metro.json；失敗就只用虛線標示中斷。
      return GT.resolveGaps(rawTrack).then(function (res) {
        gapInfo = res;
        track = res.gaps.length ? Object.assign({}, rawTrack, { points: res.points }) : rawTrack;
      });
    }).then(function () {
      prepareTrack(track);
      buildPhotoMarks(track);
      buildDispGap();
      if (coords.length < 2) {
        showStatus('<div>這筆活動沒有足夠的路線資料，無法顯示 3D 路線。</div><a class="btn gt-btn" href="' + GT.activityUrl(trackId) + '">回活動故事</a>', true);
        return;
      }
      fillPanel();
      createMap();
    }, function (e) {
      var missing = /\b404\b/.test(e.message);
      showStatus("<div>" + (missing ? "找不到這筆活動，可能已被刪除。" : "讀取失敗：" + esc(e.message)) + "</div>" +
        '<div><a class="btn gt-btn" href="history.html">前往歷史紀錄</a> <button type="button" class="btn gt-btn secondary" id="retry">重試</button></div>', true);
      var r = $("retry"); if (r) r.onclick = function () { location.reload(); };
    });
  }
  init();
})();
