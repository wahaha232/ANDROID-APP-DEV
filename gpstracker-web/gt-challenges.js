// 五大挑戰的共用邏輯（首頁、探索挑戰中心共用）：有效里程、各挑戰進度摘要、Voyager 參考資料狀態、進度卡片 HTML。
// 不碰 DOM、不發網路請求（資料由呼叫端傳入），所以可以用 node 的 vm 載入後單元測試（tests/challenges.test.js）。
// 里程只計一次：一律由 SOLAR_CORE.validTracks 去重／排除異常後加總；各挑戰共用同一份 totalKm，互不影響、同步累積。
// 依賴（皆為既有檔案，本檔不改動它們）：solar-levels.js、solar-core.js、earth-levels.js、taiwan-levels.js、progress-core.js。
(function () {
  "use strict";

  var W = typeof window !== "undefined" ? window : this;

  // 挑戰名稱必須與 progress-core.js 的 buildGoals 使用的名稱一致。
  var NAME = { solar: "太陽系征服計畫", earth: "地球征服計畫", taiwan: "台灣征服計畫", voyager: "NASA 航海家追逐賽" };
  var VOYAGER_KEY = "gpst_voyager_last";                 // 瀏覽器端保存「最後一次有效」的 Voyager 資料
  var VOYAGER_STALE_MS = 2 * 86400000;                    // 排程每日更新一次；超過 2 天沒更新就標示過期
  var MANIFEST_STALE_MS = 3 * 86400000;                   // 紀錄資料超過 3 天沒更新時提醒（可能非最新）

  // ───── 格式化（數字不封頂：百分比可超過 100%；只有進度條的填滿寬度封頂）─────
  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; });
  }
  function fmtKm(v) { return (Math.round((+v || 0) * 100) / 100).toLocaleString("zh-Hant", { maximumFractionDigits: 2 }); }
  function fmtPct(p) {
    p = +p || 0;
    // 未達 100% 時一律「捨去」而非四捨五入，避免 99.96% 顯示成 100% 而被誤認為已達成。
    if (p >= 100) return (Math.round(p * 10) / 10).toLocaleString("zh-Hant", { maximumFractionDigits: 1 }) + "%";
    if (p >= 1) return (Math.floor(p * 10) / 10).toFixed(1) + "%";
    if (p >= 0.01) return (Math.floor(p * 100) / 100).toFixed(2) + "%";
    return p > 0 ? "<0.01%" : "0%";
  }
  function barWidth(p) { p = +p || 0; return Math.max(0, Math.min(100, p)); }
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function fmtDateTime(ms) {
    if (!ms) return "未知";
    var d = new Date(ms);
    return d.getFullYear() + "/" + pad(d.getMonth() + 1) + "/" + pad(d.getDate()) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes());
  }
  function fmtDay(ms) { if (!ms) return "—"; var d = new Date(ms); return d.getFullYear() + "/" + pad(d.getMonth() + 1) + "/" + pad(d.getDate()); }
  function fmtAge(ms) {
    if (!(ms >= 0)) return "";
    var m = Math.floor(ms / 60000);
    if (m < 60) return m < 1 ? "剛剛" : m + " 分鐘前";
    var h = Math.floor(m / 60);
    if (h < 48) return h + " 小時前";
    return Math.floor(h / 24) + " 天前";
  }

  // ───── 有效里程（全站唯一定義：SOLAR_CORE.validTracks）─────
  function computeTotals(tracks) {
    tracks = tracks || [];
    var valid = W.SOLAR_CORE.validTracks(tracks), km = 0, last = 0;
    valid.forEach(function (t) {
      km += (t.distanceMeters || 0) / 1000;
      if ((t.startTimeMs || 0) > last) last = t.startTimeMs;
    });
    return { totalKm: km, validCount: valid.length, recordCount: tracks.length, excluded: tracks.length - valid.length, lastActivityMs: last || null };
  }

  // ───── Voyager 參考資料：驗證、保存最後有效值、狀態 ─────
  // 不合理的資料（缺欄位、0、負數、非數字）一律視為「更新失敗」，不會覆蓋最後一次有效值，更不會把進度重設為零。
  function validVoyager(d) {
    if (!d || !d.probes || !(d.updatedAt > 0)) return false;
    return ["voyager1", "voyager2"].every(function (id) {
      var p = d.probes[id];
      return !!p && isFinite(p.fromSunKm) && p.fromSunKm >= 1e10 && p.fromSunKm <= 1e12 &&
        (p.fromEarthKm == null || (isFinite(p.fromEarthKm) && p.fromEarthKm > 0));
    });
  }
  function readCache(storage) {
    try {
      var raw = storage && storage.getItem(VOYAGER_KEY);
      var d = raw ? JSON.parse(raw) : null;
      return validVoyager(d) ? d : null;
    } catch (e) { return null; }
  }
  function writeCache(storage, d) { try { if (storage) storage.setItem(VOYAGER_KEY, JSON.stringify(d)); } catch (e) { /* 無痕模式等：不保存也能用 */ } }

  function voyagerStatus(data, source, error, now) {
    if (!data) return { state: "none", source: "none", data: null, updatedAt: null, ageMs: null, error: error || null, tone: "err", label: "⚠️ 尚無 Voyager 參考資料（讀取失敗，且沒有先前保存的有效資料）" };
    var age = now - data.updatedAt, stale = age > VOYAGER_STALE_MS;
    var state = source === "cache" ? "failed" : stale ? "stale" : "ok";
    var label = state === "failed" ? "⚠️ 本次更新失敗，顯示最後一次成功更新的資料" :
      state === "stale" ? "⚠️ 資料已過期（超過 2 天沒有更新），顯示最後一次成功更新的資料" : "✅ 資料正常";
    return { state: state, source: source, data: data, updatedAt: data.updatedAt, ageMs: age, error: error || null, tone: state === "ok" ? "ok" : "warn", label: label };
  }

  // fetchFn(name) → Promise<json>；永遠 resolve（不會 reject），由 state 描述結果。
  function loadVoyager(fetchFn, opts) {
    opts = opts || {};
    var now = opts.now != null ? opts.now : Date.now();
    var storage = opts.storage !== undefined ? opts.storage : (function () { try { return W.localStorage; } catch (e) { return null; } })();
    var cached = readCache(storage);
    return Promise.resolve().then(function () { return fetchFn("voyager.json"); }).then(function (d) {
      if (!validVoyager(d)) throw new Error("voyager.json 內容不合理，已忽略");
      // CDN 可能回傳較舊的版本：永遠採用「更新時間較新」的那一份，不讓舊資料冒充即時資料。
      if (cached && cached.updatedAt > d.updatedAt) return voyagerStatus(cached, "cache", "取得的資料比已保存的舊", now);
      writeCache(storage, d);
      return voyagerStatus(d, "live", null, now);
    }).catch(function (e) {
      return voyagerStatus(cached, cached ? "cache" : "none", (e && e.message) || String(e), now);
    });
  }

  // ───── 進度摘要 ─────
  function metric(cur, target) {
    var m = W.PROGRESS_CORE.metric(cur, target);
    return { current: m.current, target: m.target, pct: m.pct, remaining: m.remaining, overshoot: m.overshoot, done: m.done };
  }

  // 一張征服計畫卡：下一個未達成的目標 = 目前代表性目標；全部達成時以最高目標為準（顯示超標里程）。
  function conquestPlan(def, goals, totalKm, dataInfo) {
    var done = goals.filter(function (g) { return g.done; });
    var next = goals.filter(function (g) { return !g.done; })[0] || null;
    var lastDone = done.length ? done[done.length - 1] : null; // goals 已依門檻由小到大排序
    var rep = next || goals[goals.length - 1] || null;
    return {
      id: def.id, kind: "conquest", name: def.name, icon: def.icon, href: def.href, desc: def.desc, note: def.note || "",
      current: totalKm, goalsDone: done.length, goalsTotal: goals.length,
      rep: rep ? { name: rep.name, target: rep.target, pct: rep.pct, remaining: rep.remaining, overshoot: rep.overshoot, done: rep.done } : null,
      lastDone: lastDone ? { name: lastDone.name, target: lastDone.target, overshoot: Math.max(0, totalKm - lastDone.target) } : null,
      dataInfo: dataInfo
    };
  }

  function voyagerPlan(status, totalKm) {
    var probes = [];
    if (status && status.data) {
      [["voyager1", "航海家一號"], ["voyager2", "航海家二號"]].forEach(function (x) {
        var p = status.data.probes[x[0]];
        if (!p) return;
        var m = metric(totalKm, p.fromSunKm);
        probes.push({ id: x[0], name: x[1], fromSunKm: p.fromSunKm, fromEarthKm: p.fromEarthKm, pct: m.pct, remaining: m.remaining, overshoot: m.overshoot });
      });
    }
    return { id: "voyager", kind: "voyager", name: NAME.voyager, icon: "🛰️", href: "progress.html#voyager",
      desc: "參考追逐：以探測器目前距離為目標，你永遠追不上", current: totalKm, probes: probes, status: status,
      source: status && status.data && status.data.source || "NASA/JPL Horizons" };
  }

  // 全部五大挑戰（彼此獨立、同時累積）：太陽系、地球（含垂直極限子挑戰）、台灣、Voyager、共享成就中心。
  function buildPlans(tracks, voyager, opts) {
    opts = opts || {};
    var t = computeTotals(tracks);
    var goals = W.PROGRESS_CORE.buildGoals({ totalKm: t.totalKm, solar: W.SOLAR_LEVELS, earth: W.EARTH_LEVELS, taiwan: W.TAIWAN_LEVELS, voyager: voyager && voyager.data });
    var grouped = W.PROGRESS_CORE.groupByChallenge(goals);
    var info = { updatedAt: opts.updatedAt || null, now: opts.now != null ? opts.now : Date.now() };
    function g(name) { return grouped.map[name] || []; }

    var plans = [
      conquestPlan({ id: "solar", name: NAME.solar, icon: "🚀", href: "solar.html", desc: "用累積里程一路走向月球與各大行星" }, g(NAME.solar), t.totalKm, info),
      conquestPlan({ id: "earth", name: NAME.earth, icon: "🌍", href: "earth.html", desc: "環遊地球、虛擬遠征；子挑戰含「地球垂直極限挑戰」與軌道任務" }, g(NAME.earth), t.totalKm, info),
      conquestPlan({ id: "taiwan", name: NAME.taiwan, icon: "🇹🇼", href: "taiwan.html", desc: "台灣里程、環島、高山垂直與地理探索" }, g(NAME.taiwan), t.totalKm, info),
      voyagerPlan(voyager, t.totalKm)
    ];
    // 共享成就中心：彙整三大征服計畫已達成的目標；徽章、個人最佳等在競賽中心內頁（需讀取軌跡，不在這裡重算）。
    var conq = plans.slice(0, 3), dn = 0, tot = 0;
    conq.forEach(function (p) { dn += p.goalsDone; tot += p.goalsTotal; });
    plans.push({ id: "achievements", kind: "achievements", name: "共享成就中心", icon: "🏆", href: "challenges.html",
      desc: "競賽中心：成就徽章、個人最佳、期間挑戰、里程王、虛擬環遊、步數統計", goalsDone: dn, goalsTotal: tot, current: t.totalKm, dataInfo: info });
    return { totals: t, plans: plans, goals: goals, grouped: grouped };
  }

  // ───── 進度卡片 HTML（首頁與探索挑戰中心共用，視覺一致）─────
  function bar(pct, over) {
    return '<div class="gt-bar' + (over ? " over" : "") + '" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="' + barWidth(pct).toFixed(1) + '"><i style="width:' + barWidth(pct).toFixed(2) + '%"></i></div>';
  }
  function line(l, r) { return '<div class="gt-ch-line"><span>' + l + "</span><b>" + r + "</b></div>"; }
  function chip(text, tone) { return '<span class="gt-chip ' + (tone || "muted") + '">' + esc(text) + "</span>"; }
  function head(p) {
    return '<div class="gt-ch-head"><div class="gt-ch-icon" aria-hidden="true">' + p.icon + "</div><div><b>" + esc(p.name) + "</b><span>" + esc(p.desc) + "</span></div></div>";
  }
  function dataLine(info) {
    if (!info || !info.updatedAt) return '<div class="gt-ch-foot">里程資料更新時間：未知</div>';
    var age = info.now - info.updatedAt;
    return '<div class="gt-ch-foot">里程資料更新：' + esc(fmtDateTime(info.updatedAt)) + "（" + esc(fmtAge(age)) + "）" + (age > MANIFEST_STALE_MS ? " " + chip("可能非最新", "warn") : "") + "</div>";
  }

  function renderPlanCard(p, link) {
    link = link || function (h) { return h; };
    var href = link(p.href), body = "";
    if (p.kind === "conquest") {
      body += line("目前累積", fmtKm(p.current) + " km");
      if (p.rep) {
        var r = p.rep;
        body += '<div class="gt-ch-line"><span>' + (r.done ? "最高目標" : "目前目標") + "：<b>" + esc(r.name) + "</b>（" + fmtKm(r.target) + " km）</span><b>" + fmtPct(r.pct) + "</b></div>" + bar(r.pct, r.done);
        body += line(r.done ? "已全部達成" : "剩餘", r.done ? (r.overshoot > 0 ? "超標 +" + fmtKm(r.overshoot) + " km" : "—") : fmtKm(r.remaining) + " km");
      } else {
        body += '<div class="gt-ch-line"><span>尚無目標資料</span></div>';
      }
      if (p.lastDone && p.rep && !p.rep.done) body += '<div class="gt-ch-line"><span>已越過「' + esc(p.lastDone.name) + "」</span><b>超標 +" + fmtKm(p.lastDone.overshoot) + " km</b></div>";
      body += line("已達成目標", p.goalsDone + " / " + p.goalsTotal);
      body += dataLine(p.dataInfo);
    } else if (p.kind === "voyager") {
      var st = p.status;
      body += '<div class="gt-ch-line"><span>參考資料狀態</span>' + chip(st.state === "ok" ? "資料正常" : st.state === "stale" ? "資料過期" : st.state === "failed" ? "更新失敗" : "尚無資料", st.tone) + "</div>";
      if (p.probes.length) {
        p.probes.forEach(function (x) {
          body += '<div class="gt-ch-line"><span><b>' + esc(x.name) + "</b>・距太陽（日心）</span><b>" + fmtKm(x.fromSunKm) + " km</b></div>";
          if (x.fromEarthKm) body += '<div class="gt-ch-line"><span>距地球（地心）</span><span>' + fmtKm(x.fromEarthKm) + " km</span></div>";
          body += line("你的累積里程達其 " + fmtPct(x.pct), "剩餘 " + fmtKm(x.remaining) + " km") + bar(x.pct, false);
        });
        body += '<div class="gt-ch-foot">參考資料時間：' + esc(fmtDateTime(st.updatedAt)) + "（" + esc(fmtAge(st.ageMs)) + "）・來源 " + esc(p.source) + "</div>";
        if (st.state !== "ok") body += '<div class="gt-ch-foot warn">' + esc(st.label) + "</div>";
      } else {
        body += '<div class="gt-ch-foot warn">' + esc(st.label) + "</div>";
      }
    } else {
      body += line("目前累積", fmtKm(p.current) + " km");
      body += line("三大征服計畫已達成目標", p.goalsDone + " / " + p.goalsTotal);
      body += '<div class="gt-ch-foot">成就徽章、個人最佳等需讀取各筆軌跡，請在競賽中心查看。</div>';
    }
    return '<a class="gt-ch-card" data-plan="' + p.id + '" href="' + esc(href) + '">' + head(p) + body + '<div class="gt-ch-more">' + (p.kind === "voyager" ? "查看進度表 →" : "查看詳情 →") + "</div></a>";
  }

  W.GTCH = {
    NAME: NAME, VOYAGER_KEY: VOYAGER_KEY, VOYAGER_STALE_MS: VOYAGER_STALE_MS, MANIFEST_STALE_MS: MANIFEST_STALE_MS,
    esc: esc, fmtKm: fmtKm, fmtPct: fmtPct, barWidth: barWidth, fmtDateTime: fmtDateTime, fmtDay: fmtDay, fmtAge: fmtAge,
    computeTotals: computeTotals, validVoyager: validVoyager, loadVoyager: loadVoyager, voyagerStatus: voyagerStatus,
    buildPlans: buildPlans, renderPlanCard: renderPlanCard
  };
}).call(this);
