// 首頁（活動總覽）：只讀 manifest.json（+ 選用的 meta.json、steps.json）。不載入地圖引擎，也不載入全部軌跡。
// 只有最新一筆（精選卡）與時間軸可見的幾筆會各自按需載入一個軌跡檔來畫路線縮圖。
(function () {
  "use strict";

  var C = GTCore, esc = C.escapeHtml;
  var TIMELINE_MAX = 8;

  function $(id) { return document.getElementById(id); }

  function kpi(f) {
    return '<div class="gt-kpi"><div class="v">' + esc(f.value) + (f.unit ? "<small>" + esc(f.unit) + "</small>" : "") +
      '</div><div class="l">' + esc(f.label) + "</div></div>";
  }
  function stat(v, unit, label, sub) {
    return '<div class="gt-stat"><div class="v">' + esc(v) + (unit ? "<small>" + esc(unit) + "</small>" : "") + '</div><div class="l">' + esc(label) +
      "</div>" + (sub ? '<div class="s">' + esc(sub) + "</div>" : "") + "</div>";
  }

  function renderHero(e, meta) {
    var fields = C.trackFields(e);
    var pick = fields.filter(function (f) { return ["distance", "duration", "steps", "ascent"].indexOf(f.key) >= 0; });
    var title = C.activityTitle(e, meta);
    var note = meta[e.trackId] && meta[e.trackId].notes;
    $("hero").innerHTML =
      '<article class="gt-hero">' +
        '<div class="gt-hero-body">' +
          '<div class="gt-eyebrow">最近一次活動</div>' +
          '<h3 class="gt-hero-title">' + esc(title) + "</h3>" +
          '<div class="gt-hero-meta"><span class="gt-chip">' + C.modeEmoji(C.modeOf(e, meta)) + " " + esc(C.modeName(C.modeOf(e, meta))) + "</span>" +
            "<span>" + esc(C.fmtDate(e.startTimeMs)) + "</span></div>" +
          (note ? '<div class="gt-hero-meta">' + esc(note) + "</div>" : "") +
          (pick.length ? '<div class="gt-kpis">' + pick.map(kpi).join("") + "</div>" : "") +
          '<div class="gt-cta">' +
            '<a class="btn gt-btn lg" href="' + GT.workspaceUrl(e.trackId, "replay") + '">▶ 旅程回放</a>' +
            '<a class="btn gt-btn lg secondary" href="' + GT.activityUrl(e.trackId) + '">活動故事</a>' +
            '<a class="btn gt-btn lg secondary" href="' + GT.workspaceUrl(e.trackId, "explore") + '">自由探索 3D</a>' +
          "</div>" +
        "</div>" +
        '<div class="gt-hero-route" id="heroRoute"><span class="gt-spin" aria-label="載入路線"></span></div>' +
      "</article>";
    GT.thumb(e.trackId, 480, 320).then(function (r) {
      var box = $("heroRoute");
      if (!box) return;
      if (r && r.path) box.innerHTML = GT.routeSvg(r.path, "gt-route-svg", 6) + '<div class="gt-route-cap">路線預覽・綠點起點／橘點終點</div>';
      else box.innerHTML = '<div class="gt-route-cap" style="position:static">沒有可顯示的路線資料</div>';
    });
  }

  function renderStats(tracks, daily) {
    var dist = 0, dur = 0, asc = 0, steps = 0;
    tracks.forEach(function (t) {
      dist += t.distanceMeters || 0; dur += t.durationMs || 0; asc += t.totalAscentMeters || 0; steps += t.stepCount || 0;
    });
    var html = stat(C.fmtKm(dist), "km", "累積距離") + stat(C.fmtInt(tracks.length), "筆", "活動數量");
    if (dur > 0) html += stat(C.fmtDur(dur), "", "累積時間");
    if (asc > 0) html += stat(C.fmtInt(asc), "m", "累積爬升");
    if (steps > 0) html += stat(C.fmtInt(steps), "步", "活動步數", "各筆活動記錄的步數合計");
    if (daily) html += stat(C.fmtInt(daily.total), "步", "每日步數累計", daily.days + " 天（Health Connect／Google Fit）");
    $("stats").innerHTML = html;
    $("statsSec").hidden = false;
  }

  function renderTimeline(tracks, meta) {
    var list = tracks.slice(0, TIMELINE_MAX);
    $("timeline").innerHTML = list.map(function (t) {
      var f = C.trackFields(t).filter(function (x) { return ["distance", "duration", "steps"].indexOf(x.key) >= 0; });
      return '<li><a class="gt-tl-item" href="' + GT.activityUrl(t.trackId) + '" data-id="' + esc(t.trackId) + '">' +
        '<div class="gt-tl-thumb gt-mini" aria-hidden="true"></div>' +
        "<div><div class=\"gt-tl-title\">" + C.modeEmoji(C.modeOf(t, meta)) + " " + esc(C.activityTitle(t, meta)) + "</div>" +
        '<div class="gt-tl-sub">' + esc(C.fmtDate(t.startTimeMs)) + "・" + esc(C.modeName(C.modeOf(t, meta))) + "</div>" +
        '<div class="gt-tl-stats">' + f.map(function (x) { return "<span>" + esc(x.value) + " " + esc(x.unit) + "</span>"; }).join("") + "</div></div>" +
        '<span class="gt-tl-go" aria-hidden="true">›</span></a></li>';
    }).join("");
    Array.prototype.forEach.call($("timeline").querySelectorAll(".gt-tl-item"), function (a) {
      var box = a.querySelector(".gt-tl-thumb");
      GT.lazy(a, function () {
        GT.thumb(a.dataset.id, 96, 64).then(function (r) { if (r && r.path) box.innerHTML = GT.routeSvg(r.path, "gt-mini", 3); });
      });
    });
    $("timelineSec").hidden = false;
  }

  function renderEmpty() {
    $("summary").textContent = "尚無紀錄";
    $("hero").innerHTML = '<div class="gt-state"><h3>還沒有任何活動紀錄</h3>' +
      "<p>在手機 App 錄製一趟活動並上傳到網站後，這裡就會出現你的第一則活動故事。</p>" +
      '<a class="btn gt-btn secondary" href="explore.html">先看看探索挑戰</a></div>';
  }

  function renderError(msg) {
    $("summary").textContent = "讀取失敗";
    $("hero").innerHTML = '<div class="gt-state err"><h3>無法讀取活動資料</h3><p>' + esc(msg) + "</p>" +
      '<button class="btn gt-btn" id="retry">重新載入</button></div>';
    $("retry").onclick = load;
  }

  // 每日步數（steps.json）：選用，失敗就不顯示該統計。
  function loadDaily() {
    return GT.fetchJson("steps.json", true).then(function (s) {
      var days = (s && s.days) || [], total = 0;
      days.forEach(function (d) { total += d.total || 0; });
      return days.length && total > 0 ? { total: total, days: days.length } : null;
    }, function () { return null; });
  }

  function load() {
    $("hero").innerHTML = '<div class="gt-skel" style="height:320px"></div>';
    Promise.all([GT.loadManifest(), GT.loadMeta()]).then(function (r) {
      var tracks = r[0].tracks, meta = r[1];
      if (!tracks.length) { renderEmpty(); return; }
      var dist = 0;
      tracks.forEach(function (t) { dist += t.distanceMeters || 0; });
      $("summary").textContent = tracks.length + " 筆活動 · 累積 " + C.fmtKm(dist) + " km";
      renderHero(tracks[0], meta);
      renderStats(tracks, null);
      renderTimeline(tracks, meta);
      loadDaily().then(function (d) { if (d) renderStats(tracks, d); });
    }, function (e) { renderError(e.message); });
  }

  load();
})();
