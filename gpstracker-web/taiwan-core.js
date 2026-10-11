// 台灣征服計畫 — 純邏輯（無 DOM），供測試與 taiwan.js 使用。
// 里程去重/異常沿用 SOLAR_CORE.validTracks；解鎖/海拔沿用 EARTH_CORE。
(function () {
  "use strict";

  var S = window.SOLAR_CORE;
  var E = window.EARTH_CORE;

  function inBox(lon, lat, b) { return lon >= b.w && lon <= b.e && lat >= b.s && lat <= b.n; }

  function havKm(lon1, lat1, lon2, lat2) {
    var R = 6371, dLat = (lat2 - lat1) * Math.PI / 180, dLon = (lon2 - lon1) * Math.PI / 180;
    var a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
  }

  // 由完整軌跡判定：區域到訪、四極點到訪、最大縱向/橫向跨距。
  function analyzeDetails(details, regions, extremes) {
    var regionHits = {}, extremeHits = {}, maxLatSpan = 0, maxLonSpan = 0;
    (details || []).forEach(function (t) {
      if (!t || !t.points || !t.points.length) return;
      var latMin = Infinity, latMax = -Infinity, lonMin = Infinity, lonMax = -Infinity;
      t.points.forEach(function (p) {
        var lon = p[0], lat = p[1];
        if (typeof lon !== "number" || typeof lat !== "number" || !isFinite(lon) || !isFinite(lat)) return;
        if (lat < latMin) latMin = lat; if (lat > latMax) latMax = lat;
        if (lon < lonMin) lonMin = lon; if (lon > lonMax) lonMax = lon;
        (regions || []).forEach(function (r) { if (inBox(lon, lat, r.box)) regionHits[r.id] = true; });
        (extremes || []).forEach(function (x) { if (havKm(lon, lat, x.lon, x.lat) <= x.radiusKm) extremeHits[x.id] = true; });
      });
      if (isFinite(latMin)) { var ls = latMax - latMin; if (ls > maxLatSpan) maxLatSpan = ls; }
      if (isFinite(lonMin)) { var os = lonMax - lonMin; if (os > maxLonSpan) maxLonSpan = os; }
    });
    return { regionHits: regionHits, extremeHits: extremeHits, maxLatSpan: maxLatSpan, maxLonSpan: maxLonSpan };
  }

  // 點在多邊形內（ray casting），支援 Polygon / MultiPolygon 座標陣列。
  function pointInRing(lon, lat, ring) {
    var inside = false;
    for (var i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      var xi = ring[i][0], yi = ring[i][1], xj = ring[j][0], yj = ring[j][1];
      if (((yi > lat) !== (yj > lat)) && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) inside = !inside;
    }
    return inside;
  }
  function pointInPolygon(lon, lat, polygon) { // polygon = [ring, hole...]
    if (!polygon || !polygon.length) return false;
    if (!pointInRing(lon, lat, polygon[0])) return false;
    for (var k = 1; k < polygon.length; k++) if (pointInRing(lon, lat, polygon[k])) return false;
    return true;
  }
  function countiesVisited(details, geo) {
    // geo = GeoJSON FeatureCollection of county polygons; 需完整軌跡座標。
    var hit = {};
    if (!geo || !geo.features) return hit;
    (details || []).forEach(function (t) {
      if (!t || !t.points) return;
      t.points.forEach(function (p) {
        var lon = p[0], lat = p[1];
        geo.features.forEach(function (f) {
          var name = (f.properties && (f.properties.name || f.properties.COUNTYNAME)) || null;
          if (!name) return;
          var g = f.geometry; if (!g) return;
          if (g.type === "Polygon" && pointInPolygon(lon, lat, g.coordinates)) hit[name] = true;
          else if (g.type === "MultiPolygon") g.coordinates.forEach(function (poly) { if (pointInPolygon(lon, lat, poly)) hit[name] = true; });
        });
      });
    });
    return hit;
  }

  window.TAIWAN_CORE = {
    cumulativeUnlock: E.cumulativeUnlock,   // 重用
    altitudeStats: E.altitudeStats,          // 重用
    validTracks: S.validTracks,              // 重用
    inBox: inBox,
    havKm: havKm,
    analyzeDetails: analyzeDetails,
    countiesVisited: countiesVisited
  };
})();
