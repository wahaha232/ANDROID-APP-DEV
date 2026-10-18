// 探索挑戰中心：五大挑戰（太陽系、地球、台灣、NASA Voyager、共享成就中心）同層級並列的進度卡片。
// 進度完全由既有模組計算（SOLAR_CORE.validTracks 去重/異常過濾 + PROGRESS_CORE.buildGoals，與首頁、「全挑戰進度」頁相同），
// 卡片與計算集中在 gt-challenges.js（GTCH）；這裡只負責呈現，不重新定義里程、門檻或完成條件。
(function () {
  "use strict";

  var C = GTCore, esc = C.escapeHtml, G = GTCH;

  function $(id) { return document.getElementById(id); }
  function fmtKm(v) { return G.fmtKm(v); }

  function staticCard(icon, name, desc, href, extra) {
    return '<a class="gt-ch-card" href="' + href + '"><div class="gt-ch-head"><div class="gt-ch-icon" aria-hidden="true">' + icon + "</div><div><b>" + esc(name) +
      "</b><span>" + esc(desc) + "</span></div></div>" + (extra || "") + '<div class="gt-ch-more">進入 →</div></a>';
  }

  function stat(v, unit, label, sub) {
    return '<div class="gt-stat"><div class="v">' + esc(v) + (unit ? "<small>" + esc(unit) + "</small>" : "") + '</div><div class="l">' + esc(label) + "</div>" +
      (sub ? '<div class="s">' + esc(sub) + "</div>" : "") + "</div>";
  }

  function render(m, voyager, daily, device) {
    var res = G.buildPlans(m.tracks || [], voyager, { updatedAt: m.updatedAt });
    var totals = res.totals, goals = res.goals;
    var doneGoals = goals.filter(function (g) { return g.done; }); // 與 progress.js 同口徑：分母含全部目標

    $("summary").textContent = (device ? device + "・" : "") + totals.validCount + " 筆有效活動 · " + fmtKm(totals.totalKm) + " km";
    $("overview").innerHTML = stat(fmtKm(totals.totalKm), "km", "有效累計里程") + stat(String(totals.validCount), "筆", "有效活動", totals.excluded > 0 ? "另有 " + totals.excluded + " 筆異常未計入" : "") +
      stat(doneGoals.length + " / " + goals.length, "", "已達成目標") + stat(m.updatedAt ? G.fmtDateTime(m.updatedAt) : "未知", "", "資料最後更新", m.updatedAt ? G.fmtAge(Date.now() - m.updatedAt) : "");
    $("overviewNote").textContent = "所有挑戰共用同一份有效里程（依上方選擇的裝置分開計算；相同活動只算一次、排除 GPS 異常），不會重複加總，也不必逐一完成才能開始下一個。" +
      (totals.validCount === 0 ? " 目前還沒有有效活動，進度皆為 0。" : "");

    // 五大挑戰同層級；征服計畫在前，NASA Voyager 與共享成就中心緊接其後。
    $("plans").innerHTML = res.plans.map(function (p) { return G.renderPlanCard(p, function (h) { return GT.url(h, {}); }); }).join("");

    // 已達成：依門檻由大到小（最難的在前）；沒有達成的就不顯示這一區，不拿假資料填。
    if (doneGoals.length) {
      $("done").innerHTML = doneGoals.slice().sort(function (a, b) { return b.target - a.target; }).slice(0, 12).map(function (g) {
        return '<div class="gt-badge"><span class="ic" aria-hidden="true">✅</span><div><b>' + esc(g.name) + "</b><span>" + esc(g.challenge) + "・" + fmtKm(g.target) + " km</span></div></div>";
      }).join("");
      $("doneSec").hidden = false;
    } else {
      $("doneSec").hidden = true;
    }

    var stepsExtra = daily ? '<div class="gt-ch-line"><span>每日步數累計</span><b>' + C.fmtInt(daily.total) + " 步</b></div><div class=\"gt-ch-line\"><span>統計天數</span><b>" + daily.days + " 天</b></div>" : "";
    $("more").innerHTML = staticCard("👟", "步數環遊里程", "把每日步數換算成距離，看看能走到哪裡（獨立計算，與上面的 GPS 里程分開）", "steps-journey.html", stepsExtra);
  }

  function loadOptional(name) { return GT.fetchJson(name, true).then(function (j) { return j; }, function () { return null; }); }

  function load() {
    var voy = G.loadVoyager(function (n) { return GT.fetchJson(n, true); }); // 失敗時保留最後一次有效值，不會歸零
    Promise.all([GT.loadManifest(), voy, loadOptional("steps.json")]).then(function (r) {
      var m = r[0];
      var device = C.initDeviceBar("deviceBar", m.tracks || [], null, m.devices || []);
      var view = { tracks: C.filterByDevice(m.tracks || [], device), updatedAt: m.updatedAt };
      var s = r[2], days = (s && s.days) || [], total = 0;
      days.forEach(function (d) { total += d.total || 0; });
      render(view, r[1], days.length && total > 0 ? { total: total, days: days.length } : null, device);
    }, function (e) {
      $("summary").textContent = "讀取失敗";
      $("overview").innerHTML = '<div class="gt-state err" style="grid-column:1/-1"><h3>無法讀取活動資料</h3><p>' + esc(e.message) + '</p><button class="btn gt-btn" id="retry" type="button">重新載入</button></div>';
      $("retry").onclick = load;
    });
  }
  load();
})();
