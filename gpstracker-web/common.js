// 共用小工具：資料來源解析與 GeoJSON 輔助（index 的 app.js 與 flythrough.html 共用）。
(function () {
  "use strict";

  // 解析 ?repo=/?branch=/?data= 參數。
  // 預設走「同源相對路徑」data/（在 GitHub Pages 上即 ./data/），可用 CDN 與瀏覽器快取；
  // 只有明確指定 ?repo= 時才改用 raw.githubusercontent.com（相容舊連結／跨網域）。
  function resolveConfig(search) {
    var params = new URLSearchParams(search || location.search);
    var repo = params.get("repo") || "";
    var branch = params.get("branch") || "main";
    var dataPath = params.get("data") || "data";
    var dataBase = repo
      ? "https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + dataPath + "/"
      : dataPath.replace(/\/+$/, "") + "/";
    return { repo: repo, branch: branch, dataPath: dataPath, dataBase: dataBase };
  }

  function emptyLine() {
    return { type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: [] } };
  }

  function emptyFC() {
    return { type: "FeatureCollection", features: [] };
  }

  // 快取破壞參數。GitHub Pages 的 CDN 對檔案設 max-age=600，而且不一定理會瀏覽器的 cache:"no-cache"，
  // 所以「刪除／更新資料」後最久會看到 10 分鐘前的舊內容（例如在紀錄管理刪除了，首頁還在）。
  // 給 URL 加上變動的參數就會繞過 CDN 的快取。
  // coarse = true：參數每分鐘才變一次（同一分鐘內可共用快取，最多落後 1 分鐘），給較大的軌跡檔用。
  function bust(url, coarse) {
    var t = coarse ? Math.floor(Date.now() / 60000) : Date.now();
    return url + (url.indexOf("?") >= 0 ? "&" : "?") + "t=" + t;
  }

  window.GPST = { resolveConfig: resolveConfig, emptyLine: emptyLine, emptyFC: emptyFC, bust: bust };
})();
