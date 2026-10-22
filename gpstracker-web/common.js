// 共用小工具：資料來源解析與 GeoJSON 輔助（index 的 app.js 與 flythrough.html 共用）。
(function () {
  "use strict";

  // 桌面版由 WebView2 在頁面建立前注入 GT_FEATURES；沒有注入時（手機、GitHub Pages）功能全部維持啟用。
  function featureEnabled(name) {
    var features = typeof window !== "undefined" ? window.GT_FEATURES : null;
    return !features || features[name] !== false;
  }

  // 依 GT_FEATURES 隱藏標了 data-gt-feature 的靜態元素（例如分享按鈕、步數欄位）。
  function applyFeatureVisibility() {
    if (typeof document === "undefined") return;
    var nodes = document.querySelectorAll("[data-gt-feature]");
    for (var i = 0; i < nodes.length; i++) {
      var name = nodes[i].getAttribute("data-gt-feature");
      if (!featureEnabled(name)) nodes[i].hidden = true;
    }
  }

  // 解析 ?repo=/?branch=/?data= 參數。
  // 預設走「同源相對路徑」data/（在 GitHub Pages 上即 ./data/），可用 CDN 與瀏覽器快取；
  // 只有明確指定 ?repo= 時才改用 raw.githubusercontent.com（相容舊連結／跨網域）。
  function resolveConfig(search) {
    // 桌面版的資料來源改由 App 設定畫面指定；有 GT_FEATURES 時忽略網址參數，避免被 ?repo= 帶去讀其他位置。
    var desktop = typeof window !== "undefined" && window.GT_FEATURES;
    var params = new URLSearchParams(desktop ? "" : (search || location.search));
    // 只接受正常格式的值；格式不對就退回預設（同源 data/），避免被帶去讀其他路徑或其他網址。
    var repo = params.get("repo") || "";
    var branch = params.get("branch") || "main";
    var dataPath = params.get("data") || "data";
    if (!/^[A-Za-z0-9_.-]{1,100}\/[A-Za-z0-9_.-]{1,100}$/.test(repo) || repo.indexOf("..") >= 0) repo = "";
    if (!/^[A-Za-z0-9_\-\/.]{1,100}$/.test(branch) || branch.indexOf("..") >= 0) branch = "main";
    if (!/^[A-Za-z0-9_\-\/.]{1,100}$/.test(dataPath) || dataPath.indexOf("..") >= 0 || dataPath.charAt(0) === "/") dataPath = "data";
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

  window.GTFeature = featureEnabled;
  window.GPST = { resolveConfig: resolveConfig, emptyLine: emptyLine, emptyFC: emptyFC, bust: bust };

  if (typeof document !== "undefined") {
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", applyFeatureVisibility);
    else applyFeatureVisibility();
  }
})();
