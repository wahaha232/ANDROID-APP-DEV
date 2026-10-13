// 活動故事頁：單筆活動的 3D 路線預覽（可互動、可旋轉的真實地形）+ 統計 + 海拔剖面 + 操作。
// 只顯示資料裡實際存在的欄位；完整的回放/探索在 3D 路線工作區（flythrough.html）。
(function () {
  "use strict";

  var C = GTCore, esc = C.escapeHtml;
  var params = new URLSearchParams(location.search);
  var trackId = params.get("track");
  var reduceMotion = !!(window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches);
  var ORBIT_DEG_PER_SEC = 4;

  // GPS 中斷（捷運）：rawTrack＝原始軌跡（匯出用）；track＝插入估算路徑後、只供顯示的版本。
  var rawTrack = null, gapInfo = { gaps: [], gapEdge: null, usedMetro: false };
  var track = null, meta = {}, map = null, bounds = null, orbit = { on: false, raf: 0, last: 0, visible: true };
  var colorMode = "", mapReady = false, highlights = [];

  function $(id) { return document.getElementById(id); }
  function toast(msg) {
    if (!msg) return;
    var t = $("toast"); t.textContent = msg; t.hidden = false;
    clearTimeout(toast._t); toast._t = setTimeout(function () { t.hidden = true; }, 2600);
  }

  function showState(html, isErr) {
    var s = $("state");
    s.className = "gt-state" + (isErr ? " err" : "");
    s.innerHTML = html; s.hidden = false;
    $("story").hidden = true; $("grid").hidden = true;
  }

  // ---- 3D 預覽 ----
  function startOrbit() {
    if (reduceMotion || orbit.on) return;
    orbit.on = true; orbit.last = 0;
    orbit.raf = requestAnimationFrame(spin);
  }
  function stopOrbit() { orbit.on = false; cancelAnimationFrame(orbit.raf); orbit.raf = 0; }
  function spin(ts) {
    if (!orbit.on) return;
    if (orbit.last && orbit.visible && !document.hidden) map.setBearing(map.getBearing() + ((ts - orbit.last) / 1000) * ORBIT_DEG_PER_SEC);
    orbit.last = ts;
    orbit.raf = requestAnimationFrame(spin);
  }

  function initMap() {
    var pts = track.points || [];
    var coords = pts.map(function (p) { return [p[0], p[1]]; });
    if (coords.length < 2) {
      $("hint").textContent = "這筆活動沒有足夠的路線資料可顯示";
      return;
    }
    bounds = new maplibregl.LngLatBounds();
    coords.forEach(function (c) { bounds.extend(c); });
    try {
      map = new maplibregl.Map({
        container: "map", style: GT.mapStyle({ hillshade: true }), antialias: true, fadeDuration: 0,
        bounds: bounds, fitBoundsOptions: { padding: { top: 70, bottom: 150, left: 40, right: 40 }, maxZoom: 16 },
        pitch: 55, bearing: 0, attributionControl: false
      });
    } catch (e) {
      $("hint").textContent = "此瀏覽器或裝置無法使用 WebGL，無法顯示 3D 預覽";
      return;
    }
    map.addControl(new maplibregl.AttributionControl({ compact: true }), "bottom-right");
    map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), "top-right");
    map.on("load", function () {
      GT.collapseAttribution(map);
      map.setTerrain({ source: "dem", exaggeration: 1.3 });
      // 實線在「GPS 中斷」邊上斷開；中斷段另以虛線（捷運路線色）畫出估算路徑。
      map.addSource("route", { type: "geojson", data: { type: "Feature", properties: {}, geometry: { type: "MultiLineString", coordinates: GT.splitRuns(coords, gapInfo.gapEdge) } } });
      map.addLayer({ id: "route-casing", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#04121f", "line-width": 9, "line-opacity": 0.6 } });
      map.addLayer({ id: "route-line", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": ["coalesce", ["get", "color"], "#22d3ee"], "line-width": 5 } });
      map.addSource("gaps", { type: "geojson", data: GT.gapFeatures(gapInfo.gaps) });
      map.addLayer({ id: "gaps-casing", type: "line", source: "gaps", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#04121f", "line-width": 9, "line-opacity": 0.55 } });
      map.addLayer({ id: "gaps-line", type: "line", source: "gaps", layout: { "line-cap": "butt", "line-join": "round" }, paint: { "line-color": ["get", "color"], "line-width": 5, "line-dasharray": [1.4, 1.2] } });
      gapInfo.gaps.forEach(function (g) {
        var el = document.createElement("div"); el.className = "gt-gap-label"; el.textContent = GT.gapLabel(g);
        new maplibregl.Marker({ element: el, anchor: "center" }).setLngLat(GT.mid(g.kind === "metro" ? g.coords : [g.from, g.to])).addTo(map);
      });
      map.addSource("photos", { type: "geojson", data: { type: "FeatureCollection", features: (track.photos || []).map(function (p) {
        return { type: "Feature", properties: {}, geometry: { type: "Point", coordinates: [p[0], p[1]] } };
      }) } });
      map.addLayer({ id: "photos-dot", type: "circle", source: "photos", paint: { "circle-radius": 6, "circle-color": "#FFB300", "circle-stroke-color": "#fff", "circle-stroke-width": 1.5 } });
      map.addSource("cursor", { type: "geojson", data: GPST.emptyFC() });
      map.addLayer({ id: "cursor-dot", type: "circle", source: "cursor", paint: { "circle-radius": 8, "circle-color": "#FF3D00", "circle-stroke-color": "#fff", "circle-stroke-width": 3 } });
      function pin(cls, txt, label, at) {
        var el = document.createElement("div"); el.className = "ws-mk " + cls; el.textContent = txt;
        el.setAttribute("role", "img"); el.setAttribute("aria-label", label);
        new maplibregl.Marker({ element: el }).setLngLat(at).addTo(map);
      }
      map.addSource("hl", { type: "geojson", data: { type: "FeatureCollection", features: highlights.map(function (h) {
        return { type: "Feature", properties: { key: h.key }, geometry: { type: "Point", coordinates: [h.lon, h.lat] } };
      }) } });
      map.addLayer({ id: "hl-dots", type: "circle", source: "hl", paint: { "circle-radius": 7, "circle-color": "#a78bfa", "circle-stroke-color": "#fff", "circle-stroke-width": 2 } });
      mapReady = true;
      applyRouteColor(colorMode);
      pin("s", "起", "起點", coords[0]);
      pin("e", "終", "終點", coords[coords.length - 1]);
      startOrbit();
    });
    // 使用者碰地圖就停止自動旋轉（不跟使用者搶控制權）。旋轉本身每幀都會觸發 movestart（沒有 originalEvent），
    // 所以直接偵測畫布上的使用者輸入，而不是依賴 movestart。
    function userTouched() { if (!orbit.on) return; stopOrbit(); $("hint").textContent = "3D 路線預覽・可拖曳旋轉、傾斜、縮放"; }
    ["pointerdown", "wheel", "touchstart", "dblclick"].forEach(function (ev) {
      map.getCanvasContainer().addEventListener(ev, userTouched, { capture: true, passive: true });
    });
    if (window.IntersectionObserver) {
      new IntersectionObserver(function (en) { orbit.visible = en[0].isIntersecting; }).observe($("map"));
    }
  }

  // ---- 路線上色（速度／海拔）----
  // 只列出資料真的支援的選項；沒有速度或海拔資料就不顯示該按鈕（不拿假資料填）。
  function setupColorBar() {
    var pts = track.points || [], avail = { speed: !!C.valueSeries(pts, "speed"), altitude: !!C.valueSeries(pts, "altitude") };
    var seg = $("colorSeg"), any = false;
    Array.prototype.forEach.call(seg.querySelectorAll("button"), function (b) {
      var c = b.dataset.c;
      if (c) { b.hidden = !avail[c]; if (avail[c]) any = true; }
      b.onclick = function () { setColor(c); };
    });
    $("colorBar").hidden = !any || pts.length < 2;
  }
  function setColor(mode) {
    colorMode = mode;
    Array.prototype.forEach.call($("colorSeg").querySelectorAll("button"), function (b) { b.setAttribute("aria-pressed", b.dataset.c === mode ? "true" : "false"); });
    applyRouteColor(mode);
  }
  function applyRouteColor(mode) {
    var pts = track.points || [], coords = pts.map(function (p) { return [p[0], p[1]]; }), legend = $("colorLegend");
    var values = mode ? C.valueSeries(pts, mode) : null;
    if (!mapReady || !map.getSource("route")) return;
    if (!values) {
      map.getSource("route").setData({ type: "Feature", properties: {}, geometry: { type: "MultiLineString", coordinates: GT.splitRuns(coords, gapInfo.gapEdge) } });
      legend.hidden = true;
      return;
    }
    var r = C.colorSegments(coords, values, gapInfo.gapEdge, mode);
    map.getSource("route").setData({ type: "FeatureCollection", features: r.segments.map(function (s) {
      return { type: "Feature", properties: { color: s.color }, geometry: { type: "LineString", coordinates: s.coords } };
    }) });
    var unit = mode === "speed" ? "km/h" : "m";
    legend.innerHTML = "<span>" + Math.round(r.lo) + "</span>" +
      '<i style="background:linear-gradient(90deg,' + r.legend.map(function (l) { return l.color; }).join(",") + ')"></i><span>' + Math.round(r.hi) + " " + unit + "</span>";
    legend.hidden = false;
  }

  // ---- 活動亮點 ----
  function renderHighlights() {
    if (!highlights.length) return;
    $("pnlHL").hidden = false;
    $("hlList").innerHTML = highlights.map(function (h, i) {
      return '<li><button type="button" class="gt-hl-item" data-i="' + i + '"><span class="ic" aria-hidden="true">' + h.icon + '</span><span class="tx"><b>' + esc(h.label) +
        "</b><small>" + (h.tSec > 0 ? "活動時間 " + C.fmtClock(h.tSec) : "") + '</small></span><span class="val">' + (h.prefix || "") + esc(String(h.value)) + "<small>" + esc(h.unit) + "</small></span></button></li>";
    }).join("");
    Array.prototype.forEach.call($("hlList").querySelectorAll("button"), function (b) {
      b.onclick = function () { flyToHighlight(highlights[+b.dataset.i]); };
    });
  }
  function flyToHighlight(h) {
    if (!mapReady) { toast("地圖尚未載入"); return; }
    stopOrbit();
    $("hint").textContent = "3D 路線預覽・可拖曳旋轉、傾斜、縮放";
    if (map.getSource("cursor")) map.getSource("cursor").setData({ type: "Feature", properties: {}, geometry: { type: "Point", coordinates: [h.lon, h.lat] } });
    map.easeTo({ center: [h.lon, h.lat], zoom: Math.max(map.getZoom(), 15), pitch: 60, duration: reduceMotion ? 0 : 900 });
    var hero = $("story"); if (hero && hero.scrollIntoView) hero.scrollIntoView({ block: "nearest", behavior: reduceMotion ? "auto" : "smooth" });
  }

  // 滑過剖面圖時在地圖標出對應位置（依活動時間找最近的點）。
  function showCursorAt(f) {
    if (!map || !map.getSource || !map.getSource("cursor")) return;
    if (f == null) { map.getSource("cursor").setData(GPST.emptyFC()); return; }
    var pts = track.points, target = f * (pts[pts.length - 1][4] || 0);
    var lo = 0, hi = pts.length - 1;
    while (lo < hi) { var mid = (lo + hi) >> 1; if ((pts[mid][4] || 0) < target) lo = mid + 1; else hi = mid; }
    map.getSource("cursor").setData({ type: "Feature", properties: {}, geometry: { type: "Point", coordinates: [pts[lo][0], pts[lo][1]] } });
  }

  // ---- 內容 ----
  function render() {
    var title = C.activityTitle(track, meta);
    document.title = title + " · GPS Tracker";
    $("title").textContent = title;
    $("summary").textContent = C.fmtDate(track.startTimeMs) + "・" + C.modeName(C.modeOf(track, meta));
    $("chips").innerHTML = '<span class="gt-chip">' + C.modeEmoji(C.modeOf(track, meta)) + " " + esc(C.modeName(C.modeOf(track, meta))) + "</span>";
    $("when").textContent = C.fmtDate(track.startTimeMs) + (track.endTimeMs ? " – " + C.fmtTime(track.endTimeMs) : "");

    $("goReplay").href = GT.workspaceUrl(trackId, "replay");
    $("aReplay").href = GT.workspaceUrl(trackId, "replay");
    $("goExplore").href = GT.workspaceUrl(trackId, "explore");
    $("aData").href = GT.workspaceUrl(trackId, "data");
    $("cta").hidden = (track.points || []).length < 2;

    var fields = C.trackFields(track);
    $("stats").innerHTML = fields.length ? fields.map(function (f) {
      return '<div class="gt-stat"><div class="v">' + esc(f.value) + (f.unit ? "<small>" + esc(f.unit) + "</small>" : "") + '</div><div class="l">' + esc(f.label) + "</div></div>";
    }).join("") : '<div class="gt-note">這筆活動沒有可顯示的統計資料。</div>';

    var pts = track.points || [], span = pts.length ? (pts[pts.length - 1][4] || 0) : 0;
    if (C.hasAltitude(pts) && span > 0) {
      $("pnlAlt").hidden = false;
      var prof = GTProfile.create($("profAlt"), { color: "#22d3ee", unit: "m", label: "海拔剖面",
        onHover: showCursorAt, onSelect: showCursorAt });
      prof.setData(pts.map(function (p) { return (p[4] || 0) / span; }), C.altitudeSeries(pts));
    }

    var mb = C.modeBreakdown(track.segments);
    if (mb.length) {
      $("pnlModes").hidden = false;
      $("modeBar").innerHTML = mb.map(function (m) {
        return '<i style="width:' + m.pct.toFixed(2) + "%;background:" + (C.MODE_COLOR[m.mode] || "#94a3b8") + '" title="' + esc(C.modeName(m.mode)) + '"></i>';
      }).join("");
      $("modeLegend").innerHTML = mb.map(function (m) {
        return '<li><i style="background:' + (C.MODE_COLOR[m.mode] || "#94a3b8") + '"></i>' + C.modeEmoji(m.mode) + " " + esc(C.modeName(m.mode)) + " " + C.fmtDur(m.sec * 1000) + "（" + m.pct.toFixed(0) + "%）</li>";
      }).join("");
    }

    if (gapInfo.gaps.length) {
      $("pnlGaps").hidden = false;
      $("gapBody").innerHTML = GT.gapSummaryHtml(gapInfo.gaps, gapInfo.usedMetro, track.distanceMeters);
    }
    var photos = track.photos || [];
    if (photos.length) {
      $("pnlPhotos").hidden = false;
      $("photoNote").textContent = "共 " + photos.length + " 個拍照點，已在上方地圖以黃點標示。網站只保存拍攝位置，照片檔案留在手機。";
    }
    setupColorBar();
    highlights = C.highlights((rawTrack || track).points);
    renderHighlights();

    var m = meta[trackId];
    if (m && m.notes) { $("pnlNote").hidden = false; $("noteText").textContent = m.notes; }

    // 匯出一律用原始軌跡（估算路徑只用於顯示）。
    $("aGpx").onclick = function () { GT.saveBlob(trackId + ".gpx", C.gpx(rawTrack || track), "application/gpx+xml"); };
    $("aJson").onclick = function () { GT.saveBlob(trackId + ".json", JSON.stringify(rawTrack || track), "application/json"); };
    $("aShare").onclick = function () { GT.share(GT.url("activity.html", { track: trackId }), title).then(toast); };
    // 分享圖片：用原始軌跡（不含估算路徑）與目前選的路線顏色；圖片只在本機產生。
    $("aCard").onclick = function () {
      var raw = rawTrack || track, mode = C.modeOf(track, meta);
      var picks = C.trackFields(raw).filter(function (f) { return ["distance", "duration", "avgSpeed", "steps", "ascent"].indexOf(f.key) >= 0; });
      GTShare.shareCard({
        points: raw.points, title: title, whenText: C.fmtDate(raw.startTimeMs), modeText: C.modeEmoji(mode) + " " + C.modeName(mode),
        fields: picks, colorMode: colorMode
      }, "gps-tracker-" + trackId.slice(0, 8) + ".png").then(function (msg) { if (msg) toast(msg); });
    };
    $("grid").hidden = false;
  }

  if (params.get("debug")) window.__act = { get map() { return map; } };

  function init() {
    $("crumb").href = GT.url("history.html", {});
    if (!trackId) {
      showState("<h3>沒有指定活動</h3><p>請從歷史紀錄選擇一筆活動。</p><a class=\"btn gt-btn\" href=\"history.html\">前往歷史紀錄</a>", true);
      $("summary").textContent = "—";
      return;
    }
    Promise.all([GT.loadTrack(trackId), GT.loadMeta()]).then(function (r) {
      rawTrack = r[0]; meta = r[1];
      return GT.resolveGaps(rawTrack).then(function (res) {
        gapInfo = res;
        track = res.gaps.length ? Object.assign({}, rawTrack, { points: res.points }) : rawTrack;
      });
    }).then(function () {
      render();
      if (typeof maplibregl === "undefined") $("hint").textContent = "找不到地圖引擎（vendor/maplibre-gl.js）";
      else initMap();
    }, function (e) {
      var missing = /\b404\b/.test(e.message);
      $("summary").textContent = missing ? "找不到活動" : "讀取失敗";
      showState("<h3>" + (missing ? "找不到這筆活動" : "無法讀取活動資料") + "</h3><p>" + (missing ? "它可能已在紀錄管理被刪除。" : esc(e.message)) + "</p>" +
        '<a class="btn gt-btn" href="history.html">前往歷史紀錄</a> <button type="button" class="btn gt-btn secondary" id="retry">重試</button>', true);
      var rb = $("retry"); if (rb) rb.onclick = function () { location.reload(); };
    });
  }
  init();
})();
