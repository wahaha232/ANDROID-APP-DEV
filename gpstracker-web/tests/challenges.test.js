// 五大挑戰的純邏輯測試：同時累積、里程只計一次、進度可超過 100%、Voyager 失敗時保留最後有效值、空資料。
// 以 vm 載入既有的挑戰設定與核心檔（它們掛在 window 上），不修改任何穩定的核心檔。
// 執行：node --test gpstracker-web/tests/challenges.test.js   （Node 18+，無需安裝任何套件）
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const ROOT = path.join(__dirname, "..");
const ctx = vm.createContext({ console });
ctx.window = ctx;
for (const f of ["solar-levels.js", "solar-core.js", "earth-levels.js", "taiwan-levels.js", "progress-core.js", "gt-challenges.js"]) {
  vm.runInContext(fs.readFileSync(path.join(ROOT, f), "utf8"), ctx, { filename: f });
}
const G = ctx.GTCH, SC = ctx.SOLAR_CORE;
const J = (x) => JSON.parse(JSON.stringify(x)); // 跨 vm context 的物件先轉成純 JSON 再比較

const REAL_VOYAGER = JSON.parse(fs.readFileSync(path.join(ROOT, "data", "voyager.json"), "utf8"));
const NOW = REAL_VOYAGER.updatedAt + 3600e3; // 資料更新後 1 小時

// 一筆合理的活動：均速 20 km/h
function trk(id, km, over) {
  return Object.assign({ trackId: id, startTimeMs: 1700000000000 + km * 1000, distanceMeters: km * 1000, durationMs: km / 20 * 3600e3, stepCount: 100 }, over || {});
}
function memStorage(init) {
  const m = new Map(Object.entries(init || {}));
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), _m: m };
}
const okVoyager = G.voyagerStatus(REAL_VOYAGER, "live", null, NOW);
const plan = (res, id) => res.plans.find((p) => p.id === id);

test("五大挑戰同時存在且彼此獨立：太陽系、地球、台灣、Voyager、共享成就中心", () => {
  const res = G.buildPlans([trk("a", 10)], okVoyager, {});
  assert.deepEqual(J(res.plans.map((p) => p.id)), ["solar", "earth", "taiwan", "voyager", "achievements"]);
  assert.equal(new Set(res.plans.map((p) => p.href)).size, 5, "五個入口各自指向不同頁面");
  // 地球垂直極限挑戰是地球計畫的子挑戰，不是第六個頂層入口
  const earthIds = res.grouped.map["地球征服計畫"].map((g) => g.id);
  assert.ok(earthIds.some((id) => id.startsWith("ea-vertical:")), "地球計畫內含垂直極限挑戰");
  assert.ok(!res.plans.some((p) => /vertical|垂直/.test(p.id + p.name)), "垂直極限不是頂層入口");
  // 台灣、Voyager 各自獨立，沒有被併入地球／太陽系
  assert.ok(res.grouped.map["台灣征服計畫"].length > 0 && res.grouped.map["NASA 航海家追逐賽"].length > 0);
  assert.ok(!res.grouped.map["地球征服計畫"].some((g) => /tw-|voy:/.test(g.id)));
  assert.ok(!res.grouped.map["太陽系征服計畫"].some((g) => /voy:/.test(g.id)));
});

test("同時累積：同一份里程同時推進多個挑戰，不必完成前一關才開始下一關", () => {
  const res = G.buildPlans([trk("a", 41.17)], okVoyager, {});
  const t = plan(res, "taiwan"), e = plan(res, "earth"), s = plan(res, "solar");
  assert.equal(t.current, e.current); assert.equal(e.current, s.current);
  assert.ok(t.goalsDone >= 1, "台灣 10 km 關卡已達成");
  assert.ok(e.goalsDone >= 2, "地球 5 km、20 km 賓果已達成");
  assert.equal(s.goalsDone, 0, "太陽系第一關是月球（384,400 km），尚未達成，但不影響其他挑戰");
  // 新增一筆活動後，所有挑戰同步增加（不需要逐個手動更新）
  const res2 = G.buildPlans([trk("a", 41.17), trk("b", 100)], okVoyager, {});
  for (const id of ["solar", "earth", "taiwan"]) assert.ok(plan(res2, id).current > plan(res, id).current, id);
  assert.ok(plan(res2, "taiwan").goalsDone > t.goalsDone && plan(res2, "earth").goalsDone > e.goalsDone);
});

test("總里程每筆活動只計一次：重複 trackId、異常高速、缺時間都不重複／不計入", () => {
  const a = trk("a", 10), dup = Object.assign({}, a);
  const fast = trk("fast", 100, { durationMs: 3600e3 / 10 });   // 100 km / 0.1 h = 1000 km/h：未達異常門檻（1200），應計入
  const absurd = trk("absurd", 5000, { durationMs: 3600e3 });   // 5000 km/h：GPS 異常
  const nodur = trk("nodur", 7, { durationMs: 0 });
  const t = G.computeTotals([a, dup, fast, absurd, nodur]);
  assert.equal(t.recordCount, 5);
  assert.equal(t.validCount, 2, "只有 a 與 fast 有效");
  assert.equal(Math.round(t.totalKm * 1000) / 1000, 110);
  assert.equal(t.excluded, 3);
  assert.equal(t.totalKm, SC.compute([a, dup, fast, absurd, nodur]).totalKm, "與太陽系核心的計算一致（單一口徑）");
  // 各挑戰使用的都是這同一個總里程
  const res = G.buildPlans([a, dup, fast, absurd, nodur], okVoyager, {});
  assert.ok(res.plans.filter((p) => p.kind !== "voyager").every((p) => p.current === t.totalKm));
});

test("進度可超過 100%：數字不封頂，只有進度條填滿寬度封頂；剩餘與超標不會是負數", () => {
  assert.equal(G.fmtPct(250), "250%");
  assert.equal(G.fmtPct(120.04), "120%");
  assert.equal(G.fmtPct(99.96), "99.9%", "未達標不可顯示成 100%");
  assert.equal(G.fmtPct(0), "0%");
  assert.equal(G.fmtPct(0.001), "<0.01%");
  assert.equal(G.barWidth(250), 100); assert.equal(G.barWidth(-5), 0); assert.equal(G.barWidth(42), 42);

  const res = G.buildPlans([trk("big", 12000)], okVoyager, {});   // 12,000 km：超過台灣最高目標 10,000 km
  const tw = plan(res, "taiwan");
  assert.equal(tw.goalsDone, tw.goalsTotal);
  assert.equal(tw.rep.done, true);
  assert.equal(tw.rep.pct, 120, "百分比照實為 120%，沒有被封頂");
  assert.equal(tw.rep.overshoot, 2000);
  const html = G.renderPlanCard(tw);
  assert.ok(html.includes("120%") && html.includes("超標 +2,000 km"));
  assert.ok(html.includes("width:100.00%"), "進度條視覺填滿上限為 100%");
  for (const g of res.goals) { assert.ok(g.remaining >= 0 && g.overshoot >= 0, g.id); assert.ok(!(g.remaining > 0 && g.overshoot > 0), g.id); }
  // 部分達成時：顯示已越過的最高目標與超標里程
  const part = plan(G.buildPlans([trk("p", 400)], okVoyager, {}), "taiwan");
  assert.equal(part.rep.done, false);
  assert.equal(part.lastDone.target, 300);
  assert.equal(part.lastDone.overshoot, 100);
  assert.ok(G.renderPlanCard(part).includes("超標 +100 km"));
});

test("Voyager：讀取成功會保存最後有效值；之後更新失敗時保留並明確標示，不會歸零", async () => {
  const st = memStorage();
  const live = await G.loadVoyager(async () => REAL_VOYAGER, { storage: st, now: NOW });
  assert.equal(live.state, "ok"); assert.equal(live.source, "live");
  assert.ok(st.getItem(G.VOYAGER_KEY), "已保存最後一次有效資料");

  const failed = await G.loadVoyager(async () => { throw new Error("HTTP 503"); }, { storage: st, now: NOW + 3600e3 });
  assert.equal(failed.state, "failed"); assert.equal(failed.source, "cache");
  assert.equal(failed.updatedAt, REAL_VOYAGER.updatedAt, "顯示的是最後一次成功更新的時間");
  assert.equal(failed.data.probes.voyager1.fromSunKm, REAL_VOYAGER.probes.voyager1.fromSunKm, "保留最後一次有效數值");
  assert.ok(failed.label.includes("失敗") && failed.error.includes("503"));

  const p = plan(G.buildPlans([trk("a", 41.17)], failed, {}), "voyager");
  assert.equal(p.probes.length, 2);
  assert.ok(p.probes.every((x) => x.fromSunKm > 1e10 && x.pct > 0 && x.remaining > 0));
  const html = G.renderPlanCard(p);
  assert.ok(html.includes("更新失敗") && html.includes("參考資料時間") && html.includes("日心") && html.includes("地心"));
  assert.ok(!html.includes("NaN"));
});

test("Voyager：內容不合理（0、缺欄位）視為更新失敗，不覆蓋最後有效值，也不把進度重設為零", async () => {
  const st = memStorage();
  await G.loadVoyager(async () => REAL_VOYAGER, { storage: st, now: NOW });
  const zero = J(REAL_VOYAGER); zero.probes.voyager1.fromSunKm = 0; zero.probes.voyager2.fromSunKm = 0;
  for (const bad of [zero, { probes: {} }, null, { updatedAt: 1 }]) {
    const r = await G.loadVoyager(async () => bad, { storage: st, now: NOW });
    assert.equal(r.state, "failed");
    assert.equal(r.data.probes.voyager1.fromSunKm, REAL_VOYAGER.probes.voyager1.fromSunKm);
  }
  assert.equal(JSON.parse(st.getItem(G.VOYAGER_KEY)).probes.voyager1.fromSunKm, REAL_VOYAGER.probes.voyager1.fromSunKm, "保存的值沒有被壞資料覆蓋");
});

test("Voyager：超過 2 天沒更新標示過期；舊版（CDN 快取）不會冒充較新的資料；完全沒有資料時不顯示 0", async () => {
  const stale = await G.loadVoyager(async () => REAL_VOYAGER, { storage: memStorage(), now: REAL_VOYAGER.updatedAt + 3 * 86400e3 });
  assert.equal(stale.state, "stale"); assert.ok(stale.label.includes("過期"));
  const html = G.renderPlanCard(plan(G.buildPlans([trk("a", 5)], stale, {}), "voyager"));
  assert.ok(html.includes("資料過期") && html.includes("最後一次成功更新"));

  const newer = J(REAL_VOYAGER); newer.updatedAt += 86400e3; newer.probes.voyager1.fromSunKm += 1e6;
  const st = memStorage({ [G.VOYAGER_KEY]: JSON.stringify(newer) });
  const older = await G.loadVoyager(async () => REAL_VOYAGER, { storage: st, now: NOW + 86400e3 });
  assert.equal(older.updatedAt, newer.updatedAt, "採用較新的那一份");
  assert.equal(JSON.parse(st.getItem(G.VOYAGER_KEY)).updatedAt, newer.updatedAt, "舊資料不會覆蓋較新的保存值");

  const none = await G.loadVoyager(async () => { throw new Error("offline"); }, { storage: memStorage(), now: NOW });
  assert.equal(none.state, "none"); assert.equal(none.data, null);
  const p = plan(G.buildPlans([trk("a", 5)], none, {}), "voyager");
  assert.equal(p.probes.length, 0);
  const noneHtml = G.renderPlanCard(p);
  assert.ok(noneHtml.includes("尚無") && !/0 km|NaN/.test(noneHtml), "沒有資料就明說沒有，不顯示 0 或 NaN");
});

test("沒有活動資料：所有挑戰進度為 0、不出現 NaN；缺少 manifest 內容時也不會當掉", () => {
  for (const input of [[], undefined, null]) {
    const res = G.buildPlans(input, G.voyagerStatus(null, "none", "x", NOW), {});
    assert.equal(res.totals.totalKm, 0); assert.equal(res.totals.validCount, 0); assert.equal(res.totals.lastActivityMs, null);
    for (const p of res.plans) {
      assert.equal(p.current, 0);
      const html = G.renderPlanCard(p);
      assert.ok(!/NaN|undefined|Infinity/.test(html), p.id);
    }
    assert.equal(plan(res, "taiwan").goalsDone, 0);
  }
});

test("全站只有一個「有效活動」定義：首頁與競賽中心都使用 SOLAR_CORE.validTracks", () => {
  const read = (f) => fs.readFileSync(path.join(ROOT, f), "utf8");
  assert.match(read("challenges.js"), /SOLAR_CORE\.validTracks\(tracks\(\)\)/);
  assert.match(read("home.js"), /computeTotals|SOLAR_CORE\.validTracks/);
  assert.match(read("gt-challenges.js"), /W\.SOLAR_CORE\.validTracks/);
  for (const f of ["challenges.html", "index.html", "explore.html", "progress.html", "solar.html", "steps-journey.html"]) assert.match(read(f), /gt-challenges\.js/, f);
});

test("首頁的區塊順序：累積摘要 → 五大挑戰 → 最近活動；腳本載入順序正確", () => {
  const h = fs.readFileSync(path.join(ROOT, "index.html"), "utf8");
  const pos = (s) => h.indexOf(s);
  assert.ok(pos('id="stats-h"') < pos('id="ch-h"') && pos('id="ch-h"') < pos('id="hero-h"') && pos('id="hero-h"') < pos('id="tl-h"'));
  assert.ok(pos("progress-core.js") < pos("gt-challenges.js") && pos("gt-challenges.js") < pos("home.js"));
  assert.ok(pos("solar-core.js") < pos("gt-challenges.js"));
});
