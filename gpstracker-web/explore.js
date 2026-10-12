// 探索挑戰中心：把既有的各項挑戰整理成進度卡片。
// 進度完全由既有模組計算（SOLAR_CORE.validTracks 去重/異常過濾 + PROGRESS_CORE.buildGoals，與「全挑戰進度」頁相同），
// 這裡只負責呈現，不重新定義里程、門檻或完成條件。
(function () {
  "use strict";

  var C = GTCore, esc = C.escapeHtml;

  var PLANS = [
    { challenge: "太陽系征服計畫", icon: "🚀", href: "solar.html", desc: "用累積里程一路走向月球與各大行星" },
    { challenge: "地球征服計畫", icon: "🌍", href: "earth.html", desc: "環遊地球、虛擬遠征與垂直／軌道任務" },
    { challenge: "台灣征服計畫", icon: "🇹🇼", href: "taiwan.html", desc: "台灣里程、環島與垂直任務" }
  ];

  function $(id) { return document.getElementById(id); }
  function fmtKm(v) { return (Math.round(v * 100) / 100).toLocaleString("zh-Hant", { maximumFractionDigits: 2 }); }
  function fmtPct(p) {
    if (p >= 100) return "100%";
    if (p >= 1) return p.toFixed(1) + "%";
    if (p >= 0.01) return p.toFixed(2) + "%";
    return p > 0 ? "<0.01%" : "0%";
  }
  function bar(p) { return '<div class="gt-bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="' + Math.min(100, p).toFixed(1) + '"><i style="width:' + Math.min(100, p).toFixed(2) + '%"></i></div>'; }

  function planCard(plan, goals) {
    var done = goals.filter(function (g) { return g.done; }).length;
    var next = goals.filter(function (g) { return !g.done; })[0];
    var overall = goals.length ? done / goals.length * 100 : 0;
    var body = goals.length
      ? '<div class="gt-ch-line"><span>已達成 <b>' + done + " / " + goals.length + "</b></span><span>" + fmtPct(overall) + "</span></div>" + bar(overall) +
        (next
          ? '<div class="gt-ch-line"><span>下一個：<b>' + esc(next.name) + "</b></span><span>還差 " + fmtKm(next.remaining) + " km</span></div>" + bar(next.pct)
          : '<div class="gt-ch-line"><span><b>全部目標已達成 🎉</b></span></div>')
      : '<div class="gt-ch-line"><span>尚無目標資料</span></div>';
    return '<a class="gt-ch-card" href="' + plan.href + '"><div class="gt-ch-head"><div class="gt-ch-icon" aria-hidden="true">' + plan.icon +
      "</div><div><b>" + esc(plan.challenge) + "</b><span>" + esc(plan.desc) + "</span></div></div>" + body + '<div class="gt-ch-more">查看詳情 →</div></a>';
  }

  function voyagerCard(goals) {
    var rows = goals.map(function (g) {
      return '<div class="gt-ch-line"><span>' + esc(g.name) + "</span><b>" + fmtPct(g.pct) + "</b></div>" + bar(g.pct);
    }).join("");
    return '<a class="gt-ch-card" href="progress.html"><div class="gt-ch-head"><div class="gt-ch-icon" aria-hidden="true">🛰️</div><div><b>NASA 航海家追逐賽</b>' +
      "<span>參考追逐：以探測器距太陽的距離為目標</span></div></div>" + rows + '<div class="gt-ch-more">查看進度表 →</div></a>';
  }

  function staticCard(icon, name, desc, href, extra) {
    return '<a class="gt-ch-card" href="' + href + '"><div class="gt-ch-head"><div class="gt-ch-icon" aria-hidden="true">' + icon + "</div><div><b>" + esc(name) +
      "</b><span>" + esc(desc) + "</span></div></div>" + (extra || "") + '<div class="gt-ch-more">進入 →</div></a>';
  }

  function stat(v, unit, label) {
    return '<div class="gt-stat"><div class="v">' + esc(v) + (unit ? "<small>" + esc(unit) + "</small>" : "") + '</div><div class="l">' + esc(label) + "</div></div>";
  }

  function render(m, voyager, daily) {
    var valid = SOLAR_CORE.validTracks(m.tracks || []), totalKm = 0;
    valid.forEach(function (t) { totalKm += (t.distanceMeters || 0) / 1000; });
    var goals = PROGRESS_CORE.buildGoals({ totalKm: totalKm, solar: window.SOLAR_LEVELS, earth: window.EARTH_LEVELS, taiwan: window.TAIWAN_LEVELS, voyager: voyager });
    var grouped = PROGRESS_CORE.groupByChallenge(goals);
    var doneGoals = goals.filter(function (g) { return g.done; }); // 與 progress.js 同口徑：分母含全部目標

    $("summary").textContent = valid.length + " 筆有效活動 · " + fmtKm(totalKm) + " km";
    $("overview").innerHTML = stat(fmtKm(totalKm), "km", "有效累計里程") + stat(String(valid.length), "筆", "有效活動") +
      stat(doneGoals.length + " / " + goals.length, "", "已達成目標");
    $("overviewNote").textContent = "所有挑戰共用同一份有效里程（相同活動只算一次、排除 GPS 異常），不會重複加總。" +
      (valid.length === 0 ? " 目前還沒有有效活動，進度皆為 0。" : "");

    $("plans").innerHTML = PLANS.map(function (p) { return planCard(p, grouped.map[p.challenge] || []); }).join("");

    // 已達成：依門檻由大到小（最難的在前）；沒有達成的就不顯示這一區，不拿假資料填。
    if (doneGoals.length) {
      $("done").innerHTML = doneGoals.slice().sort(function (a, b) { return b.target - a.target; }).slice(0, 12).map(function (g) {
        return '<div class="gt-badge"><span class="ic" aria-hidden="true">✅</span><div><b>' + esc(g.name) + "</b><span>" + esc(g.challenge) + "・" + fmtKm(g.target) + " km</span></div></div>";
      }).join("");
      $("doneSec").hidden = false;
    } else {
      $("doneSec").hidden = true;
    }

    var more = "";
    more += staticCard("🏆", "競賽中心", "徽章、個人最佳、期間挑戰、步數統計、里程王、虛擬環遊、攝影挑戰、距離賓果、軌跡藝術", "challenges.html");
    var voy = grouped.map["NASA 航海家追逐賽"];
    if (voy && voy.length) more += voyagerCard(voy);
    var stepsExtra = daily ? '<div class="gt-ch-line"><span>每日步數累計</span><b>' + C.fmtInt(daily.total) + " 步</b></div><div class=\"gt-ch-line\"><span>統計天數</span><b>" + daily.days + " 天</b></div>" : "";
    more += staticCard("👟", "步數環遊里程", "把每日步數換算成距離，看看能走到哪裡（獨立計算，與上面的 GPS 里程分開）", "steps-journey.html", stepsExtra);
    $("more").innerHTML = more;
  }

  function loadOptional(name) { return GT.fetchJson(name, true).then(function (j) { return j; }, function () { return null; }); }

  function load() {
    Promise.all([GT.loadManifest(), loadOptional("voyager.json"), loadOptional("steps.json")]).then(function (r) {
      var s = r[2], days = (s && s.days) || [], total = 0;
      days.forEach(function (d) { total += d.total || 0; });
      render(r[0], r[1], days.length && total > 0 ? { total: total, days: days.length } : null);
    }, function (e) {
      $("summary").textContent = "讀取失敗";
      $("overview").innerHTML = '<div class="gt-state err" style="grid-column:1/-1"><h3>無法讀取活動資料</h3><p>' + esc(e.message) + '</p><button class="btn gt-btn" id="retry" type="button">重新載入</button></div>';
      $("retry").onclick = load;
    });
  }
  load();
})();
