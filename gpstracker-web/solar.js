// 太陽系征服計畫：所有里程皆來自實際上傳的有效活動紀錄；關卡門檻設定於 solar-levels.js。
(function () {
  "use strict";

  var CONFIG = GPST.resolveConfig();
  var DATA_BASE = CONFIG.dataBase;
  var L = window.SOLAR_LEVELS;
  var CORE = window.SOLAR_CORE;
  var LEVELS = CORE.levels; // 已依 km 單調遞增排序
  var VOYAGER_STALE_DAYS = 7;     // 超過此天數視為資料過期

  var state = { totalKm: 0, valid: [], unlock: {}, filter: "all", prevUnlocked: null };

  function $(id) { return document.getElementById(id); }
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function fmtKm(v) { return Math.round(v).toLocaleString("zh-Hant"); }
  function fmtDateTime(ms) {
    var d = new Date(ms);
    return d.getFullYear() + "/" + pad(d.getMonth() + 1) + "/" + pad(d.getDate()) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes());
  }
  function loadJSON(name, revalidate) {
    return fetch(GPST.bust(DATA_BASE + name, !revalidate), revalidate ? { cache: "no-cache" } : undefined).then(function (r) {
      if (!r.ok) throw new Error(name + " " + r.status);
      return r.json();
    });
  }

  // 里程統計（去重／異常過濾）與關卡解鎖計算見 solar-core.js（window.SOLAR_CORE）。

  function currentLevel() {
    var cur = null;
    LEVELS.forEach(function (l) { if (state.totalKm >= l.km) cur = l; });
    return cur;
  }
  function nextLevel() {
    for (var i = 0; i < LEVELS.length; i++) if (state.totalKm < LEVELS[i].km) return LEVELS[i];
    return null;
  }

  // ===== 渲染 =====
  function renderSummary() {
    var cur = currentLevel(), next = nextLevel();
    var diff = next ? (next.km - state.totalKm) : 0;
    $("summary").textContent = "累積 " + fmtKm(state.totalKm) + " km · " +
      (cur ? "目前關卡 " + cur.display : "尚未征服任何關卡") +
      (next ? " · 下一站 " + next.display : " · 已征服全部關卡");
    $("solarCards").innerHTML =
      card(fmtKm(state.totalKm) + " km", "累積總里程（去重後）") +
      card(cur ? cur.display : "—", "當前關卡") +
      card(next ? next.display : "全部征服 🎉", "下一個目標") +
      card(next ? fmtKm(diff) + " km" : "0 km", "距離下一關還差") +
      card(fmtKm(state.valid.length) + " 筆", "有效活動數");
  }
  function card(v, l) { return '<div class="solar-card"><div class="v">' + v + '</div><div class="l">' + l + "</div></div>"; }

  function renderNextProgress() {
    var next = nextLevel();
    if (!next) { $("nextProgress").innerHTML = '<div class="big-progress"><div class="bp-head"><span>🎉 已征服全部關卡</span><span>100%</span></div><div class="bp-sub">你已走遍設定的所有里程里程碑。</div></div>'; return; }
    var ratio = Math.min(1, state.totalKm / next.km);
    $("nextProgress").innerHTML =
      '<div class="big-progress"><div class="bp-head"><span>下一站：' + next.display + '</span><span>' + (ratio * 100).toFixed(ratio >= 0.01 ? 2 : 6) + '%</span></div>' +
      '<div class="scale"><i style="width:' + (ratio * 100).toFixed(3) + '%"></i></div>' +
      '<div class="bp-sub">' + fmtKm(state.totalKm) + " / " + fmtKm(next.km) + " km · 還差 " + fmtKm(next.km - state.totalKm) + " km</div></div>";
  }

  function bodyCard(l) {
    var unlocked = !!state.unlock[l.id];
    var isHiddenLocked = l.hidden && !unlocked;
    var ratio = Math.min(1, state.totalKm / l.km);
    var cls = "body-card " + (unlocked ? "conquered" : "locked");
    if (state.animId === l.id) cls += " just-unlocked";
    var name = isHiddenLocked ? "🪐 ???（隱藏關卡）" : l.display;
    var desc = isHiddenLocked ? "解鎖後才會揭曉。" : l.desc;
    var dateLine = unlocked
      ? '<div class="bc-date">✅ 征服於 ' + fmtDateTime(state.unlock[l.id].ms) + "</div>"
      : "";
    return '<div class="' + cls + '">' +
      (unlocked ? '<div class="badge-tag">已征服</div>' : "") +
      '<div class="bc-top"><div class="bc-ic">' + (isHiddenLocked ? "🪐" : l.icon) + '</div>' +
      '<div class="bc-name">' + name + "</div></div>" +
      '<div class="bc-desc">' + desc + "</div>" +
      '<div class="bc-km">門檻 ' + fmtKm(l.km) + " km</div>" +
      '<div class="mini"><i style="width:' + (ratio * 100).toFixed(3) + '%"></i></div>' +
      dateLine + "</div>";
  }

  function renderBodies() {
    var html = "";
    LEVELS.forEach(function (l) {
      var unlocked = !!state.unlock[l.id];
      if (state.filter === "conquered" && !unlocked) return;
      if (state.filter === "locked" && unlocked) return;
      html += bodyCard(l);
    });
    $("bodyGrid").innerHTML = html || '<div class="solar-empty">沒有符合篩選的關卡。</div>';
    state.animId = null;
  }

  // ===== 航海家追逐 =====
  function renderVoyager() {
    loadJSON("voyager.json", true).then(function (data) {
      renderVoyagerData(data, null);
    }).catch(function (e) {
      renderVoyagerData(null, e.message);
    });
  }
  function renderVoyagerData(data, errMsg) {
    var box = $("voyager");
    var voyagers = L.voyagers;
    var html = "";
    var noteCls = "", noteText = "";
    if (errMsg || !data || !data.probes) {
      noteCls = "err";
      noteText = "⚠️ 同步失敗／尚無資料（" + (errMsg || "voyager.json 不存在") + "）。保留上次有效資料；請稍後由排程同步。";
    }
    voyagers.forEach(function (v) {
      var p = data && data.probes ? data.probes[v.id] : null;
      if (!p) {
        html += '<div class="voyager-card"><div class="vc-name">' + v.display + '</div>' +
          '<div class="vc-row"><span class="k">距太陽</span><span>—</span></div>' +
          '<div class="vc-row"><span class="k">距地球</span><span>—</span></div>' +
          '<div class="vc-row"><span class="k">與你里程差距</span><span>—</span></div>' +
          '<div class="sync-note ' + noteCls + '">' + noteText + "</div></div>";
        return;
      }
      var gap = p.fromSunKm - state.totalKm;
      html += '<div class="voyager-card"><div class="vc-name">' + v.display + "</div>" +
        '<div class="vc-row"><span class="k">距太陽</span><span>' + fmtKm(p.fromSunKm) + " km</span></div>" +
        '<div class="vc-row"><span class="k">距地球</span><span>' + fmtKm(p.fromEarthKm) + " km</span></div>" +
        '<div class="vc-row"><span class="k">你的累積里程</span><span>' + fmtKm(state.totalKm) + " km</span></div>" +
        '<div class="vc-row"><span class="k">差距（距太陽 − 你的里程）</span><span>' + fmtKm(gap) + " km</span></div>" +
        '<div class="sync-note">' + statusLine(data) + "</div></div>";
    });
    box.innerHTML = html;
  }
  function statusLine(data) {
    var src = data.source || "NASA/JPL Horizons";
    var updated = data.updatedAt ? fmtDateTime(data.updatedAt) : "未知";
    var ageDays = data.updatedAt ? (Date.now() - data.updatedAt) / 86400000 : Infinity;
    var cls = ageDays > VOYAGER_STALE_DAYS ? "sync-note stale" : "sync-note";
    var stateText = data.updatedAt
      ? (ageDays > VOYAGER_STALE_DAYS ? "⚠️ 資料已過期（> " + VOYAGER_STALE_DAYS + " 天），保留上次有效資料" : "✅ 同步正常")
      : "⚠️ 尚未成功同步";
    return '<span class="' + cls + '">' + stateText + " · 更新 " + updated + " · 來源 " + src +
      "（參考中心不同：探測器距太陽/地球 vs. 你的地表移動里程）</span>";
  }

  // ===== 我的宇宙旅程 =====
  function renderJourney() {
    var rows = LEVELS.filter(function (l) { return state.unlock[l.id]; });
    if (!rows.length) { $("journey").innerHTML = '<div class="solar-empty">尚無解鎖紀錄。持續記錄，第一站是 ' + LEVELS[0].display + "。</div>"; return; }
    var html = '<ul class="journey">';
    rows.forEach(function (l) {
      var u = state.unlock[l.id];
      html += '<li><div class="j-ic">' + l.icon + '</div><div class="j-mid"><div class="j-t1">' +
        l.display + "（門檻 " + fmtKm(l.km) + ' km）</div><div class="j-t2">征服於 ' + fmtDateTime(u.ms) + '</div></div>' +
        '<a class="link" href="index.html?track=' + encodeURIComponent(u.trackId) + '">查看當時軌跡</a></li>';
    });
    html += "</ul>";
    $("journey").innerHTML = html;
  }

  function renderAll() {
    renderSummary();
    renderNextProgress();
    renderBodies();
    renderJourney();
    $("emptyNote").style.display = state.valid.length ? "none" : "block";
    $("emptyNote").textContent = state.valid.length ? "" : "尚無有效里程紀錄（0 km）。開始記錄活動後，關卡進度會自動更新。";
  }

  function applyData(list) {
    var prev = state.unlock;
    var res = CORE.compute(list);
    var prevIds = Object.keys(prev || {});
    var newly = Object.keys(res.unlock).filter(function (id) { return prevIds.indexOf(id) < 0; });
    // 以里程最大的新解鎖做一次動畫（輕量）。
    state.animId = null;
    if (prevIds.length && newly.length) {
      var best = newly.map(function (id) { return LEVELS.filter(function (l) { return l.id === id; })[0]; })
        .sort(function (a, b) { return a.km - b.km; }).pop();
      state.animId = best.id;
    }
    state.totalKm = res.totalKm;
    state.valid = res.valid;
    state.unlock = res.unlock;
  }

  function loadAll(revalidate) {
    return loadJSON("manifest.json", true).then(function (m) {
      applyData((m && m.tracks) || []);
      renderAll();
      renderVoyager();
    }).catch(function (e) {
      $("summary").textContent = "讀取 manifest 失敗：" + e.message;
      $("bodyGrid").innerHTML = '<div class="solar-empty">無法載入紀錄：' + e.message + "</div>";
    });
  }

  // ===== 事件 =====
  Array.prototype.forEach.call(document.querySelectorAll("[data-f]"), function (b) {
    b.addEventListener("click", function () {
      state.filter = b.dataset.f;
      Array.prototype.forEach.call(document.querySelectorAll("[data-f]"), function (x) { x.className = x === b ? "active" : ""; });
      renderBodies();
    });
  });
  $("recalc").addEventListener("click", function () {
    $("recalc").textContent = "計算中…";
    loadAll(true).then(function () { $("recalc").textContent = "🔄 重新計算進度"; });
  });

  loadAll(true);
})();
