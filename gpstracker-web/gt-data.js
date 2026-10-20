// 新版介面共用的資料存取層（瀏覽器端）。資料格式與來源完全沿用既有的 data/（由 App 上傳），這裡只讀不寫。
// 依賴：common.js（GPST.resolveConfig / bust）、gt-core.js（GTCore）。
(function () {
  "use strict";

  var CFG = GPST.resolveConfig();
  var BASE_TILES = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}";
  var DEM_TILES = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png";

  var trackCache = {}, trackInflight = {};

  function fetchJson(name, revalidate) {
    // manifest 等「刪除後要立刻生效」的資料用即時參數；軌跡檔用每分鐘參數（與既有頁面一致）。
    return fetch(GPST.bust(CFG.dataBase + name, !revalidate), revalidate ? { cache: "no-cache" } : undefined).then(function (r) {
      if (!r.ok) throw new Error(name + " " + r.status);
      return r.json();
    });
  }

  // 只有使用自訂 repo/branch/data 時，才把參數帶到下一頁（與既有 app.js 的 linkOverrideParams 相同語意）。
  function carry() {
    if (!CFG.repo && CFG.dataPath === "data" && CFG.branch === "main") return "";
    return "repo=" + encodeURIComponent(CFG.repo) + "&branch=" + encodeURIComponent(CFG.branch) + "&data=" + encodeURIComponent(CFG.dataPath);
  }
  function url(page, params) {
    var q = [];
    Object.keys(params || {}).forEach(function (k) { if (params[k] != null && params[k] !== "") q.push(k + "=" + encodeURIComponent(params[k])); });
    var c = carry();
    if (c) q.push(c);
    return page + (q.length ? "?" + q.join("&") : "");
  }
  function activityUrl(id) { return url("activity.html", { track: id }); }
  function workspaceUrl(id, mode) { return url("flythrough.html", { track: id, mode: mode }); }

  function loadManifest() {
    return fetchJson("manifest.json", true).then(function (m) {
      m = m || {};
      m.tracks = (m.tracks || []).slice().sort(function (a, b) { return b.startTimeMs - a.startTimeMs; });
      return m;
    });
  }

  // 標題/備註 overlay（紀錄管理寫入的 meta.json）。不存在或讀取失敗就當作沒有，不影響主流程。
  function loadMeta() {
    return fetchJson("meta.json", true).then(function (m) { return (m && m.tracks) || {}; }, function () { return {}; });
  }

  // 紀錄編號只允許英數、底線與連字號（UUID 那種）；網址裡的 ?track= 不能夾帶路徑，像 ../x 這樣的值直接拒絕。
  var SAFE_TRACK_ID = /^[A-Za-z0-9_-]{1,80}$/;

  function loadTrack(id) {
    if (!SAFE_TRACK_ID.test(String(id))) return Promise.reject(new Error("無效的紀錄編號"));
    if (trackCache[id]) return Promise.resolve(trackCache[id]);
    if (trackInflight[id]) return trackInflight[id];
    trackInflight[id] = fetchJson(id + ".json", false).then(function (t) {
      trackCache[id] = t; delete trackInflight[id]; return t;
    }, function (e) { delete trackInflight[id]; throw e; });
    return trackInflight[id];
  }

  // 路線縮圖：按需載入單筆軌跡、限制同時下載數（清單很長時不會一次打爆）。
  var thumbCache = {}, queue = [], active = 0, LIMIT = 3;
  function thumb(id, w, h) {
    var key = id + "@" + w + "x" + h;
    if (thumbCache[key]) return thumbCache[key];
    thumbCache[key] = new Promise(function (resolve) {
      queue.push(function (done) {
        loadTrack(id).then(function (t) { resolve({ path: GTCore.routePath(t.points, w, h, 10), track: t }); },
          function () { resolve(null); }).then(done);
      });
      pump();
    });
    return thumbCache[key];
  }
  function pump() {
    while (active < LIMIT && queue.length) {
      active++;
      queue.shift()(function () { active--; pump(); });
    }
  }

  // ---- GPS 中斷（捷運內）----
  // 捷運路線資料（data/metro.json，OpenStreetMap）：只有軌跡真的有中斷段才會下載；失敗就當作沒有（中斷段仍會以虛線標示）。
  var metroPromise = null;
  function loadMetro() {
    if (!metroPromise) metroPromise = fetchJson("metro.json", false).then(function (m) { return m; }, function () { return null; });
    return metroPromise;
  }
  // 回傳 { gaps, points, gapEdge, usedMetro }：points 是插入估算路徑後的點（只用於顯示／回放，GPX/JSON 匯出請用原始軌跡）。
  function resolveGaps(track) {
    var pts = track.points || [];
    if (!window.GTMetro || !GTMetro.findGaps(pts).length) return Promise.resolve({ gaps: [], points: pts, gapEdge: null, usedMetro: false });
    return loadMetro().then(function (metro) {
      var gaps = GTMetro.annotate(pts, metro), ins = GTMetro.insertEstimated(pts, gaps);
      return { gaps: gaps, points: ins.points, gapEdge: ins.gapEdge, usedMetro: gaps.some(function (g) { return g.kind === "metro"; }) };
    });
  }
  // 中斷段在地圖上的虛線（每段一個 Feature，顏色＝捷運路線色；未知＝灰）。
  function gapFeatures(gaps) {
    return { type: "FeatureCollection", features: (gaps || []).map(function (g) {
      return { type: "Feature", properties: { color: g.kind === "metro" && g.lines.length ? g.lines[0].color : "#9aa7bd", kind: g.kind },
        geometry: { type: "LineString", coordinates: g.kind === "metro" ? g.coords : [g.from, g.to] } };
    }) };
  }
  // 把連續座標依「中斷邊」旗標切成多段實線（flags[j]＝邊 j-1→j 是否屬於中斷段）。
  function splitRuns(coords, flags) {
    var runs = [], cur = [];
    for (var j = 0; j < coords.length; j++) {
      if (flags && flags[j] && j > 0) { if (cur.length > 1) runs.push(cur); cur = [coords[j]]; }
      else cur.push(coords[j]);
    }
    if (cur.length > 1) runs.push(cur);
    return runs;
  }
  function mid(coords) { return coords[Math.floor(coords.length / 2)]; }
  function gapLabel(g) {
    var min = Math.round(g.dtSec / 60);
    return g.kind === "metro" ? "🚇 " + g.fromName + " → " + g.toName + "（捷運估算）" : "GPS 中斷 " + min + " 分";
  }
  function gapSummaryHtml(gaps, usedMetro, totalDistM) {
    var C = GTCore, esc = C.escapeHtml, straightSum = 0;
    var items = (gaps || []).map(function (g, n) {
      straightSum += g.straightM;
      var dur = C.fmtDur(g.dtSec * 1000);
      if (g.kind === "metro") {
        return "<li><b>" + esc(g.fromName) + " → " + esc(g.toName) + "</b>・" + g.lines.map(function (l) { return esc(l.name); }).join(" / ") + "<br>GPS 中斷 " + dur +
          "；直線 " + (g.straightM / 1000).toFixed(2) + " km，沿捷運路線估算約 " + (g.lengthM / 1000).toFixed(2) + " km（" + g.stops + " 站，平均 " + Math.round(g.speedKmh) + " km/h）</li>";
      }
      return "<li><b>GPS 中斷 " + dur + "</b><br>前後兩點直線 " + (g.straightM / 1000).toFixed(2) + " km；沒有對應的捷運路線，只以虛線連接。</li>";
    });
    return "<ul class=\"gt-gap-list\">" + items.join("") + "</ul>" +
      "<p class=\"gt-note\">估算僅供參考，不改變原始紀錄。距離、均速等統計仍以原始 GPS 計算，其中含中斷段的直線距離約 " + (straightSum / 1000).toFixed(2) + " km" +
      (totalDistM > 0 ? "（占總距離 " + Math.round(straightSum / totalDistM * 100) + "%）" : "") + "。" +
      (usedMetro ? "<br>捷運路線資料 © OpenStreetMap contributors（ODbL）。" : "") + "</p>";
  }

  // 縮圖 SVG（cls 決定粗細/顏色，樣式在 gt-ui.css）。
  // withMap 為 true 時，路線後面鋪衛星圖磚（與詳情頁同一個免費圖源）；圖磚載入失敗就只剩原本的深色底。
  function routeSvg(p, cls, dotR, withMap) {
    if (!p) return "";
    var r = dotR || 4, bg = "";
    if (withMap && p.tiles && p.tiles.length) {
      bg = '<g class="map-bg">' + p.tiles.map(function (t) {
        var url = BASE_TILES.replace("{z}", t.z).replace("{y}", t.y).replace("{x}", t.x);
        // 多留 0.5 單位的重疊，避免圖磚接縫出現細線。
        return '<image href="' + url + '" x="' + t.px.toFixed(2) + '" y="' + t.py.toFixed(2) + '" width="' + (t.pw + 0.5).toFixed(2) + '" height="' + (t.ph + 0.5).toFixed(2) + '" preserveAspectRatio="none"/>';
      }).join("") + '<rect class="map-shade" x="0" y="0" width="' + p.w + '" height="' + p.h + '"/></g>';
      cls += " has-map";
    }
    return '<svg class="' + cls + '" viewBox="0 0 ' + p.w + " " + p.h + '" preserveAspectRatio="xMidYMid meet" aria-hidden="true" focusable="false">' + bg +
      (bg ? '<path class="casing" d="' + p.d + '"/>' : "") +
      '<path class="line" d="' + p.d + '"/>' +
      '<circle class="dot-s" cx="' + p.start[0].toFixed(1) + '" cy="' + p.start[1].toFixed(1) + '" r="' + r + '"/>' +
      '<circle class="dot-e" cx="' + p.end[0].toFixed(1) + '" cy="' + p.end[1].toFixed(1) + '" r="' + r + '"/></svg>';
  }

  // 元素進入畫面才執行（清單很長時才不會一開始就載入所有縮圖）；不支援 IntersectionObserver 就立即執行。
  // 頁面主要內容放在可捲動的 .gt-main 裡，所以觀察的 root 要是那個容器：用視窗當 root 時，
  // 被容器裁切、還沒捲到的項目不會被視為「接近畫面」，預先載入（rootMargin）就失效。
  var ioMap = new WeakMap(), observers = [];
  function observerFor(root) {
    for (var i = 0; i < observers.length; i++) if (observers[i].root === root) return observers[i].io;
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        io.unobserve(en.target);
        var fn = ioMap.get(en.target);
        if (fn) fn();
      });
    }, { root: root, rootMargin: "200px" });
    observers.push({ root: root, io: io });
    return io;
  }
  function lazy(el, cb) {
    if (!("IntersectionObserver" in window)) { cb(); return; }
    ioMap.set(el, cb);
    observerFor(el.closest(".gt-main") || null).observe(el);
  }

  // 圖磚來源（與既有 3D 頁相同：Esri 衛星 + AWS Terrarium DEM，皆免金鑰）。
  function mapStyle(opts) {
    opts = opts || {};
    var layers = [{ id: "base", type: "raster", source: "base" }];
    if (opts.hillshade) layers.push({ id: "hillshade", type: "hillshade", source: "dem", paint: { "hillshade-exaggeration": 0.5 } });
    return {
      version: 8, projection: { type: "globe" }, sky: {},
      sources: {
        base: { type: "raster", tiles: [BASE_TILES], tileSize: 256, maxzoom: 19, attribution: "Esri, Maxar, Earthstar Geographics, GIS User Community" },
        dem: { type: "raster-dem", tiles: [DEM_TILES], encoding: "terrarium", tileSize: 256, maxzoom: 15, attribution: "Elevation: Mapzen / AWS Terrain Tiles" }
      },
      layers: layers
    };
  }

  // 需要時才載入 MapLibre（約 1 MB）：首頁、挑戰頁等不需要地圖的頁面不會下載它。
  var mlPromise = null;
  function loadMapLibre() {
    if (window.maplibregl) return Promise.resolve();
    if (mlPromise) return mlPromise;
    mlPromise = new Promise(function (resolve, reject) {
      var css = document.createElement("link");
      css.rel = "stylesheet"; css.href = "vendor/maplibre-gl.css";
      document.head.appendChild(css);
      var s = document.createElement("script");
      s.src = "vendor/maplibre-gl.js";
      s.onload = function () { resolve(); };
      s.onerror = function () { mlPromise = null; reject(new Error("無法載入地圖引擎（vendor/maplibre-gl.js）")); };
      document.head.appendChild(s);
    });
    return mlPromise;
  }

  // 地圖載入後把版權標示收成 (i) 圖示：預設展開的長文字在手機上會蓋住標題與控制列（圖示仍可點開，授權標示不會消失）。
  function collapseAttribution(map) {
    var el = map.getContainer().querySelector(".maplibregl-ctrl-attrib");
    if (el) { el.classList.remove("maplibregl-compact-show"); el.removeAttribute("open"); }
  }

  function saveBlob(name, text, type) {
    var blob = new Blob([text], { type: type || "application/octet-stream" });
    var a = document.createElement("a");
    a.href = URL.createObjectURL(blob); a.download = name;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 4000);
  }

  // 分享：支援 Web Share 就叫出系統面板（手機），否則複製連結。回傳顯示給使用者的訊息。
  function share(shareUrl, title) {
    var abs = new URL(shareUrl, location.href).href;
    if (navigator.share) {
      return navigator.share({ title: title, url: abs }).then(function () { return "已分享"; }, function (e) {
        if (e && e.name === "AbortError") return "";
        return copy(abs);
      });
    }
    return copy(abs);
  }
  function copy(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text).then(function () { return "已複製連結"; }, function () { return "無法複製，請手動複製網址列"; });
    }
    return Promise.resolve("無法複製，請手動複製網址列");
  }

  window.GT = {
    cfg: CFG, TILES: { base: BASE_TILES, dem: DEM_TILES },
    url: url, activityUrl: activityUrl, workspaceUrl: workspaceUrl, carry: carry,
    fetchJson: fetchJson, loadManifest: loadManifest, loadMeta: loadMeta, loadTrack: loadTrack, thumb: thumb,
    routeSvg: routeSvg, lazy: lazy,
    loadMetro: loadMetro, resolveGaps: resolveGaps, gapFeatures: gapFeatures, splitRuns: splitRuns, mid: mid, gapLabel: gapLabel, gapSummaryHtml: gapSummaryHtml,
    mapStyle: mapStyle, loadMapLibre: loadMapLibre, collapseAttribution: collapseAttribution, saveBlob: saveBlob, share: share
  };
})();
