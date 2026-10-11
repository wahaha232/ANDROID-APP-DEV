// 地球征服計畫 — 純邏輯（無 DOM），供自動化測試與 earth.js 使用。
// 里程去重/異常過濾「重用」太陽系征服計畫的 SOLAR_CORE.validTracks（單一來源，避免規則分歧）。
(function () {
  "use strict";

  var S = window.SOLAR_CORE;

  // 依任意門檻清單計算「累積里程」與各關卡解鎖（第一次達標的活動時間）。
  function cumulativeUnlock(levels, list) {
    var sorted = levels.slice().sort(function (a, b) { return a.km - b.km; });
    var v = S.validTracks(list).slice().sort(function (a, b) { return a.startTimeMs - b.startTimeMs; });
    var totalKm = 0;
    v.forEach(function (t) { totalKm += (t.distanceMeters || 0) / 1000; });
    var unlock = {}, cum = 0, li = 0;
    for (var i = 0; i < v.length && li < sorted.length; i++) {
      cum += (v[i].distanceMeters || 0) / 1000;
      while (li < sorted.length && cum >= sorted[li].km) {
        unlock[sorted[li].id] = { ms: v[i].startTimeMs, trackId: v[i].trackId, km: sorted[li].km };
        li++;
      }
    }
    return { totalKm: totalKm, valid: v, unlock: unlock };
  }

  // 真實海拔彙整：僅採用明確存在且有限的海拔值；缺值不預設為 0。
  function altitudeStats(details) {
    var has = false, maxAlt = null, minAlt = null, ascent = 0, descent = 0;
    (details || []).forEach(function (t) {
      if (!t) return;
      if (typeof t.maxAltitudeMeters === "number" && isFinite(t.maxAltitudeMeters)) {
        has = true; if (maxAlt === null || t.maxAltitudeMeters > maxAlt) maxAlt = t.maxAltitudeMeters;
      }
      if (typeof t.minAltitudeMeters === "number" && isFinite(t.minAltitudeMeters)) {
        has = true; if (minAlt === null || t.minAltitudeMeters < minAlt) minAlt = t.minAltitudeMeters;
      }
      if (typeof t.totalAscentMeters === "number" && isFinite(t.totalAscentMeters)) ascent += t.totalAscentMeters;
      if (typeof t.totalDescentMeters === "number" && isFinite(t.totalDescentMeters)) descent += t.totalDescentMeters;
    });
    return { has: has, maxAlt: maxAlt, minAlt: minAlt, ascent: ascent, descent: descent };
  }

  // 最高峰至最深海底的垂直高差（km）；僅為參考值計算，不代表玩家實際到達。
  function verticalSpanKm(topM, bottomM) { return (topM + Math.abs(bottomM)) / 1000; }

  window.EARTH_CORE = {
    cumulativeUnlock: cumulativeUnlock,
    altitudeStats: altitudeStats,
    verticalSpanKm: verticalSpanKm,
    validTracks: S.validTracks
  };
})();
