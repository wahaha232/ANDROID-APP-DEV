// 主題（跟隨系統／淺色／深色）：在樣式表套用前決定 <html data-theme>，避免淺色主題先閃一下深色。
// 必須在 <head> 內以一般（非 defer）script 載入。設定存在 localStorage；其他分頁切換時同步。
// 桌面版注入 window.GT_THEME_HOST：主題由程式設定（WebView 的 prefers-color-scheme）決定，網頁不另外記。
(function () {
  "use strict";

  var KEY = "gpst_theme";
  var MODES = ["system", "light", "dark"];
  var host = !!window.GT_THEME_HOST;
  var mq = window.matchMedia ? window.matchMedia("(prefers-color-scheme: light)") : null;

  function pref() {
    if (host) return "system";
    try {
      var v = localStorage.getItem(KEY);
      return MODES.indexOf(v) >= 0 ? v : "system";
    } catch (e) { return "system"; } // 無痕模式等
  }
  function effective(p) { return p === "system" ? (mq && mq.matches ? "light" : "dark") : p; }

  function apply() {
    var t = effective(pref()), root = document.documentElement;
    if (root.getAttribute("data-theme") === t) return;
    root.setAttribute("data-theme", t);
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute("content", t === "light" ? "#f4f7fb" : "#0b1220");
    try { document.dispatchEvent(new CustomEvent("gt-themechange", { detail: { theme: t } })); } catch (e) { /* 舊瀏覽器 */ }
  }

  function set(p) {
    if (host || MODES.indexOf(p) < 0) return;
    try { localStorage.setItem(KEY, p); } catch (e) { /* 不保存也能切換這一頁 */ }
    apply();
  }

  if (mq) {
    if (mq.addEventListener) mq.addEventListener("change", apply);
    else if (mq.addListener) mq.addListener(apply);
  }
  window.addEventListener("storage", function (e) { if (e.key === KEY) apply(); });
  apply();

  window.GTTheme = {
    MODES: MODES,
    pref: pref,
    current: function () { return effective(pref()); },
    set: set,
    hostControlled: host
  };
})();
