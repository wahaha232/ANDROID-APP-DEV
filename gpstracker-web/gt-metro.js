// GPS 中斷（例如搭捷運）時的「估算路徑」：偵測軌跡裡的中斷段；若中斷前最後一點在 A 站附近、恢復後第一點在 B 站附近，
// 就沿捷運路線（data/metro.json，OpenStreetMap）找 A→B 的路徑，取代「兩點一線」的直線。
// 純函式、不碰 DOM、不發請求，可直接用 node 測試（tests/gt-metro.test.js）。
// 只影響「顯示」：不改變原始軌跡、距離、均速或任何挑戰里程。估算路徑一律標示為估算。
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.GTMetro = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  var DEFAULTS = {
    minGapSec: 90,        // 相鄰兩點時間差 ≥ 這個秒數才可能是「訊號中斷」
    minGapM: 800,         // 且直線距離 ≥ 這個公尺數
    stationRadiusM: 800,  // 中斷兩端要在站點多遠以內才視為「從某站進、從某站出」
    minSpeedKmh: 5,       // 估算路徑平均速度（含候車、停靠）合理範圍
    maxSpeedKmh: 100,
    maxDetourRatio: 2.5   // 路線長度 / 直線距離 上限（避免不合理的繞路）
  };

  function hav(a, b) {
    var R = 6371000, la1 = a[1] * Math.PI / 180, la2 = b[1] * Math.PI / 180;
    var dp = la2 - la1, dl = (b[0] - a[0]) * Math.PI / 180;
    var h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(la1) * Math.cos(la2) * Math.sin(dl / 2) * Math.sin(dl / 2);
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
  }
  function opts(o) { var r = {}, k; for (k in DEFAULTS) r[k] = o && o[k] != null ? o[k] : DEFAULTS[k]; return r; }

  // 找出中斷段。回傳 [{ i, from, to, dtSec, straightM }]，i＝中斷前最後一點的索引（中斷段是 i → i+1）。
  function findGaps(points, o) {
    var c = opts(o), out = [];
    for (var i = 0; i + 1 < (points || []).length; i++) {
      var a = points[i], b = points[i + 1];
      var dt = (b[4] || 0) - (a[4] || 0);
      if (dt < c.minGapSec) continue;
      var d = hav([a[0], a[1]], [b[0], b[1]]);
      if (d >= c.minGapM) out.push({ i: i, from: [a[0], a[1]], to: [b[0], b[1]], dtSec: dt, straightM: d });
    }
    return out;
  }

  // 站點圖：節點＝站（已合併轉乘站），邊＝站間路線（附幾何）。
  function buildGraph(metro) {
    var adj = {}, stations = {};
    (metro.stations || []).forEach(function (s) { stations[s.id] = s; adj[s.id] = []; });
    (metro.edges || []).forEach(function (e, idx) {
      if (!adj[e.a] || !adj[e.b]) return;
      adj[e.a].push({ to: e.b, m: e.m, e: idx, fwd: true });
      adj[e.b].push({ to: e.a, m: e.m, e: idx, fwd: false });
    });
    return { metro: metro, stations: stations, adj: adj, list: metro.stations || [] };
  }

  function nearestStation(g, lng, lat, maxM) {
    var best = null, bd = Infinity;
    g.list.forEach(function (s) {
      // 先用粗略的經緯度差排除，省掉大部分 haversine
      if (Math.abs(s.y - lat) > 0.02 || Math.abs(s.x - lng) > 0.02) return;
      var d = hav([lng, lat], [s.x, s.y]);
      if (d < bd) { bd = d; best = s; }
    });
    return best && bd <= maxM ? { station: best, dist: bd } : null;
  }

  // Dijkstra（站數很少，用簡單的線性選最小即可）。回傳 { stations:[id…], hops:[{edge, fwd}], lengthM, lines:[ref…] } 或 null。
  function route(g, from, to) {
    if (from === to || !g.adj[from] || !g.adj[to]) return null;
    var dist = {}, prev = {}, done = {}, ids = Object.keys(g.adj), n = 0;
    dist[from] = 0;
    while (n++ < ids.length + 1) {
      var u = null, ud = Infinity;
      for (var k = 0; k < ids.length; k++) { var id = ids[k]; if (!done[id] && dist[id] != null && dist[id] < ud) { ud = dist[id]; u = id; } }
      if (u == null || String(u) === String(to)) break;
      done[u] = true;
      g.adj[u].forEach(function (h) {
        var nd = ud + h.m;
        if (dist[h.to] == null || nd < dist[h.to]) { dist[h.to] = nd; prev[h.to] = { from: u, hop: h }; }
      });
    }
    if (dist[to] == null) return null;
    var hops = [], cur = String(to);
    var stations = [Number(to)];
    while (cur !== String(from)) {
      var p = prev[cur];
      hops.push({ edge: p.hop.e, fwd: p.hop.fwd });
      stations.push(Number(p.from));
      cur = String(p.from);
    }
    hops.reverse(); stations.reverse();
    var lines = [];
    hops.forEach(function (h) { var l = g.metro.edges[h.edge].l; if (lines.indexOf(l) < 0) lines.push(l); });
    return { stations: stations, hops: hops, lengthM: dist[to], lines: lines };
  }

  function pathCoords(g, r, from, to, stFrom, stTo) {
    var out = [from];
    r.hops.forEach(function (h) {
      var geo = g.metro.edges[h.edge].g.slice();
      if (!h.fwd) geo.reverse();
      geo.forEach(function (c) { out.push(c); });
    });
    out.push(to);
    return out;
  }
  function pathLen(cs) { var s = 0; for (var i = 1; i < cs.length; i++) s += hav(cs[i - 1], cs[i]); return s; }

  // 估算單一中斷段。成功：{ kind:"metro", coords, lengthM, fromName, toName, lines, stops, speedKmh }；否則 { kind:"unknown" }。
  function estimate(g, gap, o) {
    var c = opts(o);
    var a = nearestStation(g, gap.from[0], gap.from[1], c.stationRadiusM);
    var b = nearestStation(g, gap.to[0], gap.to[1], c.stationRadiusM);
    if (!a || !b || a.station.id === b.station.id) return { kind: "unknown" };
    var r = route(g, a.station.id, b.station.id);
    if (!r) return { kind: "unknown" };
    var coords = pathCoords(g, r, gap.from, gap.to);
    var len = pathLen(coords);
    var speed = gap.dtSec > 0 ? len / gap.dtSec * 3.6 : 0;
    if (len > gap.straightM * c.maxDetourRatio + 1500 || speed < c.minSpeedKmh || speed > c.maxSpeedKmh) return { kind: "unknown" };
    var lines = r.lines.map(function (ref) { var l = g.metro.lines[ref] || {}; return { ref: ref, name: l.n || ref, color: l.c || "#888888" }; });
    return {
      kind: "metro", coords: coords, lengthM: len, speedKmh: speed, stops: r.stations.length,
      fromName: a.station.n, toName: b.station.n, lines: lines,
      stations: r.stations.map(function (id) { return g.stations[id].n; })
    };
  }

  // 對整條軌跡：回傳每個中斷段的結果（metro 為 null 時＝沒有捷運資料，全部當作「未知中斷」）。
  function annotate(points, metro, o) {
    var gaps = findGaps(points, o), g = metro ? buildGraph(metro) : null;
    return gaps.map(function (gap) {
      var est = g ? estimate(g, gap, o) : { kind: "unknown" };
      var out = { i: gap.i, from: gap.from, to: gap.to, dtSec: gap.dtSec, straightM: gap.straightM };
      for (var k in est) out[k] = est[k];
      return out;
    });
  }

  // 為回放把估算路徑「插入」點陣列：沿路徑依距離比例內插時間，讓標記沿著路線走。
  // 只插入中斷段中間的點（兩端是原本的 GPS 點）。回傳 { points, gapEdge }，gapEdge[j] = 邊 (j-1 → j) 是否屬於中斷段。
  function insertEstimated(points, gaps) {
    var byI = {}, out = [], flag = [];
    (gaps || []).forEach(function (g) { byI[g.i] = g; });
    for (var i = 0; i < points.length; i++) {
      var g = i > 0 ? byI[i - 1] : null;
      if (g && g.kind === "metro" && g.coords && g.coords.length > 2) {
        var a = points[i - 1], b = points[i], cs = g.coords, cum = [0], total = 0, m;
        for (m = 1; m < cs.length; m++) { total += hav(cs[m - 1], cs[m]); cum.push(total); }
        var t0 = a[4] || 0, t1 = b[4] || 0, avg = g.dtSec > 0 ? total / g.dtSec * 3.6 : 0;
        for (m = 1; m < cs.length - 1; m++) {
          var f = total > 0 ? cum[m] / total : 0;
          out.push([cs[m][0], cs[m][1], (a[2] || 0) + ((b[2] || 0) - (a[2] || 0)) * f, Math.round(avg * 10) / 10, Math.round(t0 + (t1 - t0) * f)]);
          flag.push(true);
        }
      }
      out.push(points[i]);
      flag.push(!!g);   // 到達原始點 i 的那條邊：若前一點是某中斷段的起點，這條邊也屬於中斷段
    }
    return { points: out, gapEdge: flag };
  }

  // 沒有插入時（例如沒有捷運資料）也要能標記中斷邊：回傳與原點陣列等長的旗標。
  function gapFlags(points, gaps) {
    var f = new Array(points.length), byI = {}, i;
    for (i = 0; i < f.length; i++) f[i] = false;
    (gaps || []).forEach(function (g) { byI[g.i] = true; });
    for (i = 1; i < points.length; i++) f[i] = !!byI[i - 1];
    return f;
  }

  return {
    DEFAULTS: DEFAULTS, hav: hav, findGaps: findGaps, buildGraph: buildGraph, nearestStation: nearestStation, route: route,
    estimate: estimate, annotate: annotate, insertEstimated: insertEstimated, gapFlags: gapFlags
  };
});
