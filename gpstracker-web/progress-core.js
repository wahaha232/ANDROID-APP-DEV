// 全挑戰同步進度 — 純邏輯：由所有挑戰設定建立目標清單，並計算目前/目標/剩餘/超越/百分比。
// 里程僅計一次（current = 全站有效累計里程）；各挑戰共用同一 mileage，互不重複加總。
(function () {
  "use strict";

  function metric(current, target) {
    var remaining = Math.max(target - current, 0);
    var overshoot = Math.max(current - target, 0);
    var pct = target > 0 ? (current / target) * 100 : 0;
    return { current: current, target: target, pct: pct, remaining: remaining, overshoot: overshoot, done: current >= target };
  }

  // cfg = { totalKm, solar:{levels}, earth:{mileage,explore,exploreBingo,vertical,orbit}, taiwan:{mileage,loop,vertical}, voyager:{probes} }
  function buildGoals(cfg) {
    var goals = [];
    function add(challenge, id, name, target, kind) {
      var m = metric(cfg.totalKm, target);
      goals.push({
        challenge: challenge, id: id, name: name, kind: kind || "mileage",
        target: m.target, current: m.current, pct: m.pct, remaining: m.remaining, overshoot: m.overshoot, done: m.done
      });
    }

    // 台灣征服計畫
    if (cfg.taiwan) {
      (cfg.taiwan.mileage || []).forEach(function (l) { add("台灣征服計畫", "tw-mileage:" + l.id, l.name, l.km, "mileage"); });
      (cfg.taiwan.loop || []).forEach(function (l) { add("台灣征服計畫", "tw-loop:" + l.id, l.name, l.km, "virtual"); });
      ((cfg.taiwan.vertical && cfg.taiwan.vertical.tasks) || []).forEach(function (l) { add("台灣征服計畫", "tw-vertical:" + l.id, l.name, l.km, "virtual"); });
    }

    // 地球征服計畫
    if (cfg.earth) {
      (cfg.earth.mileage || []).forEach(function (l) { add("地球征服計畫", "ea-mileage:" + l.id, l.name, l.km, "mileage"); });
      (cfg.earth.explore || []).forEach(function (l) { add("地球征服計畫", "ea-explore:" + l.id, l.name, l.km, "virtual"); });
      (cfg.earth.exploreBingo || []).forEach(function (l) { add("地球征服計畫", "ea-bingo:" + l.id, l.name, l.km, "virtual"); });
      ((cfg.earth.vertical && cfg.earth.vertical.tasks) || []).forEach(function (l) { add("地球征服計畫", "ea-vertical:" + l.id, l.name, l.km, "virtual"); });
      (cfg.earth.orbit || []).forEach(function (l) { add("地球征服計畫", "ea-orbit:" + l.id, l.name, l.km, "virtual"); });
    }

    // 太陽系征服計畫
    if (cfg.solar) {
      (cfg.solar.levels || []).forEach(function (l) { add("太陽系征服計畫", "sol:" + l.id, l.zh || l.name, l.km, "virtual"); });
    }

    // NASA 航海家追逐賽（目標為探測器距太陽距離；玩家永遠追不上，差距會變大）
    if (cfg.voyager && cfg.voyager.probes) {
      Object.keys(cfg.voyager.probes).forEach(function (k) {
        var p = cfg.voyager.probes[k];
        var name = k === "voyager1" ? "🛰️ 航海家一號（距太陽）" : "🛰️ 航海家二號（距太陽）";
        add("NASA 航海家追逐賽", "voy:" + k, name, p.fromSunKm, "reference");
      });
    }

    return goals;
  }

  function groupByChallenge(goals) {
    var order = [], map = {};
    goals.forEach(function (g) {
      if (!map[g.challenge]) { map[g.challenge] = []; order.push(g.challenge); }
      map[g.challenge].push(g);
    });
    order.forEach(function (c) { map[c].sort(function (a, b) { return a.target - b.target; }); });
    return { order: order, map: map };
  }

  window.PROGRESS_CORE = { metric: metric, buildGoals: buildGoals, groupByChallenge: groupByChallenge };
})();
