// 活動回顧頁：選年份（與月份）就看累積里程、次數、活動天數、與上一期比較、月／日長條圖、移動方式占比與亮點。
// 只讀 manifest.json（+ meta.json 的移動方式修改）；里程用 SOLAR_CORE.validTracks（全站同一個「有效活動」定義）。
(function () {
  "use strict";

  var C = GTCore, R = GTRecap, esc = C.escapeHtml;
  var FEATURES = window.GT_FEATURES || {};
  function featureOn(name) { return FEATURES[name] !== false; }
  var state = { tracks: [], meta: {}, year: null, month: 0, excluded: 0 };

  function $(id) { return document.getElementById(id); }
  function fmtKm(v) { return (Math.round(v * 10) / 10).toLocaleString("zh-Hant", { maximumFractionDigits: 1 }); }
  function stat(v, unit, label, sub) {
    return '<div class="gt-stat"><div class="v">' + esc(v) + (unit ? "<small>" + esc(unit) + "</small>" : "") + '</div><div class="l">' + esc(label) + "</div>" + (sub ? '<div class="s">' + sub + "</div>" : "") + "</div>";
  }
  // 與上一期比較的小標籤：上一期為 0 就只說「上一期沒有活動」，不顯示百分比。
  function deltaText(cur, prev, unit, prevLabel) {
    var d = R.delta(cur, prev);
    if (prev <= 0) return esc(prevLabel + "沒有活動");
    var sign = d.diff > 0 ? "+" : d.diff < 0 ? "−" : "";
    return esc("較" + prevLabel + " " + sign + fmtKm(Math.abs(d.diff)) + unit + "（" + sign + Math.abs(Math.round(d.pct)) + "%）");
  }

  function render() {
    var res = R.compute(state.tracks, { year: state.year, month: state.month, modeOf: function (t) { return C.modeOf(t, state.meta); } });
    var t = res.totals, pt = res.previous.totals, pp = res.previous.period;
    var label = state.year + " 年" + (state.month ? " " + state.month + " 月" : "");
    var prevLabel = state.month ? (pp.month + " 月") : (pp.year + " 年");
    $("summary").textContent = label + "・" + t.count + " 筆活動・" + fmtKm(t.km) + " km";

    if (!t.count) {
      $("recap").innerHTML = '<div class="gt-state"><h3>' + esc(label) + " 沒有活動紀錄</h3><p>換一個期間看看，或回到歷史紀錄。</p><a class=\"btn gt-btn secondary\" href=\"history.html\">歷史紀錄</a></div>";
      return;
    }
    var html = '<div class="gt-stats">' +
      stat(fmtKm(t.km), "km", "累積里程", deltaText(t.km, pt.km, " km", prevLabel)) +
      stat(String(t.count), "筆", "活動次數", deltaText(t.count, pt.count, " 筆", prevLabel)) +
      stat(String(t.activeDays), "天", "活動天數", res.streak > 1 ? "最長連續 " + res.streak + " 天" : "") +
      stat(C.fmtDur(t.durMs), "", "活動時間") +
      (t.ascent > 0 ? stat(C.fmtInt(t.ascent), "m", "累積爬升") : "") +
      (featureOn("steps") && t.steps > 0 ? stat(C.fmtInt(t.steps), "步", "活動步數", "各筆活動記錄的步數合計") : "") + "</div>";

    // 長條圖：整年看每月、單月看每天（純 CSS，每根都有文字標籤與 title）。
    var max = 0; res.buckets.forEach(function (b) { if (b.km > max) max = b.km; });
    html += '<section class="gt-panel" aria-labelledby="rc-chart"><h3 id="rc-chart">' + (state.month ? "每日里程" : "每月里程") + "</h3>" +
      '<div class="gt-bars" role="img" aria-label="' + esc(label + (state.month ? "每日" : "每月") + "里程長條圖") + '">' +
      res.buckets.map(function (b) {
        var h = max > 0 ? Math.max(b.km > 0 ? 3 : 0, b.km / max * 100) : 0;
        return '<div class="gt-bar-col" title="' + esc(b.label + "：" + fmtKm(b.km) + " km，" + b.count + " 筆") + '"><div class="gt-bar-v" style="height:' + h.toFixed(1) + '%"></div><div class="gt-bar-l">' + esc(b.label.replace(" 月", "")) + "</div></div>";
      }).join("") + "</div></section>";

    html += '<section class="gt-panel" aria-labelledby="rc-modes"><h3 id="rc-modes">移動方式</h3><div class="gt-modes">' +
      res.modes.map(function (m) { return '<i style="width:' + m.pct.toFixed(2) + "%;background:" + (C.MODE_COLOR[m.mode] || "#94a3b8") + '" title="' + esc(C.modeName(m.mode)) + '"></i>'; }).join("") +
      '</div><ul class="gt-mode-legend">' + res.modes.map(function (m) {
        return '<li><i style="background:' + (C.MODE_COLOR[m.mode] || "#94a3b8") + '"></i>' + C.modeEmoji(m.mode) + " " + esc(C.modeName(m.mode)) + " " + fmtKm(m.km) + " km（" + Math.round(m.pct) + "%・" + m.count + " 筆）</li>";
      }).join("") + "</ul></section>";

    function link(tr, text) { return '<a href="' + GT.activityUrl(tr.trackId) + '">' + esc(text) + "</a>"; }
    var hl = [];
    if (res.longest) hl.push(["📏", "最長一趟", fmtKm(res.longest.distanceMeters / 1000) + " km", link(res.longest, C.activityTitle(res.longest, state.meta) + "・" + C.fmtDay(res.longest.startTimeMs))]);
    if (res.fastest) hl.push(["⚡", "最快速度", (res.fastest.maxSpeedMps * 3.6).toFixed(1) + " km/h", link(res.fastest, C.activityTitle(res.fastest, state.meta) + "・" + C.fmtDay(res.fastest.startTimeMs))]);
    if (res.busiestDay) hl.push(["📅", "里程最多的一天", fmtKm(res.busiestDay.km) + " km", esc(res.busiestDay.day.replace(/-/g, "/") + "・" + res.busiestDay.count + " 筆")]);
    html += '<section class="gt-panel" aria-labelledby="rc-hl"><h3 id="rc-hl">亮點</h3><ul class="gt-hl">' + hl.map(function (x) {
      return '<li class="gt-hl-item" style="cursor:default"><span class="ic" aria-hidden="true">' + x[0] + '</span><span class="tx"><b>' + esc(x[1]) + "</b><small>" + x[3] + '</small></span><span class="val">' + esc(x[2]) + "</span></li>";
    }).join("") + "</ul>" +
      '<p class="gt-note">最快速度取各筆活動記錄的最高速度，超過 ' + R.SPEED_TRUST_MAX_KMH + " km/h 的 GPS 異常值不列入。</p></section>";

    html += '<section class="gt-panel" aria-labelledby="rc-top"><h3 id="rc-top">距離最長的活動</h3><ol class="gt-toplist">' + res.top.map(function (tr) {
      return "<li>" + link(tr, C.activityTitle(tr, state.meta) + "・" + C.fmtDay(tr.startTimeMs)) + "<b>" + fmtKm(tr.distanceMeters / 1000) + " km</b></li>";
    }).join("") + "</ol></section>";
    $("recap").innerHTML = html;
  }

  function buildControls() {
    var ys = R.years(state.tracks), ysel = $("yearSel"), msel = $("monthSel");
    ysel.innerHTML = ys.map(function (y) { return '<option value="' + y + '">' + y + " 年</option>"; }).join("");
    msel.innerHTML = '<option value="0">全年</option>' + [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12].map(function (m) { return '<option value="' + m + '">' + m + " 月</option>"; }).join("");
    var now = new Date(), cur = now.getFullYear();
    state.year = ys.indexOf(cur) >= 0 ? cur : ys[0];
    ysel.value = String(state.year);
    ysel.onchange = function () { state.year = +ysel.value; render(); };
    msel.onchange = function () { state.month = +msel.value; render(); };
    $("controls").hidden = false;
  }

  function load() {
    $("recap").innerHTML = '<div class="gt-skel" style="height:220px"></div>';
    Promise.all([GT.loadManifest(), GT.loadMeta()]).then(function (r) {
      var all = r[0].tracks || [];
      state.tracks = SOLAR_CORE.validTracks(all); state.meta = r[1]; state.excluded = all.length - state.tracks.length;
      if (!state.tracks.length) {
        $("summary").textContent = "尚無活動";
        $("recap").innerHTML = '<div class="gt-state"><h3>還沒有任何活動紀錄</h3><p>在手機 App 錄製並上傳一趟活動後，這裡就會出現回顧。</p></div>';
        return;
      }
      buildControls();
      render();
    }, function (e) {
      $("summary").textContent = "讀取失敗";
      $("recap").innerHTML = '<div class="gt-state err"><h3>無法讀取活動資料</h3><p>' + esc(e.message) + '</p><button class="btn gt-btn" id="retry" type="button">重新載入</button></div>';
      $("retry").onclick = load;
    });
  }
  load();
})();
