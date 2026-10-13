// 全站一致的主導覽：把各頁 <header> 右側的連結列換成同一組，並在「探索挑戰」相關頁加上子導覽。
// 沒有 JS 時各頁原本的連結仍在（漸進式增強）。
(function () {
  "use strict";

  var MAIN = [
    { id: "home", href: "index.html", label: "首頁" },
    { id: "history", href: "history.html", label: "歷史紀錄" },
    { id: "explore", href: "explore.html", label: "探索挑戰" },
    { id: "manage", href: "records.html", label: "紀錄管理" }
  ];
  var SUB = [
    { href: "explore.html", label: "挑戰總覽" },
    { href: "challenges.html", label: "🏆 競賽中心" },
    { href: "solar.html", label: "🚀 太陽系" },
    { href: "earth.html", label: "🌍 地球" },
    { href: "taiwan.html", label: "🇹🇼 台灣" },
    { href: "progress.html", label: "📈 全挑戰進度" },
    { href: "steps-journey.html", label: "👟 步數環遊" }
  ];
  var SECTION = {
    "index.html": "home", "": "home",
    "history.html": "history", "activity.html": "history",
    "records.html": "manage",
    "explore.html": "explore", "challenges.html": "explore", "solar.html": "explore", "earth.html": "explore",
    "taiwan.html": "explore", "progress.html": "explore", "steps-journey.html": "explore"
  };

  var file = location.pathname.split("/").pop();
  var section = SECTION[file];
  var header = document.querySelector("header");
  if (!header || !section) return;

  // 自訂 repo/branch/data 時把參數帶到下一頁（與 gt-data.js 的 carry 相同語意；這裡只依賴所有頁面都有的 common.js）。
  var carry = "";
  if (window.GPST) {
    var cfg = GPST.resolveConfig();
    if (cfg.repo || cfg.dataPath !== "data" || cfg.branch !== "main") {
      carry = "repo=" + encodeURIComponent(cfg.repo) + "&branch=" + encodeURIComponent(cfg.branch) + "&data=" + encodeURIComponent(cfg.dataPath);
    }
  }
  function link(href) { return carry ? href + "?" + carry : href; }

  // 品牌圖示也是回首頁的連結（導覽列在手機上已滿，不再多加項目）。
  var logo = header.querySelector(".brand .logo");
  if (logo && logo.tagName !== "A") {
    var home = document.createElement("a");
    home.className = logo.className;
    home.href = link("index.html");
    home.title = "回首頁";
    home.setAttribute("aria-label", "回首頁");
    home.innerHTML = logo.innerHTML;
    logo.parentNode.replaceChild(home, logo);
  }

  var actions = header.lastElementChild;
  if (!actions || actions.classList.contains("brand")) {
    actions = document.createElement("div");
    header.appendChild(actions);
  }
  actions.className = "gt-nav";
  actions.setAttribute("role", "navigation");
  actions.setAttribute("aria-label", "主要導覽");
  actions.removeAttribute("style");
  actions.innerHTML = MAIN.map(function (m) {
    return '<a href="' + link(m.href) + '"' + (m.id === section ? ' aria-current="page"' : "") + ">" + m.label + "</a>";
  }).join("");

  if (section === "explore") {
    var sub = document.createElement("nav");
    sub.className = "gt-subnav";
    sub.setAttribute("aria-label", "探索挑戰");
    sub.innerHTML = SUB.map(function (s) {
      return '<a href="' + link(s.href) + '"' + (s.href === (file || "index.html") ? ' aria-current="page"' : "") + ">" + s.label + "</a>";
    }).join("");
    header.parentNode.insertBefore(sub, header.nextSibling);
  }
})();
