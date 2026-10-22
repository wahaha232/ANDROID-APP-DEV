// GPS TRACKER 趣味競賽中心：全部統計皆由實際上傳的紀錄（manifest + 各筆軌跡）計算，無假資料。
(function () {
  "use strict";

  var CONFIG = GPST.resolveConfig();
  var DATA_BASE = CONFIG.dataBase;
  var FEATURES = window.GT_FEATURES || {};
  function featureOn(name) { return FEATURES[name] !== false; }

  // ===== 門檻集中設定（方便未來調整） =====
  var T = {
    dist: { firstActivity: 1, fiveKm: 5, tenKm: 10, fiftyKm: 50, hundredKm: 100, thousandTotal: 1000 },
    act: { ten: 10, fifty: 50, weekDays: 3, monthDays: 10 },
    photo: { first: 1, singleTen: 10, totalHundred: 100 },
    explore: { crossRegionDeg: 0.5 }, // ≈55km 的經/緯跨度視為「跨區域」
    speedTrustMaxKmh: 400,            // 超過此速度視為 GPS 異常，不列入「最高速度」紀錄
    period: {
      weekDistanceKm: 20, monthDistanceKm: 80, yearDistanceKm: 800,
      weekActivities: 3, monthActivities: 12,
      weekDays: 3, monthDays: 12,
      steps: 50000, photos: 20, cycleKm: 30
    },
    steps: { dailyGoal: 8000 }          // 每日步數目標（步數統計分頁的達標線）
  };

  var MILESTONES = [
    { name: "🛰️ ISS 軌道高度", km: 400 },
    { name: "🏃 馬拉松", km: 42.195 },
    { name: "🌍 繞地球一圈（赤道）", km: 40075 },
    { name: "💡 光走 1 秒", km: 299792 },
    { name: "🌕 走到月球", km: 384400 },
    { name: "☀️ 走到太陽", km: 149597870 }
  ];

  var BINGO = [
    { t: "完成一筆 1 km 活動", f: function (c) { return c.maxDist; }, target: 1 },
    { t: "完成一筆 3 km 活動", f: function (c) { return c.maxDist; }, target: 3 },
    { t: "完成一筆 5 km 活動", f: function (c) { return c.maxDist; }, target: 5 },
    { t: "完成一筆 10 km 活動", f: function (c) { return c.maxDist; }, target: 10 },
    { t: "累積 20 km", f: function (c) { return c.totalKm; }, target: 20 },
    { t: "累積 100 km", f: function (c) { return c.totalKm; }, target: 100 },
    { t: "完成 3 次有效活動", f: function (c) { return c.count; }, target: 3 },
    { t: "完成 10 次有效活動", f: function (c) { return c.count; }, target: 10 },
    { t: "完成一次含照片的活動", f: function (c) { return c.maxPhotosSingle; }, target: 1 },
    { t: "單次活動 5 張照片", f: function (c) { return c.maxPhotosSingle; }, target: 5 },
    { t: "累積 1 次單車活動", f: function (c) { return c.cycleCount; }, target: 1 },
    { t: "在 3 個不同日子活動", f: function (c) { return c.days; }, target: 3 }
  ].filter(function (item) { return featureOn("photos") || item.t.indexOf("照片") < 0; });

  var TABS = [
    ["overview", "🏆 總覽"], ["badges", "🎖️ 成就徽章"], ["best", "🥇 個人最佳"],
    ["period", "🎯 期間挑戰"], ["steps", "👣 步數統計"], ["king", "📊 里程王"], ["tour", "🌏 虛擬環遊"],
    ["history", "📅 歷史上的今天"], ["photos", "📸 攝影挑戰"], ["bingo", "🎲 距離賓果"],
    ["art", "🗺️ 軌跡藝術"]
  ].filter(function (item) {
    return (item[0] !== "steps" || featureOn("steps")) && (item[0] !== "photos" || featureOn("photos"));
  });

  var manifest = { tracks: [] };
  var detailCache = {};
  var artMap = null;
  var artSelected = {};

  function $(id) { return document.getElementById(id); }
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function fmtKm(v) { return v.toLocaleString("zh-Hant", { maximumFractionDigits: 2 }); }
  function fmtInt(v) { return Math.round(v).toLocaleString("zh-Hant"); }
  var fmtDate = GTCore.fmtDate, fmtDay = GTCore.fmtDay;
  function fmtDur(ms) {
    var s = Math.floor((ms || 0) / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
    return h > 0 ? (h + "h " + m + "m") : (m + "m");
  }
  // 模式表統一由 GTCore 提供（競賽中心沿用 TRAIN「火車」短名稱與自己的分組）。
  var modeEmoji = GTCore.modeEmoji, modeName = GTCore.modeNameShort, modeGroup = GTCore.modeGroup;

  // ===== 日期（本地時區） =====
  function startOfWeek(ms) { var d = new Date(ms); var w = (d.getDay() + 6) % 7; d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - w); return d.getTime(); }
  function startOfMonth(ms) { var d = new Date(ms); d.setHours(0, 0, 0, 0); d.setDate(1); return d.getTime(); }
  function startOfYear(ms) { var d = new Date(ms); d.setHours(0, 0, 0, 0); d.setMonth(0, 1); return d.getTime(); }
  function dayKey(ms) { var d = new Date(ms); return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()); }

  // ===== 資料 =====
  function fetchJson(name, revalidate) {
    return fetch(GPST.bust(DATA_BASE + name, !revalidate), revalidate ? { cache: "no-cache" } : undefined).then(function (r) {
      if (!r.ok) throw new Error(name + " " + r.status);
      return r.json();
    });
  }
  function loadDetail(trackId) {
    if (detailCache[trackId]) return Promise.resolve(detailCache[trackId]);
    return fetchJson(trackId + ".json", false).then(function (t) { detailCache[trackId] = t; return t; });
  }
  function loadDetails(ids, onProgress) {
    return new Promise(function (resolve) {
      var out = new Array(ids.length), i = 0, active = 0, done = 0;
      if (!ids.length) { resolve(out); return; }
      function kick() {
        while (active < 4 && i < ids.length) {
          (function (idx) {
            active++;
            loadDetail(ids[idx]).then(function (t) { out[idx] = t; }).catch(function () { out[idx] = null; })
              .then(function () { active--; done++; if (onProgress) onProgress(done, ids.length); if (done === ids.length) resolve(out); else kick(); });
          })(i++);
        }
      }
      kick();
    });
  }

  function tracks() { return manifest.tracks || []; }
  // 有效活動：與首頁、征服計畫、全挑戰進度同一定義（SOLAR_CORE.validTracks：去重、距離與時間皆 > 0、排除異常高速）。
  function validTracks() { return window.SOLAR_CORE.validTracks(tracks()); }
  // 顯示用數字：不封頂（超過目標就照實顯示），最多兩位小數。
  function fmtNum(v) { return String(Math.round((+v || 0) * 100) / 100); }
  function kmOf(t) { return (t.distanceMeters || 0) / 1000; }
  function inRange(t, a, b) { var s = t.startTimeMs; return s >= a && s < b; }

  function coreStats() {
    var v = validTracks();
    var totalKm = 0, totalDur = 0, totalSteps = 0, totalPhotos = 0, totalAscent = 0, maxDist = 0, maxPhotosSingle = 0, cycleCount = 0;
    var days = {};
    v.forEach(function (t) {
      var k = kmOf(t);
      totalKm += k; totalDur += (t.durationMs || 0); totalSteps += (t.stepCount || 0);
      totalPhotos += (t.photoCount || 0); totalAscent += (t.totalAscentMeters || 0);
      if (k > maxDist) maxDist = k;
      if ((t.photoCount || 0) > maxPhotosSingle) maxPhotosSingle = t.photoCount || 0;
      if (modeGroup(t.dominantMode) === "單車") cycleCount++;
      days[dayKey(t.startTimeMs)] = true;
    });
    return {
      list: v, count: v.length, totalKm: totalKm, totalDur: totalDur, totalSteps: totalSteps,
      totalPhotos: totalPhotos, totalAscent: totalAscent, maxDist: maxDist,
      maxPhotosSingle: maxPhotosSingle, cycleCount: cycleCount, days: Object.keys(days).length
    };
  }

  function tracksIn(a, b) { return validTracks().filter(function (t) { return inRange(t, a, b); }); }
  function agg(list) {
    var km = 0, dur = 0, steps = 0, photos = 0, dayset = {}, modes = {};
    list.forEach(function (t) {
      km += kmOf(t); dur += (t.durationMs || 0); steps += (t.stepCount || 0); photos += (t.photoCount || 0);
      dayset[dayKey(t.startTimeMs)] = true;
      var g = modeGroup(t.dominantMode);
      modes[g] = (modes[g] || 0) + kmOf(t);
    });
    return { km: km, dur: dur, steps: steps, photos: photos, count: list.length, days: Object.keys(dayset).length, modes: modes };
  }

  function linkToTrack(trackId, label) {
    return '<a class="link" href="index.html?track=' + encodeURIComponent(trackId) + '">' + (label || "查看軌跡") + "</a>";
  }

  // ===== 徽章 =====
  function badgeList(core, explore) {
    var d = T.dist, a = T.act, p = T.photo;
    var longest = longestTrack();
    var bestWeekDays = maxDistinctDaysPerWeek(core.list);
    var bestMonthDays = maxDistinctDaysPerMonth(core.list);
    function single(target) { return { cur: core.maxDist, target: target }; }
    var badges = [
      { ic: "🚩", name: "初次出發", desc: "完成第一筆有效活動", m: { cur: core.count, target: 1 } },
      { ic: "🥉", name: "五公里新手", desc: "單次活動達 " + d.fiveKm + " km", m: single(d.fiveKm) },
      { ic: "🥈", name: "十公里挑戰者", desc: "單次活動達 " + d.tenKm + " km", m: single(d.tenKm) },
      { ic: "🥇", name: "長途旅行者", desc: "單次活動達 " + d.fiftyKm + " km", m: single(d.fiftyKm) },
      { ic: "🏅", name: "百公里里程碑", desc: "單次活動達 " + d.hundredKm + " km", m: single(d.hundredKm) },
      { ic: "💯", name: "千里累積", desc: "歷史累積距離達 " + fmtInt(d.thousandTotal) + " km", m: { cur: core.totalKm, target: d.thousandTotal } },
      { ic: "🧭", name: "持續探索", desc: "累積 " + a.ten + " 次有效活動", m: { cur: core.count, target: a.ten } },
      { ic: "🎯", name: "活動達人", desc: "累積 " + a.fifty + " 次有效活動", m: { cur: core.count, target: a.fifty } },
      { ic: "📅", name: "每週探索者", desc: "一週內有 " + a.weekDays + " 天完成活動", m: { cur: bestWeekDays, target: a.weekDays } },
      { ic: "🗓️", name: "月度挑戰者", desc: "一個月內有 " + a.monthDays + " 天完成活動", m: { cur: bestMonthDays, target: a.monthDays } }
    ];
    var photoBadges = featureOn("photos") ? [
      { ic: "📷", name: "第一張旅程照片", desc: "活動中拍到第一張照片", m: { cur: core.totalPhotos, target: p.first } },
      { ic: "🖼️", name: "單趟十張", desc: "單次活動拍攝 " + p.singleTen + " 張照片", m: { cur: core.maxPhotosSingle, target: p.singleTen } },
      { ic: "📸", name: "百張收藏", desc: "累積拍攝 " + p.totalHundred + " 張照片", m: { cur: core.totalPhotos, target: p.totalHundred } }
    ] : [];
    var rest = [
      { ic: "🏔️", name: "制高點", desc: "單次活動最高海拔 ≥ 500 m（需軌跡資料）", m: { cur: (explore && explore.maxAlt) || 0, target: 500 }, pending: !explore },
      { ic: "🧭", name: "跨區域旅程", desc: "單次軌跡經緯跨度 ≥ " + T.explore.crossRegionDeg + "°", m: { cur: (explore && explore.maxSpan) || 0, target: T.explore.crossRegionDeg }, pending: !explore },
      { ic: "🎨", name: "軌跡藝術家（本機瀏覽器紀錄）", desc: "在軌跡藝術頁匯出一次作品", m: { cur: artExported() ? 1 : 0, target: 1 } }
    ];
    return badges.concat(photoBadges, rest);
  }

  function longestTrack() {
    var best = null;
    validTracks().forEach(function (t) { if (!best || kmOf(t) > kmOf(best)) best = t; });
    return best;
  }
  function maxDistinctDaysPerWeek(list) {
    var w = {};
    list.forEach(function (t) { var k = startOfWeek(t.startTimeMs); (w[k] = w[k] || {})[dayKey(t.startTimeMs)] = true; });
    var m = 0; Object.keys(w).forEach(function (k) { m = Math.max(m, Object.keys(w[k]).length); });
    return m;
  }
  function maxDistinctDaysPerMonth(list) {
    var mo = {};
    list.forEach(function (t) { var k = startOfMonth(t.startTimeMs); (mo[k] = mo[k] || {})[dayKey(t.startTimeMs)] = true; });
    var m = 0; Object.keys(mo).forEach(function (k) { m = Math.max(m, Object.keys(mo[k]).length); });
    return m;
  }
  function artExported() { try { return localStorage.getItem("gpst_art_exported") === "1"; } catch (e) { return false; } }

  function computeExplore(core) {
    // 只在需要時載入完整軌跡，計算海拔與經緯跨度；載入失敗者略過。
    var ids = core.list.map(function (t) { return t.trackId; });
    return loadDetails(ids).then(function (all) {
      var maxAlt = 0, maxSpan = 0;
      all.forEach(function (t) {
        if (!t) return;
        if (typeof t.maxAltitudeMeters === "number" && t.maxAltitudeMeters > maxAlt) maxAlt = t.maxAltitudeMeters;
        var span = coordSpan(t.points);
        if (span > maxSpan) maxSpan = span;
      });
      return { maxAlt: maxAlt, maxSpan: maxSpan };
    });
  }
  function coordSpan(points) {
    if (!points || points.length < 2) return 0;
    var minLon = points[0][0], maxLon = points[0][0], minLat = points[0][1], maxLat = points[0][1];
    points.forEach(function (p) {
      if (p[0] < minLon) minLon = p[0]; if (p[0] > maxLon) maxLon = p[0];
      if (p[1] < minLat) minLat = p[1]; if (p[1] > maxLat) maxLat = p[1];
    });
    return Math.max(maxLon - minLon, maxLat - minLat);
  }

  // ===== 渲染工具 =====
  function card(v, l, pick) {
    return '<div class="card"><div class="v">' + v + '</div><div class="l">' + l + '</div>' + (pick ? '<div class="pick">' + pick + '</div>' : '') + '</div>';
  }
  function pbar(ratio) { return '<div class="pbar"><i style="width:' + Math.min(100, ratio * 100).toFixed(1) + '%"></i></div>'; }

  // ===== 各分頁 =====
  function renderOverview() {
    var c = coreStats();
    var now = Date.now();
    var wk = agg(tracksIn(startOfWeek(now), now + 1));
    var mo = agg(tracksIn(startOfMonth(now), now + 1));
    var html = "";
    html += '<div class="cards">';
    html += card(fmtKm(c.totalKm) + " km", "累積總距離");
    html += card(fmtInt(c.count), "有效活動數");
    html += card(fmtDur(c.totalDur), "累積活動時間");
    if (featureOn("steps") && c.totalSteps > 0) html += card(fmtInt(c.totalSteps), "累積步數");
    if (featureOn("photos") && c.totalPhotos > 0) html += card(fmtInt(c.totalPhotos), "累積照片數");
    html += card(fmtInt(c.days), "活動天數");
    html += "</div>";

    html += '<div class="section-title">本週 / 本月</div><div class="cards">';
    html += card(fmtKm(wk.km) + " km", "本週距離");
    html += card(fmtInt(wk.count), "本週活動");
    html += card(fmtKm(mo.km) + " km", "本月距離");
    html += card(fmtInt(mo.count), "本月活動");
    html += "</div>";

    var best = longestTrack();
    html += '<div class="section-title">個人亮點</div><div class="cards">';
    html += card(best ? (fmtKm(kmOf(best)) + " km") : "—", "最長單次距離", best ? fmtDay(best.startTimeMs) + " " + linkToTrack(best.trackId) : "");
    html += card(fmtDurationBest(c.list), "最長單次時間");
    html += "</div>";
    if (c.count === 0) html += '<div class="empty-note">尚無資料：目前沒有可計算的活動紀錄。</div>';
    $("panel-overview").innerHTML = html;
    $("summary").textContent = fmtInt(c.count) + " 筆活動 · " + fmtKm(c.totalKm) + " km · " + fmtDur(c.totalDur);
  }

  function fmtDurationBest(list) {
    var best = null;
    list.forEach(function (t) { if (!best || (t.durationMs || 0) > (best.durationMs || 0)) best = t; });
    return best ? fmtDur(best.durationMs) : "—";
  }

  function renderBadges() {
    var c = coreStats();
    var panel = $("panel-badges");
    panel.innerHTML = '<div class="section-title">成就徽章</div><div class="muted">載入軌跡以計算探索類徽章…</div>';
    computeExplore(c).then(function (explore) {
      var list = badgeList(c, explore);
      var unlocked = 0;
      var html = '<div class="section-title">成就徽章</div><div class="badge-grid">';
      list.forEach(function (b) {
        var done = b.m.cur >= b.m.target;
        if (done) unlocked++;
        var ratio = b.m.target > 0 ? b.m.cur / b.m.target : 0;
        var progText;
        if (b.name === "跨區域旅程" && !b.pending) progText = "跨度 " + b.m.cur.toFixed(3) + "° / " + b.m.target + "°";
        else if (b.name === "制高點" && !b.pending) progText = Math.round(b.m.cur) + " m / " + b.m.target + " m";
        else progText = fmtNum(b.m.cur) + " / " + b.m.target;
        html += '<div class="badge' + (done ? " unlocked" : "") + '">' +
          '<div class="ic">' + b.ic + '</div><div style="flex:1;min-width:0">' +
          '<div class="bt">' + b.name + (done ? " ✅" : "") + "</div>" +
          '<div class="bd">' + b.desc + "</div>" +
          '<div class="bp">' + (b.pending ? "需要載入軌跡" : progText) + "</div>" +
          pbar(done ? 1 : ratio) + "</div></div>";
      });
      html += "</div>";
      html = '<div class="section-title">已解鎖 ' + unlocked + " / " + list.length + "</div>" + html;
      $("panel-badges").innerHTML = html;
      $("summary").textContent = "已解鎖成就 " + unlocked + " / " + list.length + " · " + fmtInt(c.count) + " 筆活動 · " + fmtKm(c.totalKm) + " km";
    });
  }

  function renderBest() {
    var list = validTracks();
    if (!list.length) { $("panel-best").innerHTML = '<div class="empty-note">尚無資料。</div>'; return; }
    function bestBy(f) { var b = null; list.forEach(function (t) { if (!b || f(t) > f(b)) b = t; }); return b; }
    var items = [];
    items.push(["最長單次距離", bestBy(function (t) { return kmOf(t); }), function (t) { return fmtKm(kmOf(t)) + " km"; }]);
    items.push(["最長單次時間", bestBy(function (t) { return t.durationMs || 0; }), function (t) { return fmtDur(t.durationMs); }]);
    items.push(["最高可信速度", bestBy(function (t) { return trustSpeedKmh(t); }), function (t) { return trustSpeedKmh(t).toFixed(1) + " km/h"; }]);
    if (featureOn("steps") && list.some(function (t) { return (t.stepCount || 0) > 0; }))
      items.push(["最多步數活動", bestBy(function (t) { return t.stepCount || 0; }), function (t) { return fmtInt(t.stepCount) + " 步"; }]);
    if (featureOn("photos") && list.some(function (t) { return (t.photoCount || 0) > 0; }))
      items.push(["單次最多照片", bestBy(function (t) { return t.photoCount || 0; }), function (t) { return fmtInt(t.photoCount) + " 張"; }]);
    items.push(["最大累積爬升", bestBy(function (t) { return t.totalAscentMeters || 0; }), function (t) { return fmtInt(t.totalAscentMeters) + " m"; }]);
    var streak = longestStreak(list);
    var html = '<div class="section-title">個人最佳紀錄</div><ul class="rank">';
    items.forEach(function (it, i) {
      var t = it[1];
      html += '<li><div class="pos">' + (i + 1) + '</div><div class="mid"><div class="t1">' + it[0] + "　" + it[2](t) + '</div>' +
        '<div class="t2">' + fmtDate(t.startTimeMs) + " · " + modeEmoji(t.dominantMode) + " " + modeName(t.dominantMode) + "</div></div>" +
        "<div>" + linkToTrack(t.trackId) + "</div></li>";
    });
    html += "</ul>";
    html += '<div class="section-title">連續活動天數</div><div class="cards">' + card(fmtInt(streak) + " 天", "最長連續活動天數") + "</div>";
    html += '<div class="muted" style="margin-top:8px">※ 最高速度已排除 &gt; ' + T.speedTrustMaxKmh + " km/h 的異常尖峰（GPS 漂移）。</div>";
    $("panel-best").innerHTML = html;
  }
  function trustSpeedKmh(t) { var v = (t.maxSpeedMps || 0) * 3.6; return v > T.speedTrustMaxKmh ? 0 : v; }
  function longestStreak(list) {
    var keys = {}; list.forEach(function (t) { keys[dayKey(t.startTimeMs)] = true; });
    var arr = Object.keys(keys).sort();
    var best = 0, cur = 0, prev = null;
    arr.forEach(function (k) {
      var ms = new Date(k + "T00:00:00").getTime();
      if (prev !== null && Math.round((ms - prev) / 86400000) === 1) cur++; else cur = 1;
      prev = ms; if (cur > best) best = cur;
    });
    return best;
  }

  function renderPeriod() {
    var panel = $("panel-period");
    panel.innerHTML = '<div class="section-title">🎯 期間挑戰</div>' +
      '<div class="range-row">' +
      '<button class="tabbtn" data-range="week">本週</button>' +
      '<button class="tabbtn" data-range="month">本月</button>' +
      '<button class="tabbtn" data-range="year">本年</button>' +
      '<span class="muted">自訂：</span><input type="date" id="pFrom" /> – <input type="date" id="pTo" />' +
      '</div><div id="periodBody"></div>';
    Array.prototype.forEach.call(panel.querySelectorAll("[data-range]"), function (b) {
      b.addEventListener("click", function () { periodRange(b.dataset.range); });
    });
    ["pFrom", "pTo"].forEach(function (id) {
      $(id).addEventListener("change", function () { periodCustom(); });
    });
    periodRange("week");
  }
  function periodRange(kind) {
    var now = Date.now();
    var a, b = now + 1;
    if (kind === "week") a = startOfWeek(now);
    else if (kind === "month") a = startOfMonth(now);
    else a = startOfYear(now);
    var d1 = new Date(a); var d2 = new Date(now);
    $("pFrom").value = d1.getFullYear() + "-" + pad(d1.getMonth() + 1) + "-" + pad(d1.getDate());
    $("pTo").value = d2.getFullYear() + "-" + pad(d2.getMonth() + 1) + "-" + pad(d2.getDate());
    drawPeriod(a, b, kind);
  }
  function periodCustom() {
    var a = $("pFrom").value ? new Date($("pFrom").value + "T00:00:00").getTime() : startOfMonth(Date.now());
    var toStr = $("pTo").value;
    var b = toStr ? new Date(toStr + "T00:00:00").getTime() + 86400000 : Date.now() + 1;
    drawPeriod(a, b, "custom");
  }
  function drawPeriod(a, b, kind) {
    var list = tracksIn(a, b);
    var g = agg(list);
    var kmTarget = kind === "week" ? T.period.weekDistanceKm : kind === "month" ? T.period.monthDistanceKm : T.period.yearDistanceKm;
    var actTarget = kind === "month" ? T.period.monthActivities : T.period.weekActivities;
    var rows = [
      ["累積距離", g.km, kmTarget, "km"],
      ["有效活動次數", g.count, actTarget, "次"],
      ["活動天數", g.days, kind === "month" ? T.period.monthDays : T.period.weekDays, "天"],
      ["累積步數", g.steps, T.period.steps, "步"],
      ["照片收集", g.photos, T.period.photos, "張"],
      ["單車距離", g.modes["單車"] || 0, T.period.cycleKm, "km"]
    ].filter(function (r) {
      return (featureOn("steps") || r[0] !== "累積步數") && (featureOn("photos") || r[0] !== "照片收集");
    });
    var html = '<div class="muted" style="margin-bottom:8px">' + fmtDay(a) + " – " + fmtDay(b - 1) + "（共 " + g.count + " 筆）</div>";
    rows.forEach(function (r) {
      var target = r[2];
      var has = r[1] > 0;
      var ratio = target > 0 ? r[1] / target : 0;
      var remaining = Math.max(0, target - r[1]);
      var valText = r[3] === "步" || r[3] === "張" || r[3] === "次" || r[3] === "天" ? fmtInt(r[1]) : fmtKm(r[1]);
      var tgtText = r[3] === "步" || r[3] === "張" || r[3] === "次" || r[3] === "天" ? fmtInt(target) : fmtKm(target);
      var sub = has ? (remaining <= 0 ? "已達成！🎉" : "進度 " + (ratio * 100).toFixed(1) + "%，還要 " + (r[3] === "步" || r[3] === "張" || r[3] === "次" || r[3] === "天" ? fmtInt(remaining) : fmtKm(remaining)) + " " + r[3]) : "尚無資料";
      if (target <= 0) sub = "未設定目標";
      html += '<div class="progress-card"><div class="pc-head"><span>' + r[0] + "</span><span>" + valText + " / " + tgtText + " " + r[3] + "</span></div>" +
        pbar(ratio) + '<div class="pc-sub">' + sub + "</div></div>";
    });
    $("periodBody").innerHTML = html;
  }

  // ===== 步數統計 =====
  // 步數以「每趟活動」為單位歸入活動開始的那一天（網站只保存每趟的總步數，沒有逐日資料）。
  var STEP_SOURCE_NAME = {
    ZEPP_LIFE: "Zepp Life（Health Connect）", GOOGLE_FIT: "Google Fit（Health Connect）",
    SENSOR: "手機感測器", SENSOR_FALLBACK: "手機感測器（Health Connect 尚無資料）"
  };
  var stepMode = "day";

  function stepTracks() { return tracks().filter(function (t) { return (t.stepCount || 0) > 0; }); }

  // 依模式（日／週／月）建立最近區間的桶，並把每趟步數加進對應的桶。
  function stepBuckets(mode, list) {
    var now = Date.now(), buckets = [], index = {}, i, d, key, dim;
    if (mode === "day") {
      for (i = 29; i >= 0; i--) {
        d = new Date(now); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - i);
        buckets.push({ key: dayKey(d.getTime()), label: pad(d.getMonth() + 1) + "/" + pad(d.getDate()), sum: 0, goal: T.steps.dailyGoal });
      }
    } else if (mode === "week") {
      for (i = 11; i >= 0; i--) {
        d = new Date(startOfWeek(now)); d.setDate(d.getDate() - i * 7);
        buckets.push({ key: String(d.getTime()), label: pad(d.getMonth() + 1) + "/" + pad(d.getDate()), sum: 0, goal: T.steps.dailyGoal * 7 });
      }
    } else {
      for (i = 11; i >= 0; i--) {
        d = new Date(startOfMonth(now)); d.setMonth(d.getMonth() - i);
        dim = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
        buckets.push({ key: String(d.getTime()), label: String(d.getFullYear()).slice(2) + "/" + pad(d.getMonth() + 1), sum: 0, goal: T.steps.dailyGoal * dim });
      }
    }
    buckets.forEach(function (b, idx) { index[b.key] = idx; });
    list.forEach(function (t) {
      var k = mode === "day" ? dayKey(t.startTimeMs) : mode === "week" ? String(startOfWeek(t.startTimeMs)) : String(startOfMonth(t.startTimeMs));
      if (index[k] !== undefined) buckets[index[k]].sum += t.stepCount || 0;
    });
    return buckets;
  }

  function stepChartHtml(buckets, mode) {
    var max = 0;
    buckets.forEach(function (b) { max = Math.max(max, b.sum, b.goal); });
    max = max * 1.08 || 1;
    var every = mode === "day" ? 5 : mode === "week" ? 2 : 1;
    var unit = mode === "day" ? "每日" : mode === "week" ? "每週" : "每月";
    var html = '<div class="step-chart">';
    buckets.forEach(function (b, i) {
      var h = b.sum > 0 ? Math.max(1.5, b.sum / max * 100) : 0;
      var showLbl = i % every === 0 || i === buckets.length - 1;
      html += '<div class="step-col" title="' + b.label + "：" + fmtInt(b.sum) + " 步（目標 " + fmtInt(b.goal) + "）\">" +
        '<div class="step-bar-wrap"><i class="step-bar' + (b.sum >= b.goal ? " hit" : "") + '" style="height:' + h.toFixed(1) + '%"></i>' +
        '<s style="bottom:' + (b.goal / max * 100).toFixed(1) + '%"></s></div>' +
        '<span class="step-lbl">' + (showLbl ? b.label : "") + "</span></div>";
    });
    html += "</div>";
    html += '<div class="muted" style="margin-top:6px">虛線為' + unit + "目標 " + fmtInt(T.steps.dailyGoal) + " 步" +
      (mode === "day" ? "" : " × " + (mode === "week" ? "7 天" : "當月天數")) + "；綠色長條代表達標。</div>";
    return html;
  }

  function drawStepChart() {
    var box = $("stepChartBox");
    if (!box) return;
    Array.prototype.forEach.call(document.querySelectorAll("#panel-steps [data-sm]"), function (b) {
      b.className = b.getAttribute("data-sm") === stepMode ? "on" : "";
    });
    box.innerHTML = stepChartHtml(stepBuckets(stepMode, stepTracks()), stepMode);
  }

  // ===== 逐日步數（data/steps.json，由 App 首頁「同步到網站」或定期背景上傳）=====
  // undefined = 尚未載入；null = 沒有（網站只能顯示每趟活動的步數）。
  var dailySteps;
  var stepSeries = null; // null = 預設（有「手機本身」就用它，否則用合計）

  function shortPkg(pkg) {
    if (/fitbit/i.test(pkg)) return "Fitbit";
    var parts = pkg.split(".");
    return parts[parts.length - 1] || pkg;
  }
  function daySteps(day, k) {
    if (k === "total") return day.total || 0;
    var src = day.sources || {};
    if (k.indexOf("o:") === 0) return (src.others && src.others[k.slice(2)]) || 0;
    return src[k] || 0;
  }
  function seriesDefs(daily) {
    var defs = [{ k: "total", name: "合計（去除重複）" }];
    var has = {}, others = {};
    daily.days.forEach(function (d) {
      var src = d.sources || {};
      ["phone", "googleFit", "zepp"].forEach(function (k) { if (src[k]) has[k] = true; });
      Object.keys(src.others || {}).forEach(function (k) { others[k] = true; });
    });
    if (has.phone) defs.push({ k: "phone", name: "手機本身" });
    if (has.googleFit) defs.push({ k: "googleFit", name: "Google Fit（小米 Zepp Life）" });
    if (has.zepp) defs.push({ k: "zepp", name: "Zepp Life／小米" });
    Object.keys(others).forEach(function (k) { defs.push({ k: "o:" + k, name: shortPkg(k) }); });
    return defs;
  }
  function parseYmd(str) { var a = str.split("-"); return new Date(+a[0], +a[1] - 1, +a[2]).getTime(); }

  // 依模式（日／週／月）把逐日資料彙總成桶，格式與 stepBuckets 相同，可共用 stepChartHtml。
  function dailyBuckets(mode, k, daily) {
    var now = Date.now(), buckets = [], index = {}, i, d, dim;
    if (mode === "day") {
      for (i = 29; i >= 0; i--) {
        d = new Date(now); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - i);
        buckets.push({ key: dayKey(d.getTime()), label: pad(d.getMonth() + 1) + "/" + pad(d.getDate()), sum: 0, goal: T.steps.dailyGoal });
      }
    } else if (mode === "week") {
      for (i = 11; i >= 0; i--) {
        d = new Date(startOfWeek(now)); d.setDate(d.getDate() - i * 7);
        buckets.push({ key: String(d.getTime()), label: pad(d.getMonth() + 1) + "/" + pad(d.getDate()), sum: 0, goal: T.steps.dailyGoal * 7 });
      }
    } else {
      for (i = 11; i >= 0; i--) {
        d = new Date(startOfMonth(now)); d.setMonth(d.getMonth() - i);
        dim = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
        buckets.push({ key: String(d.getTime()), label: String(d.getFullYear()).slice(2) + "/" + pad(d.getMonth() + 1), sum: 0, goal: T.steps.dailyGoal * dim });
      }
    }
    buckets.forEach(function (b, idx) { index[b.key] = idx; });
    daily.days.forEach(function (day) {
      var ms = parseYmd(day.date);
      var key = mode === "day" ? dayKey(ms) : mode === "week" ? String(startOfWeek(ms)) : String(startOfMonth(ms));
      if (index[key] !== undefined) buckets[index[key]].sum += daySteps(day, k);
    });
    return buckets;
  }

  function renderStepsDaily(daily) {
    var panel = $("panel-steps");
    var defs = seriesDefs(daily);
    var keys = defs.map(function (x) { return x.k; });
    if (!stepSeries || keys.indexOf(stepSeries) < 0) stepSeries = keys.indexOf("googleFit") >= 0 ? "googleFit" : keys.indexOf("phone") >= 0 ? "phone" : "total";
    var k = stepSeries;
    var now = Date.now();
    var days = daily.days.slice().sort(function (a, b) { return a.date < b.date ? -1 : 1; });
    var vals = days.map(function (d) { return daySteps(d, k); });
    var withData = days.filter(function (d, i) { return vals[i] > 0; });
    var total = vals.reduce(function (a, b) { return a + b; }, 0);
    var best = 0, bestDate = "", hit = 0, wk = 0, mo = 0, today = 0;
    var todayKey = dayKey(now);
    days.forEach(function (d, i) {
      var v = vals[i], ms = parseYmd(d.date);
      if (v > best) { best = v; bestDate = d.date; }
      if (v >= T.steps.dailyGoal) hit++;
      if (ms >= startOfWeek(now)) wk += v;
      if (ms >= startOfMonth(now)) mo += v;
      if (dayKey(ms) === todayKey) today = v;
    });

    var html = '<div class="section-title">👣 逐日步數（Health Connect）</div>';
    html += '<div class="range-row" id="stepSeriesRow">';
    defs.forEach(function (x) { html += '<button data-series="' + x.k + '">' + x.name + "</button>"; });
    html += "</div>";
    html += '<div class="cards">';
    html += card(fmtInt(today), "今日步數");
    html += card(fmtInt(wk), "本週步數");
    html += card(fmtInt(mo), "本月步數");
    html += card(withData.length ? fmtInt(total / withData.length) : "0", "平均每日步數", "共 " + fmtInt(withData.length) + " 天有資料");
    html += card(fmtInt(best), "最高單日", bestDate.replace(/-/g, "/"));
    html += card(fmtInt(hit) + " / " + fmtInt(withData.length), "達標天數", "目標 " + fmtInt(T.steps.dailyGoal) + " 步／日");
    html += "</div>";

    html += '<div class="section-title">📈 步數趨勢</div>';
    html += '<div class="range-row"><button data-sm="day">近 30 天</button><button data-sm="week">近 12 週</button><button data-sm="month">近 12 個月</button></div>';
    html += '<div id="stepChartBox"></div>';

    // 最近 14 天：各來源並排，一眼看出哪個來源偏高（同一天不同來源數字差很多通常是重複記錄）
    html += '<div class="section-title">🔎 最近 14 天各來源</div><ul class="rank">';
    days.slice(-14).reverse().forEach(function (d) {
      var src = d.sources || {}, parts = [];
      if (src.phone) parts.push("手機 " + fmtInt(src.phone));
      if (src.googleFit) parts.push("Google Fit " + fmtInt(src.googleFit));
      if (src.zepp) parts.push("Zepp " + fmtInt(src.zepp));
      Object.keys(src.others || {}).forEach(function (o) { parts.push(shortPkg(o) + " " + fmtInt(src.others[o])); });
      html += '<li><div class="mid"><div class="t1">' + d.date.replace(/-/g, "/") + '</div><div class="t2">' + (parts.join(" · ") || "沒有資料") + '</div></div><div class="val">' + fmtInt(d.total || 0) + "</div></li>";
    });
    html += "</ul>";
    html += '<div class="muted" style="margin-top:10px">右側數字為「合計（去除重複）」：Health Connect 依優先順序，同一段時間被多個來源記錄時只算一次。' +
      "小米 Zepp Life 的步數會先同步到 Google Fit 再進入 Health Connect。資料更新時間：" + fmtDate(daily.updatedAt || 0) + "（由 App 同步）。</div>";
    panel.innerHTML = html;

    Array.prototype.forEach.call(panel.querySelectorAll("[data-series]"), function (b) {
      b.className = b.getAttribute("data-series") === k ? "on" : "";
      b.addEventListener("click", function () { stepSeries = b.getAttribute("data-series"); renderStepsDaily(daily); });
    });
    Array.prototype.forEach.call(panel.querySelectorAll("[data-sm]"), function (b) {
      b.addEventListener("click", function () { stepMode = b.getAttribute("data-sm"); drawDailyChart(daily); });
    });
    drawDailyChart(daily);
  }

  function drawDailyChart(daily) {
    var box = $("stepChartBox");
    if (!box) return;
    Array.prototype.forEach.call(document.querySelectorAll("#panel-steps [data-sm]"), function (b) {
      b.className = b.getAttribute("data-sm") === stepMode ? "on" : "";
    });
    box.innerHTML = stepChartHtml(dailyBuckets(stepMode, stepSeries, daily), stepMode);
  }

  // 分頁進入點：先載入 steps.json；有逐日資料就顯示逐日，沒有就退回「每趟活動」的步數。
  function renderSteps() {
    if (!featureOn("steps")) return;
    var panel = $("panel-steps");
    if (dailySteps === undefined) {
      panel.innerHTML = '<div class="empty-note">載入步數資料中…</div>';
      fetchJson("steps.json", true)
        .then(function (d) { dailySteps = (d && d.days && d.days.length) ? d : null; })
        .catch(function () { dailySteps = null; })
        .then(function () { if (panel.className.indexOf("active") >= 0) renderSteps(); });
      return;
    }
    if (dailySteps) renderStepsDaily(dailySteps);
    else renderStepsByTrack();
  }

  function renderStepsByTrack() {
    var list = stepTracks();
    var panel = $("panel-steps");
    if (!list.length) {
      panel.innerHTML = '<div class="empty-note">尚無步數資料。請在 App 首頁按「同步到網站」上傳每天的步數；或在「設定 → 步數來源」選好來源，之後上傳的每趟紀錄也會帶有步數。</div>';
      return;
    }
    var now = Date.now();
    var total = 0, dayMap = {}, bestTrack = list[0], sources = {};
    list.forEach(function (t) {
      total += t.stepCount;
      var k = dayKey(t.startTimeMs);
      dayMap[k] = (dayMap[k] || 0) + t.stepCount;
      if (t.stepCount > bestTrack.stepCount) bestTrack = t;
      var s = t.stepSource || "UNKNOWN";
      sources[s] = (sources[s] || 0) + 1;
    });
    var days = Object.keys(dayMap);
    var bestDay = days[0], hitDays = 0;
    days.forEach(function (k) {
      if (dayMap[k] > dayMap[bestDay]) bestDay = k;
      if (dayMap[k] >= T.steps.dailyGoal) hitDays++;
    });
    var wk = 0, mo = 0;
    list.forEach(function (t) {
      if (t.startTimeMs >= startOfWeek(now)) wk += t.stepCount;
      if (t.startTimeMs >= startOfMonth(now)) mo += t.stepCount;
    });

    var html = '<div class="section-title">👣 步數總覽</div><div class="cards">';
    html += card(fmtInt(total), "累積步數", "共 " + fmtInt(list.length) + " 筆活動有步數");
    html += card(fmtInt(total / days.length), "平均每個活動日步數", "共 " + fmtInt(days.length) + " 個活動日");
    html += card(fmtInt(dayMap[bestDay]), "最高單日步數", bestDay.replace(/-/g, "/"));
    html += card(fmtInt(bestTrack.stepCount), "單趟最高步數", fmtDay(bestTrack.startTimeMs));
    html += card(fmtInt(wk), "本週步數");
    html += card(fmtInt(mo), "本月步數");
    html += card(fmtInt(hitDays) + " / " + fmtInt(days.length), "達標活動日", "目標 " + fmtInt(T.steps.dailyGoal) + " 步／日");
    html += "</div>";

    html += '<div class="section-title">📈 步數趨勢</div>';
    html += '<div class="range-row"><button data-sm="day">近 30 天</button><button data-sm="week">近 12 週</button><button data-sm="month">近 12 個月</button></div>';
    html += '<div id="stepChartBox"></div>';

    var srcKeys = Object.keys(sources);
    html += '<div class="section-title">🔎 步數來源</div><div class="cards">';
    srcKeys.forEach(function (s) { html += card(fmtInt(sources[s]) + " 筆", STEP_SOURCE_NAME[s] || "未標示（舊紀錄）"); });
    html += "</div>";
    html += '<div class="muted" style="margin-top:10px">目前只有「每趟活動」的步數（歸入活動開始的那一天）。要看每天的全天步數與各來源，請在 App 首頁按「同步到網站」。' +
      "「手機感測器（Health Connect 尚無資料）」代表錄製結束時 Zepp Life 還沒同步，之後補同步成功會自動更新該筆。</div>";
    panel.innerHTML = html;
    Array.prototype.forEach.call(panel.querySelectorAll("[data-sm]"), function (b) {
      b.addEventListener("click", function () { stepMode = b.getAttribute("data-sm"); drawStepChart(); });
    });
    drawStepChart();
  }

  function renderKing() {
    var now = Date.now();
    var all = validTracks();
    var wk = agg(tracksIn(startOfWeek(now), now + 1));
    var mo = agg(tracksIn(startOfMonth(now), now + 1));
    var yr = agg(tracksIn(startOfYear(now), now + 1));
    var tot = agg(all);
    var html = '<div class="section-title">📊 累積里程王</div><div class="cards">';
    html += card(fmtKm(wk.km) + " km", "本週");
    html += card(fmtKm(mo.km) + " km", "本月");
    html += card(fmtKm(yr.km) + " km", "本年");
    html += card(fmtKm(tot.km) + " km", "全部歷史");
    html += "</div>";

    html += '<div class="section-title">依移動模式分組（全部歷史）</div><div class="mode-bars">';
    var modes = tot.modes, maxMode = 0;
    Object.keys(modes).forEach(function (m) { if (modes[m] > maxMode) maxMode = modes[m]; });
    var order = Object.keys(modes).sort(function (x, y) { return modes[y] - modes[x]; });
    if (!order.length) html += '<div class="empty-note">尚無資料。</div>';
    order.forEach(function (m) {
      html += '<div class="mode-bar"><span>' + m + '</span><div class="mb-track"><div class="mb-fill" style="width:' + (maxMode ? (modes[m] / maxMode * 100).toFixed(1) : 0) + '%"></div></div><span>' + fmtKm(modes[m]) + " km</span></div>";
    });
    html += "</div>";

    html += '<div class="section-title">單次活動距離排名（前 10）</div><ul class="rank">';
    all.slice().sort(function (a, b) { return kmOf(b) - kmOf(a); }).slice(0, 10).forEach(function (t, i) {
      html += '<li><div class="pos">' + (i + 1) + '</div><div class="mid"><div class="t1">' + fmtKm(kmOf(t)) + ' km</div>' +
        '<div class="t2">' + fmtDate(t.startTimeMs) + " · " + modeEmoji(t.dominantMode) + " " + modeName(t.dominantMode) + "</div></div>" +
        "<div>" + linkToTrack(t.trackId) + "</div></li>";
    });
    html += "</ul>";
    $("panel-king").innerHTML = html;
  }

  function renderTour() {
    var c = coreStats();
    var html = '<div class="section-title">🌏 虛擬環遊</div>' +
      '<div class="muted" style="margin-bottom:8px">用你的總里程虛擬走到這些目標（真實距離）。</div>';
    MILESTONES.forEach(function (m) {
      var pct = c.totalKm / m.km * 100;   // 百分比可超過 100%；只有進度條的填滿寬度封頂（pbar 內）
      var remaining = Math.max(0, m.km - c.totalKm);
      var done = c.totalKm >= m.km;
      html += '<div class="progress-card"><div class="pc-head"><span>' + m.name + '</span><span>' +
        (done ? "已達成 🎉（超出 +" + fmtKm(c.totalKm - m.km) + " km）" : "還差 " + fmtKm(remaining) + " km") + "</span></div>" + pbar(pct / 100) +
        '<div class="pc-sub">目標 ' + fmtKm(m.km) + " km · 進度 " + GTCH.fmtPct(pct) + "</div></div>";
    });
    $("panel-tour").innerHTML = html;
  }

  function renderHistory() {
    var all = validTracks();
    var now = new Date();
    var m = now.getMonth(), day = now.getDate();
    var matches = all.filter(function (t) { var d = new Date(t.startTimeMs); return d.getMonth() === m && d.getDate() === day; });
    var html = '<div class="section-title">📅 歷史上的今天（' + (m + 1) + "/" + day + "）</div>";
    if (!matches.length) {
      html += '<div class="empty-note">過去的今天還沒有活動紀錄。</div>';
      $("panel-history").innerHTML = html; return;
    }
    matches.sort(function (a, b) { return b.startTimeMs - a.startTimeMs; });
    html += '<ul class="rank">';
    matches.forEach(function (t) {
      html += '<li><div class="pos">' + new Date(t.startTimeMs).getFullYear() + '</div><div class="mid"><div class="t1">' +
        fmtKm(kmOf(t)) + " km · " + fmtDur(t.durationMs) + '</div><div class="t2">' + fmtDate(t.startTimeMs) + " · " +
        modeEmoji(t.dominantMode) + " " + modeName(t.dominantMode) + "</div></div><div>" + linkToTrack(t.trackId) + "</div></li>";
    });
    html += "</ul>";
    $("panel-history").innerHTML = html;
  }

  function renderPhotos() {
    if (!featureOn("photos")) return;
    var all = validTracks();
    var total = all.reduce(function (a, t) { return a + (t.photoCount || 0); }, 0);
    var ranking = all.filter(function (t) { return (t.photoCount || 0) > 0; }).sort(function (a, b) { return (b.photoCount || 0) - (a.photoCount || 0); });
    var html = '<div class="section-title">📸 攝影挑戰</div><div class="cards">' +
      card(fmtInt(total), "累積照片數") + card(fmtInt(ranking.length), "有照片的活動") + "</div>";
    html += '<div class="muted" style="margin:8px 0">※ 網站只保存「拍照座標」，並未上傳照片檔；以下為各活動的拍照點數量與地圖定位。</div>';
    if (!ranking.length) { html += '<div class="empty-note">尚無照片紀錄。</div>'; $("panel-photos").innerHTML = html; return; }
    html += '<div class="section-title">單次活動照片數排名</div><ul class="rank">';
    ranking.slice(0, 10).forEach(function (t, i) {
      html += '<li><div class="pos">' + (i + 1) + '</div><div class="mid"><div class="t1">' + fmtInt(t.photoCount) + ' 張</div>' +
        '<div class="t2">' + fmtDate(t.startTimeMs) + " · " + modeEmoji(t.dominantMode) + " " + modeName(t.dominantMode) + "</div></div>" +
        "<div>" + linkToTrack(t.trackId) + "</div></li>";
    });
    html += "</ul>";
    html += '<div class="section-title">拍照點地圖</div><button class="btn secondary" id="loadPhotoMap">在地圖顯示所有拍照點</button><div id="photoMap"></div>';
    $("panel-photos").innerHTML = html;
    $("loadPhotoMap").addEventListener("click", showPhotoMap);
  }
  function showPhotoMap() {
    var ids = validTracks().filter(function (t) { return (t.photoCount || 0) > 0; }).map(function (t) { return t.trackId; });
    $("loadPhotoMap").textContent = "載入中…";
    loadDetails(ids).then(function (all) {
      var features = [], bounds = new maplibregl.LngLatBounds();
      all.forEach(function (t) {
        if (!t || !t.photos) return;
        t.photos.forEach(function (p) {
          features.push({ type: "Feature", properties: {}, geometry: { type: "Point", coordinates: [p[0], p[1]] } });
          bounds.extend([p[0], p[1]]);
        });
      });
      // 先給容器尺寸，MapLibre 才能正確初始化（否則高度 0 會畫不出來）。
      $("photoMap").style.height = "360px";
      $("photoMap").style.borderRadius = "10px";
      $("photoMap").style.marginTop = "10px";
      var map = new maplibregl.Map({
        container: "photoMap", style: baseStyle(), center: [121, 23.5], zoom: 6, attributionControl: true
      });
      map.on("load", function () {
        map.addSource("ph", { type: "geojson", data: { type: "FeatureCollection", features: features } });
        map.addLayer({ id: "ph-dot", type: "circle", source: "ph", paint: { "circle-radius": 6, "circle-color": "#FFB300", "circle-stroke-color": "#fff", "circle-stroke-width": 1.5 } });
        if (features.length) map.fitBounds(bounds, { padding: 50, maxZoom: 15 });
      });
      $("loadPhotoMap").style.display = "none";
    });
  }

  function renderBingo() {
    var c = coreStats();
    var html = '<div class="section-title">🎲 距離賓果</div><div class="ch-bingo">';
    c.days = c.days; // distinct activity days
    BINGO.forEach(function (b) {
      var cur = b.f(c);
      var done = cur >= b.target;
      html += '<div class="bingo-cell' + (done ? " done" : "") + '"><div class="bt">' + (done ? "✅ " : "⬜ ") + b.t + "</div>" +
        '<div class="bs">' + fmtNum(cur) + " / " + b.target + (done ? "（達成）" : "") + "</div></div>";
    });
    html += "</div>";
    $("panel-bingo").innerHTML = html;
  }

  // ===== 軌跡藝術 =====
  function renderArt() {
    if (artMap) { try { artMap.remove(); } catch (e) {} artMap = null; }
    var all = validTracks().slice().sort(function (a, b) { return b.startTimeMs - a.startTimeMs; });
    var html = '<div class="section-title">🗺️ 軌跡藝術家</div><div class="muted" style="margin-bottom:8px">勾選活動疊加顯示；「匯出 PNG」會輸出無底圖的軌跡線稿。<b>原始 GPS 座標不會被修改。</b></div>';
    html += '<div class="art-wrap"><div class="art-list" id="artList"></div>' +
      '<div class="art-canvas-box"><canvas id="artCanvas" width="900" height="600"></canvas>' +
      '<div class="range-row" style="margin-top:10px"><button class="btn" id="artDraw">繪製</button>' +
      '<button class="btn secondary" id="artExport">⬇ 匯出 PNG</button>' +
      '<button class="btn secondary" id="artMapBtn">在地圖顯示</button></div></div></div>';
    html += '<div id="artMapWrap"><div id="artMap"></div></div>';
    $("panel-art").innerHTML = html;

    var listBox = $("artList");
    if (!all.length) { listBox.innerHTML = '<div class="empty-note">尚無資料。</div>'; return; }
    all.forEach(function (t) {
      var label = document.createElement("label");
      var cb = document.createElement("input");
      cb.type = "checkbox"; cb.value = t.trackId;
      cb.checked = !!artSelected[t.trackId];
      cb.addEventListener("change", function () { artSelected[t.trackId] = cb.checked; drawArt(); });
      var span = document.createElement("span");
      span.textContent = fmtDay(t.startTimeMs) + " · " + fmtKm(kmOf(t)) + " km · " + modeEmoji(t.dominantMode);
      label.appendChild(cb); label.appendChild(span);
      listBox.appendChild(label);
    });
    $("artDraw").addEventListener("click", drawArt);
    $("artExport").addEventListener("click", exportArt);
    $("artMapBtn").addEventListener("click", showArtMap);
  }

  var ART_COLORS = ["#00E676", "#2F6BFF", "#FFB300", "#FF3D00", "#B388FF", "#00E5FF", "#FF80AB", "#7CFF7C"];

  function selectedTracks() {
    return validTracks().filter(function (t) { return artSelected[t.trackId]; });
  }
  function drawArt() {
    var sel = selectedTracks();
    var canvas = $("artCanvas");
    var ctx = canvas.getContext("2d");
    ctx.fillStyle = "#071018"; ctx.fillRect(0, 0, canvas.width, canvas.height);
    if (!sel.length) { ctx.fillStyle = "#9aa7bd"; ctx.font = "16px sans-serif"; ctx.fillText("請勾選左側活動", 20, 30); return; }
    $("artDraw").textContent = "載入中…";
    loadDetails(sel.map(function (t) { return t.trackId; })).then(function (all) {
      $("artDraw").textContent = "繪製";
      var minLon = Infinity, maxLon = -Infinity, minLat = Infinity, maxLat = -Infinity;
      all.forEach(function (t) {
        if (!t || !t.points) return;
        t.points.forEach(function (p) {
          if (p[0] < minLon) minLon = p[0]; if (p[0] > maxLon) maxLon = p[0];
          if (p[1] < minLat) minLat = p[1]; if (p[1] > maxLat) maxLat = p[1];
        });
      });
      if (!isFinite(minLon)) return;
      var pad = 30, W = canvas.width, H = canvas.height;
      var spanX = Math.max(1e-6, maxLon - minLon), spanY = Math.max(1e-6, maxLat - minLat);
      var scale = Math.min((W - 2 * pad) / spanX, (H - 2 * pad) / spanY);
      function proj(p) {
        return [pad + (p[0] - minLon) * scale, H - pad - (p[1] - minLat) * scale];
      }
      ctx.lineWidth = 2; ctx.lineJoin = "round"; ctx.lineCap = "round";
      all.forEach(function (t, i) {
        if (!t || !t.points || t.points.length < 2) return;
        ctx.strokeStyle = ART_COLORS[i % ART_COLORS.length];
        ctx.beginPath();
        t.points.forEach(function (p, j) { var q = proj(p); if (j === 0) ctx.moveTo(q[0], q[1]); else ctx.lineTo(q[0], q[1]); });
        ctx.stroke();
      });
    });
  }
  function exportArt() {
    try {
      var url = $("artCanvas").toDataURL("image/png");
      var a = document.createElement("a");
      a.href = url; a.download = "gpstracker-art.png";
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      try { localStorage.setItem("gpst_art_exported", "1"); } catch (e) {}
    } catch (e) {
      alert("匯出失敗：" + e.message);
    }
  }
  function showArtMap() {
    var sel = selectedTracks();
    if (!sel.length) { alert("請先勾選活動"); return; }
    loadDetails(sel.map(function (t) { return t.trackId; })).then(function (all) {
      if (!artMap) {
        artMap = new maplibregl.Map({ container: "artMap", style: baseStyle(), center: [121, 23.5], zoom: 6, attributionControl: true });
        artMap.on("load", function () { setArtMapData(all); });
      } else {
        setArtMapData(all);
      }
    });
  }
  function setArtMapData(all) {
    var features = [], bounds = new maplibregl.LngLatBounds();
    all.forEach(function (t) {
      if (!t || !t.points || t.points.length < 2) return;
      var coords = t.points.map(function (p) { return [p[0], p[1]]; });
      coords.forEach(function (c) { bounds.extend(c); });
      features.push({ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: coords } });
    });
    if (artMap.getSource("artroutes")) artMap.getSource("artroutes").setData({ type: "FeatureCollection", features: features });
    else { artMap.addSource("artroutes", { type: "geojson", data: { type: "FeatureCollection", features: features } }); artMap.addLayer({ id: "artroutes-line", type: "line", source: "artroutes", paint: { "line-color": "#00E676", "line-width": 4 } }); }
    if (features.length) artMap.fitBounds(bounds, { padding: 50 });
  }

  function baseStyle() {
    return {
      version: 8,
      sources: {
        base: { type: "raster", tiles: ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"], tileSize: 256, attribution: "Esri, Maxar, Earthstar Geographics, GIS User Community" },
        dem: { type: "raster-dem", tiles: ["https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"], encoding: "terrarium", tileSize: 256, attribution: "Elevation: Mapzen / AWS Terrain Tiles" }
      },
      layers: [{ id: "base", type: "raster", source: "base" }]
    };
  }

  // ===== 分頁切換 =====
  var RENDERERS = {
    overview: renderOverview, badges: renderBadges, best: renderBest, period: renderPeriod,
    steps: renderSteps, king: renderKing, tour: renderTour, history: renderHistory, photos: renderPhotos,
    bingo: renderBingo, art: renderArt
  };
  function showTab(id) {
    Array.prototype.forEach.call(document.querySelectorAll(".tabs button"), function (b) { b.className = b.dataset.tab === id ? "active" : ""; });
    Array.prototype.forEach.call(document.querySelectorAll(".panel"), function (p) { p.className = "panel" + (p.id === "panel-" + id ? " active" : ""); });
    try { RENDERERS[id](); } catch (e) { $("panel-" + id).innerHTML = '<div class="empty-note">載入失敗：' + GTCore.escapeHtml(e.message) + "</div>"; }
  }

  function buildTabs() {
    var nav = $("tabs");
    TABS.forEach(function (t) {
      var b = document.createElement("button");
      b.textContent = t[1]; b.dataset.tab = t[0];
      b.addEventListener("click", function () { showTab(t[0]); });
      nav.appendChild(b);
    });
  }

  function init() {
    buildTabs();
    fetchJson("manifest.json", true).then(function (m) {
      manifest = m || { tracks: [] };
      var device = window.GTCore.initDeviceBar("deviceBar", manifest.tracks || [], null, manifest.devices || []);
      manifest.tracks = window.GTCore.filterByDevice(manifest.tracks || [], device);
      manifest.tracks = manifest.tracks.slice().sort(function (a, b) { return b.startTimeMs - a.startTimeMs; });
      $("summary").textContent = (device ? device + "・" : "") + fmtInt(manifest.tracks.length) + " 筆紀錄 · 載入競賽資料中…";
      showTab("overview");
    }).catch(function (e) {
      $("summary").textContent = "讀取 manifest 失敗：" + e.message;
      showTab("overview");
    }).then(function () {
      if (typeof maplibregl === "undefined") {
        // 地圖相關分頁仍可用，只是無法顯示地圖。
      }
    });
  }

  init();
})();
