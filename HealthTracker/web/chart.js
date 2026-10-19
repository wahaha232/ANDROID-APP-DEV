// 趨勢圖：幾何與刻度的純函式（可用 node 測試）＋ SVG 繪製。
// 色彩依 dataviz 規格：系列色用 CSS 變數（亮／暗各自選色，已用驗證腳本檢查），文字用文字色，不用系列色。
import { TYPES, formatValue, formatTime } from './lib.js';

const DAY_MS = 86400000;
const TAIPEI_MS = 8 * 3600000;

/** 把 [min,max] 擴成漂亮的刻度（1/2/5 × 10^n）。 */
export function niceScale(min, max, count = 5) {
  if (!Number.isFinite(min) || !Number.isFinite(max)) return { min: 0, max: 1, ticks: [0, 1] };
  if (min === max) { min -= 1; max += 1; }
  const span = max - min;
  const rough = span / Math.max(1, count - 1);
  const pow = 10 ** Math.floor(Math.log10(rough));
  const frac = rough / pow;
  const step = (frac <= 1 ? 1 : frac <= 2 ? 2 : frac <= 5 ? 5 : 10) * pow;
  const lo = Math.floor(min / step) * step;
  const hi = Math.ceil(max / step) * step;
  const ticks = [];
  for (let v = lo; v <= hi + step / 2; v += step) ticks.push(Number(v.toFixed(10)));
  return { min: Number(lo.toFixed(10)), max: Number(hi.toFixed(10)), ticks };
}

/** 依類型把紀錄（新到舊）整理成「舊到新」的系列。血壓有兩條線。 */
export function buildSeries(type, records) {
  const asc = [...records].sort((a, b) => a.measured_at - b.measured_at);
  if (type === 'BLOOD_PRESSURE') {
    return [
      { key: 'sys', label: '收縮壓', points: asc.map((r) => ({ t: r.measured_at, v: r.value, rec: r })) },
      { key: 'dia', label: '舒張壓', points: asc.filter((r) => r.value2 != null).map((r) => ({ t: r.measured_at, v: r.value2, rec: r })) },
    ];
  }
  return [{ key: 'main', label: TYPES[type]?.label ?? type, points: asc.map((r) => ({ t: r.measured_at, v: r.value, rec: r })) }];
}

/** X 軸刻度：落在台北時間的 0 點，間隔挑 1/2/7/14/30/60/90/180/365 天中讓刻度數約 6 的那個。 */
export function timeTicks(minT, maxT, target = 6) {
  const span = Math.max(DAY_MS, maxT - minT);
  const steps = [1, 2, 7, 14, 30, 60, 90, 180, 365].map((d) => d * DAY_MS);
  const step = steps.find((s) => span / s <= target + 1) ?? steps[steps.length - 1];
  const startLocal = Math.ceil((minT + TAIPEI_MS) / DAY_MS) * DAY_MS; // 台北當地午夜
  const ticks = [];
  for (let t = startLocal; t - TAIPEI_MS <= maxT; t += step) ticks.push(t - TAIPEI_MS);
  return ticks;
}

export function formatTick(t) {
  return formatTime(t).slice(5, 10).replace('-', '/'); // MM/DD
}

/** 回傳座標轉換函式與刻度。points 全部系列合併後取範圍。 */
export function layout(series, { width, height, margin }) {
  const all = series.flatMap((s) => s.points);
  const times = all.map((p) => p.t);
  const minT = Math.min(...times);
  const maxT = Math.max(...times);
  const y = niceScale(Math.min(...all.map((p) => p.v)), Math.max(...all.map((p) => p.v)));
  const innerW = width - margin.left - margin.right;
  const innerH = height - margin.top - margin.bottom;
  const tSpan = Math.max(1, maxT - minT);
  return {
    minT, maxT, y, innerW, innerH,
    x: (t) => margin.left + (maxT === minT ? innerW / 2 : ((t - minT) / tSpan) * innerW),
    yPos: (v) => margin.top + innerH - ((v - y.min) / (y.max - y.min)) * innerH,
    xTicks: timeTicks(minT, maxT),
  };
}

/** 找出離 px 最近的時間點索引（xs 由小到大）。 */
export function nearestIndex(xs, px) {
  let best = 0;
  for (let i = 1; i < xs.length; i++) if (Math.abs(xs[i] - px) < Math.abs(xs[best] - px)) best = i;
  return best;
}

// ---------- 繪製 ----------
const NS = 'http://www.w3.org/2000/svg';
const W = 720;
const H = 300;
const MARGIN = { top: 16, right: 64, bottom: 28, left: 48 };

function el(tag, attrs = {}, text) {
  const n = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, String(v));
  if (text != null) n.textContent = text;
  return n;
}

/** 在 container 畫圖；沒有資料就清空。 */
export function renderChart(container, tooltipEl, type, records) {
  container.replaceChildren();
  tooltipEl.hidden = true;
  const series = buildSeries(type, records).filter((s) => s.points.length);
  if (!series.length) return;

  const t = TYPES[type];
  const g = layout(series, { width: W, height: H, margin: MARGIN });
  const svg = el('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': `${t.label}趨勢圖，共 ${records.length} 筆；完整數值見下方表格` });

  // 格線與 Y 軸刻度（髮絲、實線、低調）
  for (const tick of g.y.ticks) {
    const y = g.yPos(tick);
    svg.append(el('line', { x1: MARGIN.left, x2: W - MARGIN.right, y1: y, y2: y, class: 'grid' }));
    svg.append(el('text', { x: MARGIN.left - 8, y: y + 4, class: 'axis', 'text-anchor': 'end' }, String(tick)));
  }
  for (const tt of g.xTicks) {
    svg.append(el('text', { x: g.x(tt), y: H - 8, class: 'axis', 'text-anchor': 'middle' }, formatTick(tt)));
  }

  // 系列：2px 線；點數不多才畫點（含 2px 底色環），點多只標最後一點
  series.forEach((s, i) => {
    const cls = `s${i + 1}`;
    const d = s.points.map((p, k) => `${k ? 'L' : 'M'}${g.x(p.t).toFixed(1)},${g.yPos(p.v).toFixed(1)}`).join('');
    if (s.points.length > 1) svg.append(el('path', { d, class: `line ${cls}` }));
    const showAll = s.points.length <= 60;
    s.points.forEach((p, k) => {
      if (showAll || k === s.points.length - 1) svg.append(el('circle', { cx: g.x(p.t), cy: g.yPos(p.v), r: 4, class: `dot ${cls}` }));
    });
    const last = s.points[s.points.length - 1];
    // 直接標示：只標最後一筆（文字用文字色，色彩由旁邊的線段短鍵承擔）
    svg.append(el('line', { x1: W - MARGIN.right + 6, x2: W - MARGIN.right + 16, y1: g.yPos(last.v), y2: g.yPos(last.v), class: `key ${cls}` }));
    svg.append(el('text', { x: W - MARGIN.right + 20, y: g.yPos(last.v) + 4, class: 'endlabel' }, String(Number.isInteger(last.v) ? last.v : last.v.toFixed(t.digits))));
  });

  // 十字線與互動層
  const cross = el('line', { y1: MARGIN.top, y2: H - MARGIN.bottom, class: 'cross', visibility: 'hidden' });
  svg.append(cross);
  const hit = el('rect', { x: MARGIN.left, y: MARGIN.top, width: g.innerW, height: g.innerH, class: 'hit', tabindex: 0, role: 'application', 'aria-label': '方向鍵左右移動可查看每筆數值' });
  svg.append(hit);

  // 所有時間點（去重、排序），十字線對齊最近的時間
  const times = [...new Set(series.flatMap((s) => s.points.map((p) => p.t)))].sort((a, b) => a - b);
  const xs = times.map(g.x);
  let current = times.length - 1;

  const showAt = (idx) => {
    current = Math.max(0, Math.min(times.length - 1, idx));
    const tm = times[current];
    cross.setAttribute('x1', xs[current]);
    cross.setAttribute('x2', xs[current]);
    cross.setAttribute('visibility', 'visible');
    tooltipEl.replaceChildren();
    const head = document.createElement('div');
    head.className = 'tt-time';
    head.textContent = formatTime(tm);
    tooltipEl.append(head);
    series.forEach((s, i) => {
      const p = s.points.find((q) => q.t === tm);
      if (!p) return;
      const row = document.createElement('div');
      row.className = 'tt-row';
      const key = document.createElement('span');
      key.className = `tt-key s${i + 1}`;
      const val = document.createElement('b');
      val.textContent = series.length > 1 ? `${p.v}` : formatValue(p.rec);
      row.append(key, val);
      if (series.length > 1) {
        const name = document.createElement('span');
        name.className = 'tt-name';
        name.textContent = ` ${s.label}`;
        row.append(name);
      }
      tooltipEl.append(row);
    });
    tooltipEl.hidden = false;
    const box = container.getBoundingClientRect();
    const px = (xs[current] / W) * box.width;
    tooltipEl.style.left = `${Math.min(Math.max(px + 12, 0), Math.max(0, box.width - 150))}px`;
  };

  hit.addEventListener('pointermove', (e) => {
    const box = svg.getBoundingClientRect();
    showAt(nearestIndex(xs, ((e.clientX - box.left) / box.width) * W));
  });
  hit.addEventListener('pointerleave', () => { cross.setAttribute('visibility', 'hidden'); tooltipEl.hidden = true; });
  hit.addEventListener('focus', () => showAt(current));
  hit.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowLeft') { e.preventDefault(); showAt(current - 1); }
    if (e.key === 'ArrowRight') { e.preventDefault(); showAt(current + 1); }
  });

  // 圖例：兩條線以上才有（單一系列由標題說明）
  if (series.length > 1) {
    const legend = document.createElement('div');
    legend.className = 'legend';
    series.forEach((s, i) => {
      const item = document.createElement('span');
      const key = document.createElement('span');
      key.className = `tt-key s${i + 1}`;
      item.append(key, ` ${s.label}`);
      legend.append(item);
    });
    container.append(legend);
  }
  container.append(svg);
}
