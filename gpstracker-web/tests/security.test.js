// 執行：node --test gpstracker-web/tests/security.test.js
// 網址參數與外部資料的防護：不能讓被竄改的 manifest／meta／網址把 HTML 或路徑帶進頁面。
const test = require("node:test");
const assert = require("node:assert/strict");
const G = require("../gt-core.js");

global.window = global.window || {};
global.location = global.location || { search: "" };
require("../common.js");
const resolveConfig = global.window.GPST.resolveConfig;

test("未知的模式名稱：只放行單純字詞，HTML 一律顯示「未知」", () => {
  assert.equal(G.modeName("WALKING"), G.modeName("WALKING"));
  assert.notEqual(G.modeName("WALKING"), "未知");
  assert.equal(G.modeName("SOME_NEW_MODE"), "SOME_NEW_MODE");
  assert.equal(G.modeName('<img src=x onerror=alert(1)>'), "未知");
  assert.equal(G.modeName('"><script>'), "未知");
  assert.equal(G.modeName(""), "未知");
  assert.equal(G.modeName(null), "未知");
});

test("escapeHtml 會跳脫尖括號與引號", () => {
  assert.equal(G.escapeHtml('<a href="x">&\''), "&lt;a href=&quot;x&quot;&gt;&amp;&#39;");
});

test("resolveConfig：正常的 repo／branch／data 照用", () => {
  const c = resolveConfig("?repo=wahaha232/ANDROID-APP-DEV&branch=main&data=gpstracker-web/data");
  assert.equal(c.repo, "wahaha232/ANDROID-APP-DEV");
  assert.equal(c.dataBase, "https://raw.githubusercontent.com/wahaha232/ANDROID-APP-DEV/main/gpstracker-web/data/");
});

test("resolveConfig：格式不對一律退回預設，不會被帶去別的路徑", () => {
  for (const bad of ["?repo=../x", "?repo=a/b/c", "?repo=x", "?repo=a%2F..%2Fb"]) {
    assert.equal(resolveConfig(bad).repo, "", bad);
  }
  assert.equal(resolveConfig("?branch=../../etc").branch, "main");
  assert.equal(resolveConfig("?branch=a b").branch, "main");
  assert.equal(resolveConfig("?data=../secret").dataPath, "data");
  assert.equal(resolveConfig("?data=/abs").dataPath, "data");
  assert.equal(resolveConfig("?data=a%3Cb").dataPath, "data");
  assert.equal(resolveConfig("").dataBase, "data/");
});
