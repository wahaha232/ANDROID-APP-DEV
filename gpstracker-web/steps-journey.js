// 步數環遊里程（獨立頁面）：把累計步數 × 步幅換算成里程，對照太陽系／地球／台灣征服計畫的關卡門檻。
// 只「唯讀」引用 solar-levels.js / earth-levels.js / taiwan-levels.js 的門檻，不使用任何 GPS 里程，也不影響其他頁面。
(function () {
  "use strict";

  var CONFIG = GPST.resolveConfig();
  var DATA_BASE = CONFIG.dataBase;
  var STRIDE_KEY = "gpst_sj_stride_m";
  var AVG_WINDOW_DAYS = 90;          // 預估「還要幾天」時，用最近幾天（含沒走的日子）的平均每日里程
  var EARTH_EQUATOR_KM = 40075;
  var MOON_KM = 384400;

  var state = { daily: null, voyager: null, series: null, stride: 0.7 };

  function $(id) { return document.getElementById(id); }
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function fmtInt(v) { return Math.round(v).toLocaleString("zh-Hant"); }
  function fmtKm(v) {
    if (v >= 100) return Math.round(v).toLocaleString("zh-Hant");
    return v.toLocaleString("zh-Hant", { maximumFractionDigits: 2 });
  }
  function fmtDate(str) { return str.replace(/-/g, "/"); }
  function ymd(d) { return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()); }
  function parseYmd(str) { var a = str.split("-"); return new Date(+a[0], +a[1] - 1, +a[2]); }
  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }

  function loadJSON(name) {
    return fetch(GPST.bust(DATA_BASE + name), { cache: "no-cache" }).then(function (r) {
      if (!r.ok) throw new Error(name + " " + r.status);
      return r.json();
    });
  }

  // ===== 步幅 =====
  function readStride() {
    var v = parseFloat(localStorage.getItem(STRIDE_KEY));
    return v >= 0.3 && v <= 1.5 ? v : 0.7;
  }
  function saveStride(v) { try { localStorage.setItem(STRIDE_KEY, String(v)); } catch (e) { /* 無痕模式等：不保存也能用 */ } }

  // ===== 來源（與步數統計頁相同的 steps.json 格式）=====
  function shortPkg(pkg) {
    if (/fitbit/i.test(pkg)) return "Fitbit";
    var p = pkg.split(".");
    return p[p.length - 1] || pkg;
  }
  function daySteps(day, k) {
    if (k === "total") return day.total || 0;
    var src = day.sources || {};
    if (k.indexOf("o:") === 0) return (src.others && src.others[k.slice(2)]) || 0;
    return src[k] || 0;
  }
  function seriesDefs(daily) {
    var defs = [{ k: "total", name: "合計（去除重複）" }];
    var has = {}, others = {};
    daily.days.forEach(function (d) {
      var src = d.sources || {};
      ["phone", "googleFit", "zepp"].forEach(function (k) { if (src[k]) has[k] = true; });
      Object.keys(src.others || {}).forEach(function (k) { others[k] = true; });
    });
    if (has.googleFit) defs.unshift({ k: "googleFit", name: "Google Fit（小米 Zepp Life）" });
    if (has.phone) defs.push({ k: "phone", name: "手機本身" });
    if (has.zepp) defs.push({ k: "zepp", name: "Zepp Life／小米" });
    Object.keys(others).forEach(function (k) { defs.push({ k: "o:" + k, name: shortPkg(k) }); });
    return defs;
  }

  // ===== 計算：逐日累計（缺少的日期補 0），之後都用步數 × 步幅換算 =====
  function build() {
    var k = state.series;
    var byDate = {}, first = null, last = null;
    state.daily.days.forEach(function (d) {
      var s = daySteps(d, k);
      if (s <= 0) return;
      byDate[d.date] = s;
      if (!first || d.date < first) first = d.date;
      if (!last || d.date > last) last = d.date;
    });
    var rows = [], cum = 0, dataDays = 0;
    if (first) {
      for (var d = parseYmd(first), end = parseYmd(last); d <= end; d.setDate(d.getDate() + 1)) {
        var key = ymd(d), s = byDate[key] || 0;
        cum += s;
        if (s > 0) dataDays++;
        rows.push({ date: key, steps: s, cumSteps: cum });
      }
    }
    return { rows: rows, totalSteps: cum, dataDays: dataDays };
  }
  function kmOfSteps(steps) { return steps * state.stride / 1000; }

  function achievedDate(model, targetKm) {
    for (var i = 0; i < model.rows.length; i++) {
      if (kmOfSteps(model.rows[i].cumSteps) >= targetKm) return model.rows[i].date;
    }
    return null;
  }
  function recentAvgKmPerDay(model) {
    var rows = model.rows.slice(-AVG_WINDOW_DAYS);
    if (!rows.length) return 0;
    var steps = rows.reduce(function (a, r) { return a + r.steps; }, 0);
    return kmOfSteps(steps) / rows.length;
  }
  function fmtDuration(days) {
    if (!isFinite(days)) return "—";
    if (days < 1) return "不到 1 天";
    if (days < 60) return "約 " + Math.ceil(days) + " 天";
    if (days < 730) return "約 " + (days / 30.4).toFixed(1) + " 個月";
    var years = days / 365;
    return "約 " + (years >= 100 ? Math.round(years).toLocaleString("zh-Hant") : years.toFixed(1)) + " 年";
  }

  // ===== 關卡群組（唯讀引用各競賽設定）=====
  function groupsFromConfig() {
    var g = [];
    var S = window.SOLAR_LEVELS, E = window.EARTH_LEVELS, T = window.TAIWAN_LEVELS;
    if (S && S.levels) {
      g.push({ title: "🚀 太陽系征服計畫", sub: "九大天體關卡（累積里程門檻）", list: S.levels.map(function (l) {
        return { name: l.display || l.zh, km: l.km, desc: l.desc, hidden: !!l.hidden };
      }) });
    }
    if (E) {
      g.push({ title: "🌍 地球征服計畫 · 全球里程征服", list: E.mileage });
      g.push({ title: "🌍 地球征服計畫 · 世界探索任務", list: E.explore });
      g.push({ title: "🌍 地球征服計畫 · 距離賓果", list: E.exploreBingo });
      g.push({ title: "🌍 地球征服計畫 · 垂直極限挑戰", list: (E.vertical && E.vertical.tasks) || [] });
      g.push({ title: "🌍 地球征服計畫 · 軌道與逃逸任務", list: E.orbit });
    }
    if (T) {
      g.push({ title: "🇹🇼 台灣征服計畫 · 里程征服", list: T.mileage });
      g.push({ title: "🇹🇼 台灣征服計畫 · 環島挑戰", list: T.loop });
      g.push({ title: "🇹🇼 台灣征服計畫 · 高山與垂直挑戰", list: (T.vertical && T.vertical.tasks) || [] });
    }
    return g.filter(function (x) { return x.list && x.list.length; });
  }

  // ===== 渲染 =====
  function renderControls(defs) {
    var html = '<div class="sj-title">⚙️ 換算設定</div><div class="sj-row" id="sjSeriesRow">';
    defs.forEach(function (d) { html += '<button class="sj-chip' + (d.k === state.series ? " on" : "") + '" data-series="' + esc(d.k) + '">' + esc(d.name) + "</button>"; });
    html += '</div><div class="sj-row">' +
      '<label>步幅（公尺）<input id="sjStride" type="number" min="0.3" max="1.5" step="0.01" value="' + state.stride.toFixed(2) + '"></label>' +
      '<label>或輸入身高 <input id="sjHeight" type="number" min="100" max="230" step="1" placeholder="cm"> cm</label>' +
      '<span class="sj-hint" id="sjHint" style="font-size:12px;color:var(--muted)">一般步行步幅約為身高 × 0.415（身高 170 cm ≈ 0.71 公尺）。步幅會影響所有里程換算。</span>' +
      "</div>";
    $("sjControls").innerHTML = html;

    Array.prototype.forEach.call($("sjControls").querySelectorAll("[data-series]"), function (b) {
      b.addEventListener("click", function () { state.series = b.getAttribute("data-series"); render(); });
    });
    $("sjStride").addEventListener("change", function () {
      var v = parseFloat(this.value);
      if (!(v >= 0.3 && v <= 1.5)) { this.value = state.stride.toFixed(2); return; }
      state.stride = v; saveStride(v); render();
    });
    $("sjHeight").addEventListener("change", function () {
      var h = parseFloat(this.value);
      if (!(h >= 100 && h <= 230)) return;
      state.stride = Math.round(h * 0.415) / 100; saveStride(state.stride); render();
    });
  }

  function card(v, l, s, big) {
    return '<div class="sj-card' + (big ? " big" : "") + '"><div class="v">' + v + '</div><div class="l">' + l + "</div>" + (s ? '<div class="s">' + s + "</div>" : "") + "</div>";
  }

  function renderSummary(model, totalKm) {
    var first = model.rows[0].date, last = model.rows[model.rows.length - 1].date;
    var avgSteps = model.totalSteps / model.dataDays;
    var loop = window.TAIWAN_LEVELS ? 1000 : 0;
    var html = '<div class="sj-title">👟 步數換算結果</div><div class="sj-cards">';
    html += card(fmtInt(model.totalSteps), "累計步數", fmtDate(first) + " – " + fmtDate(last), true);
    html += card(fmtKm(totalKm) + " km", "換算里程", "步幅 " + state.stride.toFixed(2) + " 公尺", true);
    html += card(fmtInt(model.dataDays), "有資料的天數", "期間共 " + fmtInt(model.rows.length) + " 天");
    html += card(fmtInt(avgSteps), "平均每日步數", "≈ " + fmtKm(kmOfSteps(avgSteps)) + " km／日");
    html += card((totalKm / EARTH_EQUATOR_KM).toFixed(2) + " 圈", "相當於繞地球赤道", "一圈 " + fmtInt(EARTH_EQUATOR_KM) + " km");
    html += card((totalKm / MOON_KM * 100).toFixed(1) + "%", "地月距離的比例", "地月平均 " + fmtInt(MOON_KM) + " km");
    if (loop) html += card((totalKm / loop).toFixed(1) + " 次", "台灣環島（以 1,000 km 計）", "對照環島里程達人門檻");
    var upd = state.daily && state.daily.updatedAt ? new Date(state.daily.updatedAt) : null;
    html += card(upd ? pad(upd.getMonth() + 1) + "/" + pad(upd.getDate()) + " " + pad(upd.getHours()) + ":" + pad(upd.getMinutes()) : "—", "最後同步時間", "本頁每 5 分鐘自動更新");
    html += "</div>";
    $("sjSummary").innerHTML = html;
  }

  function niceStep(max, ticks) {
    var raw = max / ticks, mag = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10)), n = raw / mag;
    return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * mag;
  }

  function renderChart(model, totalKm, milestones) {
    var W = 820, H = 280, L = 62, R = 18, T = 16, B = 30;
    var n = model.rows.length;
    var next = null;
    milestones.forEach(function (m) { if (!next && m.km > totalKm) next = m; });
    var yMax = totalKm * 1.08;
    if (next && next.km <= totalKm * 2.2) yMax = next.km * 1.08;
    if (yMax < 1) yMax = 1;
    function X(i) { return L + (W - L - R) * (n <= 1 ? 1 : i / (n - 1)); }
    function Y(km) { return T + (H - T - B) * (1 - km / yMax); }

    var pts = model.rows.map(function (r, i) { return X(i).toFixed(1) + "," + Y(kmOfSteps(r.cumSteps)).toFixed(1); });
    var svg = '<svg viewBox="0 0 ' + W + " " + H + '" role="img" aria-label="累計里程隨時間成長的折線圖">' +
      '<defs><linearGradient id="sjGrad" x1="0" x2="1"><stop offset="0" stop-color="#4c8dff"/><stop offset="1" stop-color="#34d399"/></linearGradient>' +
      '<linearGradient id="sjArea" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="#4c8dff" stop-opacity=".35"/><stop offset="1" stop-color="#4c8dff" stop-opacity="0"/></linearGradient></defs>';
    var step = niceStep(yMax, 4);
    for (var v = 0; v <= yMax; v += step) {
      svg += '<line class="grid" x1="' + L + '" x2="' + (W - R) + '" y1="' + Y(v).toFixed(1) + '" y2="' + Y(v).toFixed(1) + '"/>' +
        '<text x="' + (L - 8) + '" y="' + (Y(v) + 4).toFixed(1) + '" text-anchor="end">' + fmtInt(v) + "</text>";
    }
    svg += '<line class="axis" x1="' + L + '" x2="' + (W - R) + '" y1="' + (H - B) + '" y2="' + (H - B) + '"/>';
    milestones.forEach(function (m) {
      if (m.km <= 0 || m.km > yMax) return;
      var y = Y(m.km).toFixed(1);
      svg += '<line class="ms" x1="' + L + '" x2="' + (W - R) + '" y1="' + y + '" y2="' + y + '"/>' +
        '<text class="ms-label" x="' + (W - R - 4) + '" y="' + (Y(m.km) - 4).toFixed(1) + '" text-anchor="end">' + esc(m.name) + " " + fmtInt(m.km) + " km</text>";
    });
    svg += '<polygon class="area" points="' + L + "," + (H - B) + " " + pts.join(" ") + " " + X(n - 1).toFixed(1) + "," + (H - B) + '"/>' +
      '<polyline class="line" points="' + pts.join(" ") + '"/>';
    [0, Math.floor((n - 1) / 2), n - 1].forEach(function (i, idx) {
      svg += '<text x="' + X(i).toFixed(1) + '" y="' + (H - 8) + '" text-anchor="' + (idx === 0 ? "start" : idx === 2 ? "end" : "middle") + '">' + fmtDate(model.rows[i].date) + "</text>";
    });
    svg += "</svg>";
    $("sjChart").innerHTML = '<div class="sj-title">📈 累計里程成長曲線 <small>橫向虛線為各競賽的關卡門檻</small></div><div class="sj-chart">' + svg + "</div>";
  }

  function renderGroups(model, totalKm, avgKm) {
    var html = '<div class="sj-title">🏁 能達成哪些關卡</div>';
    groupsFromConfig().forEach(function (g) {
      var list = g.list.slice().sort(function (a, b) { return a.km - b.km; });
      var done = list.filter(function (l) { return totalKm >= l.km; }).length;
      var nextShown = false;
      html += '<div class="sj-group"><div class="sj-group-head"><span>' + esc(g.title) + '</span><span class="cnt">已達成 ' + done + " / " + list.length + "</span></div><ul class=\"sj-list\">";
      list.forEach(function (l) {
        var reached = totalKm >= l.km;
        var isNext = !reached && !nextShown;
        if (isNext) nextShown = true;
        var name = l.name, sub = "門檻 " + fmtInt(l.km) + " km";
        if (l.hidden && !reached) name = "？？？（隱藏關卡）";
        var right = "", cls = "", icon = "🔒", bar = "";
        if (reached) {
          cls = "done"; icon = "✅";
          sub += " · 達成於 " + fmtDate(achievedDate(model, l.km) || "—");
          right = "100%";
        } else {
          var pct = totalKm / l.km * 100;
          right = pct >= 1 ? pct.toFixed(1) + "%" : pct.toFixed(pct >= 0.01 ? 2 : 4) + "%";
          if (isNext) {
            cls = "next"; icon = "🎯";
            sub += " · 還差 " + fmtKm(l.km - totalKm) + " km" + (avgKm > 0 ? "（照近 " + AVG_WINDOW_DAYS + " 天平均，" + fmtDuration((l.km - totalKm) / avgKm) + "）" : "");
          }
          bar = '<div class="sj-bar"><i style="width:' + Math.min(100, pct).toFixed(2) + '%"></i></div>';
        }
        html += '<li class="' + cls + '"><span class="st">' + icon + '</span><div class="mid"><div class="t1">' + esc(name) + '</div><div class="t2">' + esc(sub) + "</div>" + bar + '</div><div class="val">' + right + "</div></li>";
      });
      html += "</ul></div>";
    });

    // 航海家追逐（參考距離，玩家追不上）
    if (state.voyager && state.voyager.probes) {
      html += '<div class="sj-group"><div class="sj-group-head"><span>🛰️ NASA 航海家追逐賽（參考）</span><span class="cnt">距太陽（日心）的距離</span></div>' +
        (state.voyagerStatus && state.voyagerStatus.state !== "ok" ? '<div class="sj-empty" style="padding:8px 14px;color:var(--amber)">' + state.voyagerStatus.label + "（參考資料時間 " + GTCH.fmtDateTime(state.voyagerStatus.updatedAt) + "）</div>" : "") + '<ul class="sj-list">';
      Object.keys(state.voyager.probes).forEach(function (key) {
        var p = state.voyager.probes[key];
        var name = key === "voyager1" ? "🛰️ 航海家一號" : "🛰️ 航海家二號";
        var pct = totalKm / p.fromSunKm * 100;
        html += '<li><span class="st">🛰️</span><div class="mid"><div class="t1">' + name + '</div><div class="t2">距太陽 ' + fmtInt(p.fromSunKm) + " km · 你的步數里程是它的 " + pct.toFixed(4) + '%</div><div class="sj-bar"><i style="width:' + Math.min(100, pct).toFixed(3) + '%"></i></div></div><div class="val">' + pct.toFixed(4) + "%</div></li>";
      });
      html += "</ul></div>";
    }
    html += '<div class="sj-foot">各關卡門檻與其他競賽頁使用同一份設定（太陽系／地球／台灣）；台灣的縣市制霸、四極點需要 GPS 位置，無法用步數換算，所以不在此頁。' +
      "里程 = 累計步數 × 步幅，只是估算；步數資料來自 App 同步的 steps.json。" +
      "步數會隨 App 同步持續累計：每次同步只會新增或更新天數，已經上傳的天數永遠保留，累計只增不減；今天的步數也會隨一天進行而增加。</div>";
    $("sjGroups").innerHTML = html;
  }

  function render() {
    var defs = seriesDefs(state.daily);
    var keys = defs.map(function (d) { return d.k; });
    if (!state.series || keys.indexOf(state.series) < 0) state.series = keys.indexOf("googleFit") >= 0 ? "googleFit" : keys.indexOf("phone") >= 0 ? "phone" : "total";
    renderControls(defs);

    var model = build();
    if (!model.rows.length) {
      $("sjSummary").innerHTML = '<div class="sj-empty">這個來源沒有步數資料。請換一個來源。</div>';
      $("sjChart").innerHTML = ""; $("sjGroups").innerHTML = "";
      $("summary").textContent = "沒有資料";
      return;
    }
    var totalKm = kmOfSteps(model.totalSteps);
    var avgKm = recentAvgKmPerDay(model);

    // 圖上的門檻線：地球里程、月球、地球軌道等具代表性的關卡
    var milestones = [];
    if (window.EARTH_LEVELS) window.EARTH_LEVELS.mileage.forEach(function (l) { if (l.km >= 1000) milestones.push({ name: l.name, km: l.km }); });
    if (window.SOLAR_LEVELS) window.SOLAR_LEVELS.levels.slice(0, 2).forEach(function (l) { milestones.push({ name: l.zh, km: l.km }); });
    milestones.sort(function (a, b) { return a.km - b.km; });

    $("summary").textContent = fmtInt(model.totalSteps) + " 步 ≈ " + fmtKm(totalKm) + " km · " + fmtInt(model.dataDays) + " 天資料";
    renderSummary(model, totalKm);
    renderChart(model, totalKm, milestones);
    renderGroups(model, totalKm, avgKm);
  }

  // ===== 持續累計：頁面開著時定期重新讀取 steps.json，新的步數同步上來就會自動加進累計 =====
  var REFRESH_MS = 5 * 60 * 1000;
  var refreshing = false;
  function refresh(manual) {
    if (refreshing) return;
    refreshing = true;
    loadJSON("steps.json").then(function (d) {
      if (d && d.days && d.days.length) { state.daily = d; render(); }
    }).catch(function () { /* 網路暫時失敗：保留目前畫面，下次再試 */ }).then(function () { refreshing = false; });
  }

  function init() {
    state.stride = readStride();
    setInterval(function () { if (state.daily && !document.hidden) refresh(false); }, REFRESH_MS);
    document.addEventListener("visibilitychange", function () { if (!document.hidden && state.daily) refresh(false); });
    Promise.all([
      loadJSON("steps.json").catch(function () { return null; }),
      GTCH.loadVoyager(loadJSON) // 失敗時沿用最後一次有效值並標示，不會消失或歸零
    ]).then(function (res) {
      state.voyagerStatus = res[1]; state.voyager = res[1].data;
      if (!res[0] || !res[0].days || !res[0].days.length) {
        $("summary").textContent = "尚無逐日步數資料";
        $("sjSummary").innerHTML = '<div class="sj-empty">尚無逐日步數資料。請在 App 首頁按「同步到網站」，上傳每天的步數後再回來看。</div>';
        return;
      }
      state.daily = res[0];
      render();
    });
  }

  init();
})();
