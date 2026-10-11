// 太陽系征服計畫 — 純邏輯（無 DOM），方便自動化測試，並由 solar.js 使用。
(function () {
  "use strict";

  var L = window.SOLAR_LEVELS;
  var LEVELS = L.levels.slice().sort(function (a, b) { return a.km - b.km; }); // 單調遞增
  var ANOMALY_MAX_KMH = 1200; // 平均速度超過此值視為 GPS 漂移/異常

  // 去重（相同 trackId 只算一次）+ 異常/缺值過濾。缺失距離不默認為有效里程。
  function validTracks(list) {
    var seen = {}, out = [];
    (list || []).forEach(function (t) {
      if (!t || !t.trackId) return;
      if (seen[t.trackId]) return;
      seen[t.trackId] = true;
      var distKm = (t.distanceMeters || 0) / 1000;
      if (!(distKm > 0)) return;
      var durH = (t.durationMs || 0) / 3600000;
      if (durH <= 0) return;
      if (distKm / durH > ANOMALY_MAX_KMH) return;
      out.push(t);
    });
    return out;
  }

  // 計算總里程與各關卡解鎖時間（累積里程第一次達到門檻的活動時間）。
  function compute(list) {
    var v = validTracks(list).slice().sort(function (a, b) { return a.startTimeMs - b.startTimeMs; });
    var totalKm = 0;
    v.forEach(function (t) { totalKm += (t.distanceMeters || 0) / 1000; });
    var unlock = {}, cum = 0, li = 0;
    for (var i = 0; i < v.length && li < LEVELS.length; i++) {
      cum += (v[i].distanceMeters || 0) / 1000;
      while (li < LEVELS.length && cum >= LEVELS[li].km) {
        unlock[LEVELS[li].id] = { ms: v[i].startTimeMs, trackId: v[i].trackId };
        li++;
      }
    }
    return { totalKm: totalKm, valid: v, unlock: unlock };
  }

  window.SOLAR_CORE = {
    validTracks: validTracks,
    compute: compute,
    levels: LEVELS,
    ANOMALY_MAX_KMH: ANOMALY_MAX_KMH
  };
})();
