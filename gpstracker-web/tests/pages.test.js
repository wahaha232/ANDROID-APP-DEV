// 靜態頁面檢查：每頁都能回首頁、分享預覽 meta、noscript、skip link、白字按鈕對比。
// 執行：node --test gpstracker-web/tests/pages.test.js   （Node 18+，無需安裝任何套件）
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");
const pages = fs.readdirSync(ROOT).filter((f) => f.endsWith(".html"));
const read = (f) => fs.readFileSync(path.join(ROOT, f), "utf8");
const APP = "flythrough.html"; // 全螢幕 3D 工作區：不使用一般頁首與 skip link

test("有 13 個頁面可檢查", () => assert.equal(pages.length, 13));

test("每一頁都有回首頁的連結（指向 index.html）", () => {
  for (const p of pages) {
    if (p === "index.html") continue;
    assert.match(read(p), /<a [^>]*href="index\.html"[^>]*>/, p + " 缺少回首頁連結");
  }
});

test("每頁都有 description、canonical 與完整的分享預覽 meta", () => {
  for (const p of pages) {
    const h = read(p);
    for (const re of [/<meta name="description" content="[^"]+"/, /<link rel="canonical" href="https:\/\/[^"]+"/,
      /<meta property="og:title" content="[^"]+"/, /<meta property="og:description" content="[^"]+"/,
      /<meta property="og:url" content="https:\/\/[^"]+"/, /<meta property="og:image" content="https:\/\/[^"]+og-cover\.png"/,
      /<meta name="twitter:card" content="summary_large_image"/]) assert.match(h, re, p + " 缺少 " + re);
    // canonical 與 og:url 必須一致
    assert.equal(h.match(/rel="canonical" href="([^"]+)"/)[1], h.match(/og:url" content="([^"]+)"/)[1], p);
  }
});

test("og-cover.png 存在且為 1200x630 的 PNG", () => {
  const b = fs.readFileSync(path.join(ROOT, "og-cover.png"));
  assert.equal(b.slice(1, 4).toString(), "PNG");
  assert.equal(b.readUInt32BE(16), 1200);
  assert.equal(b.readUInt32BE(20), 630);
});

test("每頁都有 <noscript> 提示；一般頁面有 skip link 且指向存在的 #main", () => {
  for (const p of pages) {
    const h = read(p);
    assert.match(h, /<noscript>/, p + " 缺少 noscript");
    if (p === APP) continue;
    assert.match(h, /<a class="gt-skip" href="#main">/, p + " 缺少 skip link");
    assert.match(h, /<main [^>]*id="main"/, p + " 的 main 沒有 id=main");
  }
});

function lum(rgb) {
  const c = rgb.map((v) => v / 255).map((v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}
const hex = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
const ratio = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };

test("白字按鈕漸層（--grad-btn）兩端含 hover 亮度 +10% 後，對白字的對比都 ≥ 4.5:1", () => {
  const m = read("style.css").match(/--grad-btn: linear-gradient\(135deg, (#[0-9a-f]{6}), (#[0-9a-f]{6})\)/i);
  assert.ok(m, "找不到 --grad-btn");
  for (const c of [m[1], m[2]]) {
    assert.ok(ratio([255, 255, 255], hex(c)) >= 4.5, c + " 對比不足");
    assert.ok(ratio([255, 255, 255], hex(c).map((v) => Math.min(255, v * 1.1))) >= 4.5, c + " hover 對比不足");
  }
});

test("白字 + 漸層的元件都用 --grad-btn，而不是對比不足的 --grad", () => {
  for (const f of fs.readdirSync(ROOT).filter((x) => x.endsWith(".css"))) {
    for (const line of read(f).split(/\r?\n/)) {
      if (/background:\s*var\(--grad\)/.test(line) && /color:\s*#fff/.test(line)) assert.fail(f + "：" + line.trim());
    }
  }
});
