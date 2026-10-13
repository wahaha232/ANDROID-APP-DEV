// 年度／月度回顧的純邏輯（不碰 DOM、不發網路請求，可用 node 單元測試：tests/recap.test.js）。
// 輸入是 manifest 的活動摘要（建議先用 SOLAR_CORE.validTracks 去重／排除異常，與全站同一個「有效活動」定義）。
// 所有數字都只來自傳進來的活動；沒有資料的期間回傳 0／空陣列，不捏造。
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.GTRecap = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  var SPEED_TRUST_MAX_KMH = 400; // 與 gt-core／競賽中心一致：超過視為 GPS 異常，不列為「最快」

  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function dayKey(ms) { var d = new Date(ms); return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()); }
  function km(t) { return (t.distanceMeters || 0) / 1000; }

  // 有活動的年份（新→舊）。
  function years(tracks) {
    var seen = {};
    (tracks || []).forEach(function (t) { seen[new Date(t.startTimeMs).getFullYear()] = true; });
    return Object.keys(seen).map(Number).sort(function (a, b) { return b - a; });
  }

  // month：0 = 整年；1～12 = 該月。
  function inPeriod(t, year, month) {
    var d = new Date(t.startTimeMs);
    return d.getFullYear() === year && (!month || d.getMonth() + 1 === month);
  }
  function previousPeriod(year, month) {
    if (!month) return { year: year - 1, month: 0 };
    return month === 1 ? { year: year - 1, month: 12 } : { year: year, month: month - 1 };
  }

  function totalsOf(list) {
    var o = { count: list.length, km: 0, durMs: 0, ascent: 0, steps: 0, activeDays: 0 }, days = {};
    list.forEach(function (t) {
      o.km += km(t); o.durMs += t.durationMs || 0; o.ascent += t.totalAscentMeters || 0; o.steps += t.stepCount || 0;
      days[dayKey(t.startTimeMs)] = true;
    });
    o.activeDays = Object.keys(days).length;
    return o;
  }

  // 最長連續活動天數（日曆日連續）。
  function longestStreak(list) {
    var keys = {};
    list.forEach(function (t) { keys[dayKey(t.startTimeMs)] = true; });
    var days = Object.keys(keys).sort(), best = 0, run = 0, prev = null;
    days.forEach(function (k) {
      var p = k.split("-"), cur = new Date(+p[0], +p[1] - 1, +p[2], 12).getTime();
      run = prev !== null && Math.round((cur - prev) / 86400000) === 1 ? run + 1 : 1;
      prev = cur;
      if (run > best) best = run;
    });
    return best;
  }

  // opts: { year, month (0=整年), modeOf: function(track) -> mode 字串（讓「紀錄管理」改過的移動方式生效） }
  function compute(tracks, opts) {
    var year = opts.year, month = opts.month || 0, modeOf = opts.modeOf || function (t) { return t.dominantMode || "UNKNOWN"; };
    var list = (tracks || []).filter(function (t) { return inPeriod(t, year, month); });
    var pp = previousPeriod(year, month);
    var prevList = (tracks || []).filter(function (t) { return inPeriod(t, pp.year, pp.month); });

    // 圖表分組：整年＝12 個月；單月＝該月每一天。
    var buckets = [], i;
    if (!month) {
      for (i = 1; i <= 12; i++) buckets.push({ key: i, label: i + " 月", km: 0, count: 0 });
      list.forEach(function (t) { var b = buckets[new Date(t.startTimeMs).getMonth()]; b.km += km(t); b.count++; });
    } else {
      var n = new Date(year, month, 0).getDate();
      for (i = 1; i <= n; i++) buckets.push({ key: i, label: String(i), km: 0, count: 0 });
      list.forEach(function (t) { var b = buckets[new Date(t.startTimeMs).getDate() - 1]; b.km += km(t); b.count++; });
    }

    var modeKm = {}, modeCount = {}, totalKm = 0;
    list.forEach(function (t) { var m = modeOf(t); modeKm[m] = (modeKm[m] || 0) + km(t); modeCount[m] = (modeCount[m] || 0) + 1; totalKm += km(t); });
    var modes = Object.keys(modeKm).map(function (m) { return { mode: m, km: modeKm[m], count: modeCount[m], pct: totalKm > 0 ? modeKm[m] / totalKm * 100 : 0 }; })
      .sort(function (a, b) { return b.km - a.km; });

    var byDay = {};
    list.forEach(function (t) { var k = dayKey(t.startTimeMs); (byDay[k] = byDay[k] || { day: k, km: 0, count: 0 }); byDay[k].km += km(t); byDay[k].count++; });
    var busiest = null;
    Object.keys(byDay).forEach(function (k) { if (!busiest || byDay[k].km > busiest.km) busiest = byDay[k]; });

    var longest = null, fastest = null;
    list.forEach(function (t) {
      if (!longest || km(t) > km(longest)) longest = t;
      var v = (t.maxSpeedMps || 0) * 3.6;
      if (v > 0 && v <= SPEED_TRUST_MAX_KMH && (!fastest || v > (fastest.maxSpeedMps || 0) * 3.6)) fastest = t;
    });
    var top = list.slice().sort(function (a, b) { return km(b) - km(a); }).slice(0, 5);

    return {
      period: { year: year, month: month }, previous: { period: pp, totals: totalsOf(prevList) }, totals: totalsOf(list),
      buckets: buckets, modes: modes, busiestDay: busiest, longest: longest, fastest: fastest, top: top, streak: longestStreak(list)
    };
  }

  // 與上一期比較：回傳 { diff, pct }；上一期為 0 時 pct 為 null（不顯示「無限大」）。
  function delta(cur, prev) { return { diff: cur - prev, pct: prev > 0 ? (cur - prev) / prev * 100 : null }; }

  return { years: years, compute: compute, delta: delta, previousPeriod: previousPeriod, longestStreak: longestStreak, dayKey: dayKey, SPEED_TRUST_MAX_KMH: SPEED_TRUST_MAX_KMH };
});
