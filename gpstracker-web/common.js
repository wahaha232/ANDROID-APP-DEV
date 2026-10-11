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

  window.GPST = { resolveConfig: resolveConfig, emptyLine: emptyLine, emptyFC: emptyFC };
})();
