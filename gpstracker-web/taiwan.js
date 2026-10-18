// 台灣征服計畫：所有里程與實際探索皆由實際上傳紀錄計算；門檻/邊界集中於 taiwan-levels.js。
// 里程去重/異常沿用 SOLAR_CORE.validTracks；解鎖沿用 EARTH_CORE.cumulativeUnlock。
(function () {
  "use strict";

  var CONFIG = GPST.resolveConfig();
  var DATA_BASE = CONFIG.dataBase;
  var T = window.TAIWAN_LEVELS;
  var CORE = window.TAIWAN_CORE;

  var ALL_LEVELS = T.mileage.concat(T.loop).concat(T.vertical.tasks);

  var state = { totalKm: 0, valid: [], unlock: {}, analysis: null, counties: null, altLoaded: false };
  var detailsCache = null;
  var countiesGeo = null;
  var countiesChecked = false;

  function $(id) { return document.getElementById(id); }
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function fmtKm(v) { return (Math.round(v * 100) / 100).toLocaleString("zh-Hant", { maximumFractionDigits: 2 }); }
  function fmtM(v) { return (v >= 0 ? "+" : "") + (Math.round(v * 10) / 10).toLocaleString("zh-Hant", { maximumFractionDigits: 1 }) + " m"; }
  function fmtDate(ms) { var d = new Date(ms); return d.getFullYear() + "/" + pad(d.getMonth() + 1) + "/" + pad(d.getDate()) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes()); }
  function loadJSON(url, revalidate) {
    return fetch(GPST.bust(url, !revalidate), revalidate ? { cache: "no-cache" } : undefined).then(function (r) {
      if (!r.ok) throw new Error(url + " " + r.status);
      return r.json();
    });
  }
  function loadDetails(ids, onProgress) {
    return new Promise(function (resolve) {
      var out = new Array(ids.length), i = 0, active = 0, done = 0;
      if (!ids.length) { resolve(out); return; }
      function kick() {
        while (active < 4 && i < ids.length) {
          (function (idx) {
            active++;
            loadJSON(DATA_BASE + ids[idx] + ".json", false).then(function (t) { out[idx] = t; }).catch(function () { out[idx] = null; })
              .then(function () { active--; done++; if (onProgress) onProgress(done, ids.length); if (done === ids.length) resolve(out); else kick(); });
          })(i++);
        }
      }
      kick();
    });
  }
  function ensureDetails(onProgress) {
    if (detailsCache) return Promise.resolve(detailsCache);
    if (!state.valid.length) return Promise.resolve([]);
    return loadDetails(state.valid.map(function (t) { return t.trackId; }), onProgress).then(function (all) { detailsCache = all; return all; });
  }

  function unlockedCount(levels) { return levels.filter(function (l) { return state.unlock[l.id]; }).length; }
  function ecard(v, l) { return '<div class="e-card"><div class="v">' + v + '</div><div class="l">' + l + "</div></div>"; }
  function levelList(levels) {
    var html = '<ul class="level-list">';
    levels.forEach(function (l, i) {
      var u = state.unlock[l.id], ratio = Math.min(1, state.totalKm / l.km);
      html += '<li' + (u ? ' class="done"' : "") + '><div class="pos">' + (u ? "✅" : (i + 1)) + '</div>' +
        '<div class="mid"><div class="t1">' + l.name + '</div><div class="t2">門檻 ' + fmtKm(l.km) + " km" + (u ? " · 解鎖於 " + fmtDate(u.ms) : "") + '</div>' +
        '<div class="e-bar"><i style="width:' + (ratio * 100).toFixed(2) + '%"></i></div></div>' +
        '<div class="val">' + (u ? "已解鎖" : (ratio * 100).toFixed(ratio >= 0.01 ? 1 : 4) + "%") + "</div></li>";
    });
    html += "</ul>";
    return html;
  }

  // ===== 總覽 =====
  function renderOverview() {
    var next = T.mileage.filter(function (l) { return !state.unlock[l.id]; })[0] || null;
    $("summary").textContent = "累積 " + fmtKm(state.totalKm) + " km · 里程關卡 " + unlockedCount(T.mileage) + "/" + T.mileage.length;
    var html = '<div class="e-cards">' +
      ecard(fmtKm(state.totalKm) + " km", "台灣累積里程（全站去重後）") +
      ecard(unlockedCount(T.mileage) + " / " + T.mileage.length, "里程關卡") +
      ecard(unlockedCount(T.loop) + " / " + T.loop.length, "環島挑戰（虛擬）") +
      ecard(next ? next.name : "全部完成 🎉", "下一里程關卡") +
      ecard(next ? fmtKm(Math.max(0, next.km - state.totalKm)) + " km" : "0 km", "還差里程") +
      "</div>";
    html += '<div class="section-h">子挑戰</div><div class="sub-grid">' +
      sub("🛣️", "台灣里程征服", "累積里程解鎖 8 關", "mileage", unlockedCount(T.mileage), T.mileage.length, T.mileage) +
      sub("🔁", "台灣環島挑戰", "虛擬環島里程（非實際環島認證）", "loop", unlockedCount(T.loop), T.loop.length, T.loop) +
      sub("🗺️", "台灣地理探索", "由 GPS 座標近似判定區域", "geo", null, null, null) +
      sub("🏙️", "台灣縣市制霸", "需縣市邊界資料", "county", null, null, null) +
      sub("⛰️", "台灣高山與垂直挑戰", "虛擬里程 + 真實海拔", "vertical", unlockedCount(T.vertical.tasks), T.vertical.tasks.length, T.vertical.tasks) +
      sub("🧭", "台灣四極點挑戰", "概略座標近似判定（待核實）", "extremes", null, null, null) +
      "</div>";
    html += '<div class="info-note">⚠️ 里程門檻為<b>遊戲成就</b>，不代表實際走訪台灣；環島為<b>虛擬</b>，非實際環島認證。實際到訪僅依 GPS 座標近似判定，並明確標示資料不足。</div>';
    $("panel-overview").innerHTML = html;
    bind(); // 子卡「查看」按鈕
  }
  function sub(ic, name, desc, tab, done, total, levels) {
    var line = (done === null) ? '<div class="sc-line"><span class="k">狀態</span> 依 GPS 座標判定</div>' :
      '<div class="sc-line"><span class="k">進度</span> ' + done + " / " + total + " 關卡</div>";
    var ratio = 1;
    if (levels) { var nx = levels.filter(function (l) { return !state.unlock[l.id]; })[0]; ratio = nx ? Math.min(1, state.totalKm / nx.km) : 1; }
    else ratio = 0;
    return '<div class="sub-card"><div class="sc-top"><span class="sc-ic">' + ic + '</span><span class="sc-name">' + name + "</span></div>" +
      '<div class="sc-desc">' + desc + "</div>" + line +
      '<div class="e-bar"><i style="width:' + (ratio * 100).toFixed(2) + '%"></i></div>' +
      '<div class="btn-row"><button class="btn secondary" data-goto="' + tab + '">查看</button></div></div>';
  }

  function renderMileage() {
    $("panel-mileage").innerHTML = '<div class="section-h">🛣️ 台灣里程征服</div>' +
      '<div class="info-note">累積有效里程解鎖（遊戲成就，非實際走訪）。目前 ' + fmtKm(state.totalKm) + " km。</div>" + levelList(T.mileage);
  }
  function renderLoop() {
    $("panel-loop").innerHTML = '<div class="section-h">🔁 台灣環島挑戰</div>' +
      '<div class="info-note warn">以下為<b>虛擬環島</b>：僅依累積里程推進，<b>不代表</b>你實際完成環島。真實環島認證需依實際 GPS 軌跡與地理路線判定，本頁未提供。</div>' + levelList(T.loop);
  }

  // ===== 地理探索 =====
  function renderGeo() {
    $("panel-geo").innerHTML = '<div class="section-h">🗺️ 台灣地理探索</div>' +
      '<div class="info-note">依<b>實際 GPS 座標</b>與概略矩形範圍（非官方邊界）判定是否曾進入該區。<a class="link" href="#" id="geoRun">開始分析我的紀錄</a></div><div id="geoBody"></div>';
    $("geoRun").addEventListener("click", function (e) { e.preventDefault(); runGeo(); });
    if (state.analysis) renderGeoBody();
  }
  function runGeo() {
    $("geoBody").innerHTML = '<div class="e-empty">分析中…</div>';
    ensureDetails(function (d, n) { $("geoBody").innerHTML = '<div class="e-empty">載入軌跡… ' + Math.round(d / n * 100) + "%</div>"; }).then(function (all) {
      state.analysis = CORE.analyzeDetails(all, T.regions, T.extremes);
      renderGeoBody();
    });
  }
  function renderGeoBody() {
    var a = state.analysis;
    if (!a) return;
    var html = '<div class="chip-grid">';
    T.regions.forEach(function (r) {
      var hit = !!a.regionHits[r.id];
      html += '<div class="chip' + (hit ? " hit" : "") + '"><span class="dot"></span><span>' + r.name + (hit ? " ✅" : "") + "</span></div>";
    });
    html += "</div>";
    var ns = a.maxLatSpan >= T.span.northSouthDeg, ew = a.maxLonSpan >= T.span.eastWestDeg;
    html += '<div class="section-h">跨距挑戰</div><div class="info-note">' +
      "台灣本島南北縱向挑戰：最大縱向跨距 " + a.maxLatSpan.toFixed(3) + "°（門檻 " + T.span.northSouthDeg + "°）→ " + (ns ? "已達成 ✅" : "未達成") + "<br>" +
      "台灣東西向橫越挑戰：最大橫向跨距 " + a.maxLonSpan.toFixed(3) + "°（門檻 " + T.span.eastWestDeg + "°）→ " + (ew ? "已達成 ✅" : "未達成") + "</div>";
    html += '<div class="info-note">區域為概略矩形近似，可能與官方邊界不同；僅憑起訖點不足以判定途經所有地區。</div>';
    $("geoBody").innerHTML = html;
  }

  // ===== 縣市制霸 =====
  function renderCounty() {
    $("panel-county").innerHTML = '<div class="section-h">🏙️ 台灣縣市制霸</div>' +
      '<div class="info-note">縣市判定需要可靠的縣市邊界資料（GeoJSON）。本專案目前<b>未內嵌</b>官方縣市邊界檔，因此不捏造任何完成狀態。</div>' +
      '<div id="countyBody"></div>';
    loadCounties().then(function (geo) {
      if (!geo) {
        $("countyBody").innerHTML = '<div class="e-empty warn">資料不足：未提供縣市邊界資料。若要啟用，請將縣市邊界 GeoJSON 放到 <code>' + escapeHtml(DATA_BASE) + T.countiesFile +
          '</code>（FeatureCollection，每個 feature 具 <code>properties.name</code>），本頁即會依 GPS 座標判定。</div>';
        return;
      }
      $("countyBody").innerHTML = '<div class="btn-row"><button class="btn" id="countyRun">分析已探索縣市</button></div><div id="countyList"></div>';
      $("countyRun").addEventListener("click", function () {
        $("countyList").innerHTML = '<div class="e-empty">分析中…</div>';
        ensureDetails().then(function (all) {
          state.counties = CORE.countiesVisited(all, geo);
          var names = Object.keys(state.counties);
          var html = '<div class="e-cards">' + ecard(names.length, "已探索縣市數") + "</div>";
          html += names.length ? '<div class="chip-grid">' + names.map(function (n) { return '<div class="chip hit"><span class="dot"></span><span>' + escapeHtml(n) + "</span></div>"; }).join("") + "</div>"
            : '<div class="e-empty">依現有 GPS 軌跡，尚無判定進入任何縣市。</div>';
          html += '<div class="info-note">僅依實際軌跡座標落在縣市多邊形內判定；不會因起訖點或累積里程推斷經過。</div>';
          $("countyList").innerHTML = html;
        });
      });
    });
  }
  function loadCounties() {
    if (countiesChecked) return Promise.resolve(countiesGeo);
    countiesChecked = true;
    return loadJSON(DATA_BASE + T.countiesFile, true).then(function (g) {
      countiesGeo = (g && g.features) ? g : null; return countiesGeo;
    }).catch(function () { countiesGeo = null; return null; });
  }

  // ===== 高山與垂直 =====
  function renderVertical() {
    var html = '<div class="section-h">⛰️ 台灣高山與垂直挑戰</div>';
    html += '<div class="info-note">分<b>虛擬征服模式</b>（累積里程，非實際登頂）與<b>真實海拔模式</b>（僅在紀錄含可信海拔時顯示）。不可將水平里程換算為海拔。</div>';
    html += '<div class="section-h">A. 虛擬征服模式</div>' + levelList(T.vertical.tasks);
    html += '<div class="section-h">B. 真實海拔模式</div><div class="btn-row"><button class="btn" id="altRun">載入我的真實海拔紀錄</button></div><div id="altBody"></div>';
    $("panel-vertical").innerHTML = html;
    $("altRun").addEventListener("click", runAlt);
    if (state.altLoaded) runAlt();
  }
  function runAlt() {
    $("altBody").innerHTML = '<div class="e-empty">載入中…</div>';
    ensureDetails(function (d, n) { $("altBody").innerHTML = '<div class="e-empty">載入軌跡… ' + Math.round(d / n * 100) + "%</div>"; }).then(function (all) {
      state.altLoaded = true;
      var a = CORE.altitudeStats(all);
      if (!a.has) { $("altBody").innerHTML = '<div class="e-empty warn">資料不足：紀錄沒有可信的海拔欄位，無法顯示真實海拔。</div>'; return; }
      var html = '<div class="e-cards">' +
        ecard(a.maxAlt === null ? "—" : fmtM(a.maxAlt), "你的最高海拔") +
        ecard(a.minAlt === null ? "—" : fmtM(a.minAlt), "你的最低海拔") +
        ecard(fmtM(a.ascent), "累積爬升") + ecard(fmtM(a.descent), "累積下降") + "</div>";
      html += '<div class="info-note">玉山主峰參考高度 ' + fmtM(T.vertical.yushanM) + "：" +
        (a.maxAlt === null ? "—" : (a.maxAlt >= T.vertical.yushanM ? "最高海拔已達/超越 ✅" : "最高海拔距玉山約 " + fmtM(T.vertical.yushanM - a.maxAlt))) + "。</div>";
      html += '<div class="info-note warn">GPS 海拔誤差較大；僅有起訖點海拔時無法代表整段累積爬升。登頂判定需可信軌跡，本頁不宣稱你已登頂玉山或百岳。</div>';
      $("altBody").innerHTML = html;
    });
  }

  // ===== 四極點 =====
  function renderExtremes() {
    $("panel-extremes").innerHTML = '<div class="section-h">🧭 台灣四極點挑戰</div>' +
      '<div class="info-note warn">' + escapeHtml(T.extremesNote) + " 判定採半徑內視為到訪，屬近似；如需精確認證請以官方測量座標為準。</div>" +
      '<div class="btn-row"><button class="btn" id="extRun">分析我的實際到訪</button></div><div id="extBody"></div>';
    $("extRun").addEventListener("click", function () {
      $("extBody").innerHTML = '<div class="e-empty">分析中…</div>';
      ensureDetails().then(function (all) {
        var a = CORE.analyzeDetails(all, [], T.extremes);
        var html = '<ul class="ext-list">';
        T.extremes.forEach(function (x) {
          var hit = !!a.extremeHits[x.id];
          html += '<li' + (hit ? ' class="hit"' : "") + '><div class="e-mid"><div class="e-t1">' + x.name + '</div>' +
            '<div class="e-t2">概略座標 ' + x.lat.toFixed(3) + ", " + x.lon.toFixed(3) + " · 半徑 " + x.radiusKm + ' km</div></div>' +
            '<span class="e-tag">' + (hit ? "已到訪 ✅" : "未到訪") + "</span></li>";
        });
        html += "</ul>";
        html += '<div class="info-note">僅累積里程可解鎖四極點<b>虛擬遠征</b>（不在此判定）。實際到訪僅依 GPS 座標落在半徑內判定。</div>';
        $("extBody").innerHTML = html;
      });
    });
  }

  function escapeHtml(s) { return String(s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function bind() { /* 子卡按鈕交由全域事件處理 */ }

  var TABS = [["overview", "🏠 總覽"], ["mileage", "🛣️ 里程征服"], ["loop", "🔁 環島挑戰"], ["geo", "🗺️ 地理探索"], ["county", "🏙️ 縣市制霸"], ["vertical", "⛰️ 高山垂直"], ["extremes", "🧭 四極點"]];
  var RENDERERS = { overview: renderOverview, mileage: renderMileage, loop: renderLoop, geo: renderGeo, county: renderCounty, vertical: renderVertical, extremes: renderExtremes };

  function showTab(id) {
    Array.prototype.forEach.call(document.querySelectorAll(".e-tabs button"), function (b) { b.className = b.dataset.tab === id ? "active" : ""; });
    Array.prototype.forEach.call(document.querySelectorAll(".e-panel"), function (p) { p.className = "e-panel" + (p.id === "panel-" + id ? " active" : ""); });
    try { RENDERERS[id](); } catch (e) { $("panel-" + id).innerHTML = '<div class="e-empty err">載入失敗：' + escapeHtml(e.message) + "</div>"; }
  }

  document.addEventListener("click", function (e) {
    var t = e.target;
    if (t && t.dataset && t.dataset.goto) showTab(t.dataset.goto);
  });

  function init() {
    var nav = $("tabs");
    TABS.forEach(function (t) {
      var b = document.createElement("button");
      b.textContent = t[1]; b.dataset.tab = t[0];
      b.addEventListener("click", function () { showTab(t[0]); });
      nav.appendChild(b);
    });
    loadJSON(DATA_BASE + "manifest.json", true).then(function (m) {
      var tracks = (m && m.tracks) || [];
      var device = window.GTCore.initDeviceBar("deviceBar", tracks, null, m.devices || []);
      var res = CORE.cumulativeUnlock(ALL_LEVELS, window.GTCore.filterByDevice(tracks, device));
      state.totalKm = res.totalKm; state.valid = res.valid; state.unlock = res.unlock;
      showTab("overview");
    }).catch(function (e) {
      $("summary").textContent = "讀取 manifest 失敗：" + e.message;
      $("panel-overview").className = "e-panel active";
      $("panel-overview").innerHTML = '<div class="e-empty err">無法載入紀錄：' + escapeHtml(e.message) + "</div>";
    });
  }

  init();
})();
