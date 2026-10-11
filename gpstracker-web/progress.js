// 全挑戰同步進度：一次載入 manifest，同步重算所有挑戰（台灣/地球/太陽系/航海家）的進度。
// 里程只計一次（SOLAR_CORE.validTracks 去重 + 異常過濾）；目標門檻來自各集中設定檔。
(function () {
  "use strict";

  var CONFIG = GPST.resolveConfig();
  var DATA_BASE = CONFIG.dataBase;
  var CORE = window.PROGRESS_CORE;

  var state = { totalKm: 0, count: 0, updatedAt: null, goals: [], voyager: null, voyagerStale: false, voyagerErr: null };

  function $(id) { return document.getElementById(id); }
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function fmtKm(v) { return (Math.round(v * 100) / 100).toLocaleString("zh-Hant", { maximumFractionDigits: 2 }); }
  function fmtDate(ms) { if (!ms) return "未知"; var d = new Date(ms); return d.getFullYear() + "/" + pad(d.getMonth() + 1) + "/" + pad(d.getDate()) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes()); }
  function loadJSON(name, revalidate) {
    return fetch(DATA_BASE + name, revalidate ? { cache: "no-cache" } : undefined).then(function (r) {
      if (!r.ok) throw new Error(name + " " + r.status);
      return r.json();
    });
  }
  function kindLabel(k) { return k === "mileage" ? "里程關卡" : k === "reference" ? "參考追逐" : "虛擬目標"; }
  function escapeHtml(s) { return String(s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }

  function load() {
    $("note").className = "pg-note"; $("note").textContent = "載入中…";
    return loadJSON("manifest.json", true).then(function (m) {
      var tracks = (m && m.tracks) || [];
      var valid = window.SOLAR_CORE.validTracks(tracks);
      var totalKm = 0;
      valid.forEach(function (t) { totalKm += (t.distanceMeters || 0) / 1000; });
      state.totalKm = totalKm;
      state.count = valid.length;
      state.updatedAt = (m && m.updatedAt) || null;

      // 航海家（獨立參考資料；失敗則保留缺值並標示）
      return loadJSON("voyager.json", true).then(function (v) {
        state.voyager = v; state.voyagerErr = null;
        state.voyagerStale = v && v.updatedAt ? (Date.now() - v.updatedAt) / 86400000 > 7 : true;
      }).catch(function (e) {
        state.voyager = null; state.voyagerErr = e.message; state.voyagerStale = true;
      });
    }).then(function () {
      state.goals = CORE.buildGoals({
        totalKm: state.totalKm,
        solar: window.SOLAR_LEVELS,
        earth: window.EARTH_LEVELS,
        taiwan: window.TAIWAN_LEVELS,
        voyager: state.voyager
      });
      render();
    }).catch(function (e) {
      $("note").className = "pg-note err";
      $("note").textContent = "讀取失敗：" + e.message;
      $("tableWrap").innerHTML = '<div class="pg-note err">無法載入紀錄：' + escapeHtml(e.message) + "</div>";
    });
  }

  function render() {
    var g = state.goals;
    var done = g.filter(function (x) { return x.done; }).length;
    var ageDays = state.updatedAt ? (Date.now() - state.updatedAt) / 86400000 : null;

    $("summary").textContent = "全站 " + fmtKm(state.totalKm) + " km · " + state.count + " 筆有效活動 · " + g.length + " 個目標";
    $("cards").innerHTML =
      card(fmtKm(state.totalKm) + " km", "全站有效累計里程（計一次）") +
      card(state.count + " 筆", "有效活動數") +
      card(done + " / " + g.length, "已達成目標") +
      card(fmtDate(state.updatedAt), "紀錄資料更新時間");

    var noteLines = [];
    noteLines.push("所有目標共用同一份有效里程，不會重複加總；累計里程改變時，全部挑戰同步更新（本頁載入即重算）。");
    if (ageDays !== null && ageDays > 3) noteLines.push("⚠️ 紀錄資料距今約 " + Math.floor(ageDays) + " 天，可能非最新。");
    if (state.voyagerErr) noteLines.push("⚠️ 航海家資料同步失敗／缺少（" + state.voyagerErr + "），該項目以缺值顯示並標示。");
    else if (state.voyagerStale) noteLines.push("⚠️ 航海家資料已過期（> 7 天），顯示最後一次有效資料。");
    $("note").className = "pg-note" + ((state.voyagerErr || state.voyagerStale) ? " warn" : "");
    $("note").innerHTML = noteLines.map(escapeHtml).join("<br>");

    buildFilters();
    renderTable();
  }
  function card(v, l) { return '<div class="pg-card"><div class="v">' + escapeHtml(v) + '</div><div class="l">' + escapeHtml(l) + "</div></div>"; }

  function buildFilters() {
    var sel = $("chFilter");
    if (sel.dataset.built) return;
    var seen = {};
    state.goals.forEach(function (g) { seen[g.challenge] = true; });
    Object.keys(seen).forEach(function (c) { var o = document.createElement("option"); o.value = c; o.textContent = c; sel.appendChild(o); });
    sel.dataset.built = "1";
  }

  function renderTable() {
    var cf = $("chFilter").value, kf = $("kindFilter").value, onlyInc = $("onlyIncomplete").checked;
    var goals = state.goals.filter(function (g) {
      if (cf && g.challenge !== cf) return false;
      if (kf && g.kind !== kf) return false;
      if (onlyInc && g.done) return false;
      return true;
    });
    var grouped = CORE.groupByChallenge(goals);
    if (!grouped.order.length) { $("tableWrap").innerHTML = '<div class="pg-note">沒有符合條件的目標。</div>'; return; }

    var html = "";
    grouped.order.forEach(function (c) {
      var list = grouped.map[c];
      var doneCount = list.filter(function (x) { return x.done; }).length;
      html += '<div class="pg-group-h">' + escapeHtml(c) + "　<small style=\"color:var(--muted);font-weight:400\">" + doneCount + " / " + list.length + " 已達成</small></div>";
      html += '<div class="pg-table-wrap"><table class="pg-table"><thead><tr>' +
        "<th>目標</th><th>類型</th><th>目前累計</th><th>目標</th><th>完成比例</th><th>剩餘</th><th>超越</th></tr></thead><tbody>";
      list.forEach(function (g) {
        var pctText = g.pct.toFixed(g.pct >= 100 ? 1 : (g.pct >= 1 ? 2 : 6)) + "%";
        html += '<tr' + (g.done ? ' class="done"' : "") + ">" +
          '<td class="name">' + escapeHtml(g.name) + (g.done ? " ✅" : "") + "</td>" +
          '<td><span class="pg-kind ' + g.kind + '">' + kindLabel(g.kind) + "</span></td>" +
          '<td class="num">' + fmtKm(g.current) + " km</td>" +
          '<td class="num">' + fmtKm(g.target) + " km</td>" +
          '<td class="num"><span class="pg-prog"><i style="width:' + Math.min(100, g.pct).toFixed(2) + '%"></i></span>' + pctText + "</td>" +
          '<td class="num">' + (g.remaining > 0 ? fmtKm(g.remaining) + " km" : "—") + "</td>" +
          '<td class="num">' + (g.overshoot > 0 ? "+" + fmtKm(g.overshoot) + " km" : "—") + "</td>" +
          "</tr>";
      });
      html += "</tbody></table></div>";
    });
    $("tableWrap").innerHTML = html;
  }

  $("chFilter").addEventListener("change", renderTable);
  $("kindFilter").addEventListener("change", renderTable);
  $("onlyIncomplete").addEventListener("change", renderTable);
  $("refresh").addEventListener("click", function () { $("refresh").textContent = "更新中…"; load().then(function () { $("refresh").textContent = "🔄 重新整理"; }); });

  load();
})();
