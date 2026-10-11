// 地球征服計畫：所有進度皆由實際上傳的有效活動紀錄即時推算（重新整理不會歸零）。
// 里程去重/異常規則重用 SOLAR_CORE.validTracks；門檻集中於 earth-levels.js。
(function () {
  "use strict";

  var CONFIG = GPST.resolveConfig();
  var DATA_BASE = CONFIG.dataBase;
  var E = window.EARTH_LEVELS;
  var CORE = window.EARTH_CORE;

  var ALL_LEVELS = E.mileage
    .concat(E.explore)
    .concat(E.exploreBingo)
    .concat(E.vertical.tasks)
    .concat(E.orbit);

  var state = { totalKm: 0, valid: [], unlock: {}, altLoaded: false };
  var detailCache = {};

  function $(id) { return document.getElementById(id); }
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function fmtKm(v) {
    var r = Math.round(v * 100) / 100;
    return r.toLocaleString("zh-Hant", { maximumFractionDigits: 2 });
  }
  function fmtM(v) { return (v >= 0 ? "+" : "") + (Math.round(v * 10) / 10).toLocaleString("zh-Hant", { maximumFractionDigits: 1 }) + " m"; }
  function fmtDate(ms) { var d = new Date(ms); return d.getFullYear() + "/" + pad(d.getMonth() + 1) + "/" + pad(d.getDate()) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes()); }
  function loadJSON(name, revalidate) {
    return fetch(DATA_BASE + name, revalidate ? { cache: "no-cache" } : undefined).then(function (r) {
      if (!r.ok) throw new Error(name + " " + r.status);
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
            loadJSON(ids[idx] + ".json", false).then(function (t) { out[idx] = t; }).catch(function () { out[idx] = null; })
              .then(function () { active--; done++; if (onProgress) onProgress(done, ids.length); if (done === ids.length) resolve(out); else kick(); });
          })(i++);
        }
      }
      kick();
    });
  }

  function progress(count, total) { return total > 0 ? count / total : 0; }
  function unlockedCount(levels) { return levels.filter(function (l) { return state.unlock[l.id]; }).length; }

  // ===== 總覽 =====
  function renderOverview() {
    var total = ALL_LEVELS.length, done = unlockedCount(ALL_LEVELS);
    var nextMileage = E.mileage.filter(function (l) { return !state.unlock[l.id]; })[0] || null;
    $("summary").textContent = "累積 " + fmtKm(state.totalKm) + " km · 已完成 " + done + " / " + total + " 關卡";
    var html = '<div class="e-cards">' +
      ecard(fmtKm(state.totalKm) + " km", "累積總里程（去重後）") +
      ecard(done + " / " + total, "已完成關卡") +
      ecard(nextMileage ? nextMileage.name : "全部完成 🎉", "下一里程關卡") +
      ecard(nextMileage ? fmtKm(Math.max(0, nextMileage.km - state.totalKm)) + " km" : "0 km", "還差里程") +
      "</div>";

    html += '<div class="section-h">子挑戰</div><div class="sub-grid">';
    html += subCard("🌐", "全球里程征服", "累積里程解鎖 8 個地球關卡",
      unlockedCount(E.mileage), E.mileage.length, E.mileage, "mileage");
    html += subCard("🧭", "世界探索任務", "虛擬遠征 + 距離賓果（非真實到訪）",
      unlockedCount(E.explore) + unlockedCount(E.exploreBingo), E.explore.length + E.exploreBingo.length, E.explore.concat(E.exploreBingo), "explore");
    html += subCard("⛰️", "地球垂直極限挑戰", "虛擬征服 + 真實海拔模式",
      unlockedCount(E.vertical.tasks), E.vertical.tasks.length, E.vertical.tasks, "vertical");
    html += subCard("🚀", "地球軌道與逃逸任務", "虛擬里程成就（非真實航太）",
      unlockedCount(E.orbit), E.orbit.length, E.orbit, "orbit");
    html += "</div>";

    html += '<div class="info-note">⚠️ 所有門檻皆為<b>遊戲用累積里程</b>，不代表真實環遊世界、登高、下潛或進入軌道。進度由實際上傳紀錄即時計算，重新整理不會歸零。</div>';
    $("panel-overview").innerHTML = html;
  }
  function ecard(v, l) { return '<div class="e-card"><div class="v">' + v + '</div><div class="l">' + l + "</div></div>"; }
  function subCard(ic, name, desc, done, total, levels, tab) {
    var next = levels.filter(function (l) { return !state.unlock[l.id]; })[0] || null;
    var ratio = next ? Math.min(1, state.totalKm / next.km) : 1;
    return '<div class="sub-card"><div class="sc-top"><span class="sc-ic">' + ic + '</span><span class="sc-name">' + name + "</span></div>" +
      '<div class="sc-desc">' + desc + "</div>" +
      '<div class="sc-line"><span class="k">進度</span> ' + done + " / " + total + " 關卡</div>" +
      (next ? '<div class="sc-line"><span class="k">下一關</span> ' + next.name + "（" + fmtKm(next.km) + " km）</div>" : '<div class="sc-line">✅ 全部完成</div>') +
      '<div class="e-bar"><i style="width:' + (ratio * 100).toFixed(2) + '%"></i></div>' +
      '<div class="btn-row"><button class="btn secondary" data-goto="' + tab + '">查看</button></div></div>';
  }

  // ===== 關卡清單 =====
  function levelList(levels) {
    var html = '<ul class="level-list">';
    levels.forEach(function (l, i) {
      var u = state.unlock[l.id];
      var ratio = Math.min(1, state.totalKm / l.km);
      html += '<li' + (u ? ' class="done"' : "") + '><div class="pos">' + (u ? "✅" : (i + 1)) + '</div>' +
        '<div class="mid"><div class="t1">' + l.name + '</div>' +
        '<div class="t2">門檻 ' + fmtKm(l.km) + " km" + (u ? " · 解鎖於 " + fmtDate(u.ms) : "") + "</div>" +
        '<div class="e-bar"><i style="width:' + (ratio * 100).toFixed(2) + '%"></i></div></div>' +
        '<div class="val">' + (u ? "已解鎖" : (ratio * 100).toFixed(ratio >= 0.01 ? 1 : 4) + "%") + "</div></li>";
    });
    html += "</ul>";
    return html;
  }

  function renderMileage() {
    $("panel-mileage").innerHTML = '<div class="section-h">🌐 全球里程征服</div>' +
      '<div class="info-note">依累積里程解鎖（遊戲門檻，非真實地理環遊）。目前 ' + fmtKm(state.totalKm) + " km。</div>" +
      levelList(E.mileage);
  }

  function renderExplore() {
    var html = '<div class="section-h">🧭 世界探索任務</div>';
    html += '<div class="info-note">目前紀錄<b>沒有</b>可靠的國家／大洲／地理到訪資料，因此以下為明確標示的<b>虛擬遠征</b>，以累積里程推進，不代表實際到訪該地區。</div>';
    html += '<div class="section-h">虛擬遠征（七大洲 + 南北極 + 世界旅行）</div>' + levelList(E.explore);
    html += '<div class="section-h">距離賓果 / 隨機里程任務</div><div class="bingo-grid">';
    E.exploreBingo.forEach(function (b) {
      var u = state.unlock[b.id];
      html += '<div class="bingo-cell' + (u ? " done" : "") + '"><div>' + (u ? "✅ " : "⬜ ") + b.name + "</div>" +
        '<div style="color:var(--muted);font-size:11px;margin-top:4px">' + fmtKm(b.km) + " km</div></div>";
    });
    html += "</div>";
    html += '<div class="info-note">歷史日期活動回顧與 GPS 軌跡探索成就已於<b>競賽中心</b>提供（<a class="link" href="challenges.html">前往競賽中心</a>：歷史上的今天、探索徽章），此處不重複實作，以避免重複資料來源。</div>';
    $("panel-explore").innerHTML = html;
  }

  function renderVertical() {
    var V = E.vertical;
    var span = CORE.verticalSpanKm(V.everestM, V.challengerDeepM);
    var html = '<div class="section-h">⛰️ 地球垂直極限挑戰</div>';
    html += '<div class="info-note">分兩種模式：<b>虛擬征服模式</b>（用累積里程解鎖，非真實高度）與<b>真實海拔紀錄模式</b>（僅在紀錄含可信高度時顯示）。不可將水平里程換算為真實垂直高度。</div>';

    html += '<div class="section-h">A. 虛擬征服模式（累積里程門檻）</div>' + levelList(V.tasks);

    html += '<div class="section-h">B. 真實海拔紀錄模式</div>';
    html += '<div class="info-note">參考值：珠穆朗瑪峰 ' + fmtM(V.everestM) + "、挑戰者深淵 " + fmtM(V.challengerDeepM) +
      "、最高至最深垂直高差 ≈ " + (Math.round(span * 100) / 100) + " km。以下僅在你上傳紀錄含海拔資料時才有意義。</div>";
    html += '<div class="btn-row"><button class="btn" id="loadAlt">載入我的真實海拔紀錄</button></div><div id="altBody"></div>';
    $("panel-vertical").innerHTML = html;
    $("loadAlt").addEventListener("click", loadAltitude);
    if (state.altLoaded) loadAltitude();
  }

  function loadAltitude() {
    var ids = state.valid.map(function (t) { return t.trackId; });
    if (!ids.length) { $("altBody").innerHTML = '<div class="e-empty">尚無有效活動紀錄。</div>'; return; }
    $("loadAlt").textContent = "載入中…";
    loadDetails(ids, function (d, n) { $("loadAlt").textContent = "載入中… " + Math.round(d / n * 100) + "%"; })
      .then(function (all) {
        state.altLoaded = true;
        $("loadAlt").style.display = "none";
        var a = CORE.altitudeStats(all);
        if (!a.has) {
          $("altBody").innerHTML = '<div class="e-empty warn">資料不足：已上傳的紀錄沒有可信的海拔欄位，無法顯示真實海拔紀錄。</div>';
          return;
        }
        var V = E.vertical;
        var html = '<div class="e-cards">' +
          ecard(a.maxAlt === null ? "—" : fmtM(a.maxAlt), "你的最高海拔（紀錄）") +
          ecard(a.minAlt === null ? "—" : fmtM(a.minAlt), "你的最低海拔（紀錄）") +
          ecard(fmtM(a.ascent), "累積爬升") +
          ecard(fmtM(a.descent), "累積下降") +
          "</div>";
        html += '<div class="info-note">珠峰比較：你的最高海拔 ' + (a.maxAlt === null ? "—" : fmtM(a.maxAlt)) +
          "，距珠峰 " + fmtM(V.everestM) + " " + (a.maxAlt === null ? "" : (a.maxAlt >= V.everestM ? "已達/超越 ✅" : "還差 " + fmtM(V.everestM - a.maxAlt))) + "。</div>";
        html += '<div class="info-note warn">GPS 海拔誤差較大，且一般 GPS 紀錄無法證明曾到達海底深處；深海任務僅能以「虛擬征服模式」完成。</div>';
        $("altBody").innerHTML = html;
      });
  }

  function renderOrbit() {
    $("panel-orbit").innerHTML = '<div class="section-h">🚀 地球軌道與逃逸任務</div>' +
      '<div class="info-note">以下為<b>虛擬里程成就</b>，以累積活動里程解鎖，不代表真實進入軌道、離開地球或達成任何航太任務。</div>' +
      levelList(E.orbit);
  }

  // ===== 分頁 =====
  var TABS = [["overview", "🏠 總覽"], ["mileage", "🌐 全球里程"], ["explore", "🧭 世界探索"], ["vertical", "⛰️ 垂直極限"], ["orbit", "🚀 軌道與逃逸"]];
  var RENDERERS = { overview: renderOverview, mileage: renderMileage, explore: renderExplore, vertical: renderVertical, orbit: renderOrbit };

  function showTab(id) {
    Array.prototype.forEach.call(document.querySelectorAll(".e-tabs button"), function (b) { b.className = b.dataset.tab === id ? "active" : ""; });
    Array.prototype.forEach.call(document.querySelectorAll(".e-panel"), function (p) { p.className = "e-panel" + (p.id === "panel-" + id ? " active" : ""); });
    try { RENDERERS[id](); } catch (e) { $("panel-" + id).innerHTML = '<div class="e-empty err">載入失敗：' + e.message + "</div>"; }
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
    loadJSON("manifest.json", true).then(function (m) {
      var res = CORE.cumulativeUnlock(ALL_LEVELS, (m && m.tracks) || []);
      state.totalKm = res.totalKm;
      state.valid = res.valid;
      state.unlock = res.unlock;
      showTab("overview");
    }).catch(function (e) {
      $("summary").textContent = "讀取 manifest 失敗：" + e.message;
      $("panel-overview").className = "e-panel active";
      $("panel-overview").innerHTML = '<div class="e-empty err">無法載入紀錄：' + e.message + "</div>";
    });
  }

  init();
})();
