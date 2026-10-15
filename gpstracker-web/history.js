// 歷史紀錄：活動卡片（依月份分組）+ 搜尋 / 類型篩選 / 排序，以及「全部軌跡」地圖檢視。
// 清單只依賴 manifest.json；路線縮圖與地圖按需載入單筆軌跡檔（限制同時下載數）。
(function () {
  "use strict";

  var C = GTCore, esc = C.escapeHtml;
  var MAX_MAP_POINTS = 400;   // 地圖上每筆最多取樣點數（與舊版「顯示全部軌跡」相同）
  var MAP_CONCURRENCY = 4;
  var SEARCH_DEBOUNCE_MS = 150;

  var tracks = [], meta = {};
  var view = "cards";
  var map = null, mapReady = false, mapToken = 0;

  function $(id) { return document.getElementById(id); }

  function currentList() {
    var f = C.filterTracks(tracks, { q: $("search").value, mode: $("modeFilter").value, meta: meta });
    return C.sortTracks(f, $("sortSel").value);
  }

  function buildModeFilter() {
    var seen = {}, sel = $("modeFilter");
    tracks.forEach(function (t) { seen[C.modeOf(t, meta)] = true; });
    sel.length = 1;
    Object.keys(seen).forEach(function (m) {
      var o = document.createElement("option");
      o.value = m; o.textContent = C.modeEmoji(m) + " " + C.modeName(m);
      sel.appendChild(o);
    });
  }

  function cardHtml(t) {
    var f = C.trackFields(t).filter(function (x) { return ["distance", "duration", "steps", "ascent"].indexOf(x.key) >= 0; });
    var m = meta[t.trackId] || {};
    return '<article class="gt-card" data-id="' + esc(t.trackId) + '">' +
      '<div class="gt-card-thumb"><span class="gt-chip">' + C.modeEmoji(C.modeOf(t, meta)) + " " + esc(C.modeName(C.modeOf(t, meta))) + "</span>" +
      '<div class="gt-nothumb"><span class="gt-spin" aria-hidden="true"></span></div></div>' +
      '<div class="gt-card-body">' +
        '<h3 class="gt-card-title"><a href="' + GT.activityUrl(t.trackId) + '">' + esc(C.activityTitle(t, meta)) + "</a></h3>" +
        '<div class="gt-card-sub">' + esc(C.fmtDate(t.startTimeMs)) + "</div>" +
        '<div class="gt-card-stats">' + f.map(function (x) { return "<span>" + esc(x.value) + " " + esc(x.unit) + "</span>"; }).join("") + "</div>" +
        (m.notes ? '<div class="gt-card-note">' + esc(m.notes) + "</div>" : "") +
        '<div class="gt-card-actions">' +
          '<a class="btn gt-btn" href="' + GT.workspaceUrl(t.trackId, "replay") + '">▶ 3D 回放</a>' +
          '<a class="btn gt-btn secondary" href="' + GT.activityUrl(t.trackId) + '">活動故事</a>' +
          '<button class="btn gt-btn secondary" type="button" data-act="gpx" data-id="' + esc(t.trackId) + '">GPX</button>' +
        "</div>" +
      "</div></article>";
  }

  function renderList() {
    var list = currentList();
    $("count").textContent = list.length === tracks.length ? tracks.length + " 筆" : list.length + " / " + tracks.length + " 筆";
    var box = $("list");
    if (!tracks.length) {
      box.innerHTML = '<div class="gt-state"><h3>還沒有任何活動紀錄</h3><p>在手機 App 錄製並上傳後，活動會出現在這裡。</p></div>';
      return;
    }
    if (!list.length) {
      box.innerHTML = '<div class="gt-state"><h3>沒有符合條件的活動</h3><p>試試其他關鍵字，或清除篩選。</p><button class="btn gt-btn secondary" id="clearFilters" type="button">清除篩選</button></div>';
      $("clearFilters").onclick = function () { $("search").value = ""; $("modeFilter").value = ""; refresh(); };
      return;
    }
    var byDate = $("sortSel").value.indexOf("date_") === 0;
    if (byDate) {
      box.innerHTML = C.groupByMonth(list).map(function (g) {
        return '<h2 class="gt-month">' + esc(g.label) + ' <span style="font-weight:500">· ' + g.items.length + " 筆</span></h2>" +
          '<div class="gt-cards">' + g.items.map(cardHtml).join("") + "</div>";
      }).join("");
    } else {
      box.innerHTML = '<div class="gt-cards" style="margin-top:12px">' + list.map(cardHtml).join("") + "</div>";
    }
    Array.prototype.forEach.call(box.querySelectorAll(".gt-card"), function (card) {
      var holder = card.querySelector(".gt-card-thumb");
      GT.lazy(card, function () {
        GT.thumb(card.dataset.id, 320, 180).then(function (r) {
          var ph = holder.querySelector(".gt-nothumb");
          if (r && r.path) { if (ph) ph.remove(); holder.insertAdjacentHTML("beforeend", GT.routeSvg(r.path, "gt-mini", 4, true)); }
          else if (ph) ph.textContent = "沒有路線資料";
        });
      });
    });
  }

  // ---- 全部軌跡地圖 ----
  function setMapNote(text) { var n = $("mapNote"); if (n) n.textContent = text; }

  function ensureMap() {
    if (map) return Promise.resolve();
    return GT.loadMapLibre().then(function () {
      map = new maplibregl.Map({ container: "allmap", style: GT.mapStyle(), center: [121, 23.5], zoom: 2, antialias: true, attributionControl: { compact: true } });
      map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), "top-right");
      return new Promise(function (resolve) {
        map.on("load", function () {
          GT.collapseAttribution(map);
          map.addSource("routes", { type: "geojson", data: GPST.emptyFC() });
          map.addLayer({ id: "routes-casing", type: "line", source: "routes", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#04121f", "line-width": 6, "line-opacity": 0.55 } });
          map.addLayer({ id: "routes-line", type: "line", source: "routes", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#22d3ee", "line-width": 3, "line-opacity": 0.9 } });
          map.on("click", "routes-line", function (e) {
            var f = e.features && e.features[0];
            if (f && f.properties && f.properties.id) location.href = GT.activityUrl(f.properties.id);
          });
          map.on("mouseenter", "routes-line", function () { map.getCanvas().style.cursor = "pointer"; });
          map.on("mouseleave", "routes-line", function () { map.getCanvas().style.cursor = ""; });
          mapReady = true; resolve();
        });
      });
    });
  }

  function loadLimited(ids, limit, onEach) {
    return new Promise(function (resolve) {
      var i = 0, active = 0, done = 0;
      if (!ids.length) { resolve(); return; }
      (function kick() {
        while (active < limit && i < ids.length) {
          (function (id) {
            active++;
            GT.loadTrack(id).then(function (t) { onEach(id, t); }, function () { onEach(id, null); }).then(function () {
              active--; done++; if (done === ids.length) resolve(); else kick();
            });
          })(ids[i++]);
        }
      })();
    });
  }

  function drawMap() {
    var token = ++mapToken, list = currentList();
    setMapNote("載入全部軌跡中…");
    ensureMap().then(function () {
      if (token !== mapToken) return;
      var features = [], bounds = new maplibregl.LngLatBounds(), n = 0;
      loadLimited(list.map(function (t) { return t.trackId; }), MAP_CONCURRENCY, function (id, t) {
        if (token !== mapToken || !t) return;
        var coords = C.downsample((t.points || []).map(function (p) { return [p[0], p[1]]; }), MAX_MAP_POINTS);
        if (coords.length < 2) return;
        coords.forEach(function (c) { bounds.extend(c); });
        features.push({ type: "Feature", properties: { id: id }, geometry: { type: "LineString", coordinates: coords } });
        n++;
        setMapNote("載入中… " + n + " / " + list.length);
      }).then(function () {
        if (token !== mapToken) return;
        map.getSource("routes").setData({ type: "FeatureCollection", features: features });
        if (features.length) { map.fitBounds(bounds, { padding: 60, duration: 600, maxZoom: 15 }); setMapNote(features.length + " 筆軌跡・點擊路線開啟活動故事"); }
        else setMapNote(list.length ? "這些活動沒有可顯示的路線" : "沒有符合條件的活動");
      });
    }, function (e) { setMapNote("地圖無法載入：" + e.message); });
  }

  function setView(v) {
    view = v;
    $("viewCards").setAttribute("aria-pressed", v === "cards");
    $("viewMap").setAttribute("aria-pressed", v === "map");
    $("mapWrap").hidden = v !== "map";
    if (v === "map") { drawMap(); if (map) setTimeout(function () { map.resize(); }, 0); }
  }

  function refresh() {
    renderList();
    if (view === "map") drawMap();
  }

  // ---- 事件 ----
  var timer = null;
  $("search").addEventListener("input", function () { clearTimeout(timer); timer = setTimeout(refresh, SEARCH_DEBOUNCE_MS); });
  $("modeFilter").addEventListener("change", refresh);
  $("sortSel").addEventListener("change", renderList);
  $("viewCards").addEventListener("click", function () { setView("cards"); });
  $("viewMap").addEventListener("click", function () { setView("map"); });
  $("list").addEventListener("click", function (e) {
    var b = e.target.closest("button[data-act='gpx']");
    if (!b) return;
    b.disabled = true;
    GT.loadTrack(b.dataset.id).then(function (t) { GT.saveBlob(b.dataset.id + ".gpx", C.gpx(t), "application/gpx+xml"); })
      .catch(function (er) { alert("下載失敗：" + er.message); })
      .then(function () { b.disabled = false; });
  });

  function load() {
    $("list").innerHTML = '<div class="gt-skel" style="height:240px"></div>';
    Promise.all([GT.loadManifest(), GT.loadMeta()]).then(function (r) {
      tracks = r[0].tracks; meta = r[1];
      var dist = 0;
      tracks.forEach(function (t) { dist += t.distanceMeters || 0; });
      $("summary").textContent = tracks.length ? tracks.length + " 筆活動 · 累積 " + C.fmtKm(dist) + " km" : "尚無紀錄";
      buildModeFilter();
      renderList();
    }, function (e) {
      $("summary").textContent = "讀取失敗";
      $("list").innerHTML = '<div class="gt-state err"><h3>無法讀取活動資料</h3><p>' + esc(e.message) + '</p><button class="btn gt-btn" id="retry" type="button">重新載入</button></div>';
      $("retry").onclick = load;
    });
  }

  load();
})();
