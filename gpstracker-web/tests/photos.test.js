// 照片縮圖的回放邏輯測試。執行：node --test gpstracker-web/tests/photos.test.js
const test = require("node:test");
const assert = require("node:assert/strict");
const G = require("../gt-core.js");

// 100 個點，每 10 秒一個，進度均勻（cumFrac[i] = i/99）
const points = Array.from({ length: 100 }, (_, i) => [121.5 + i * 0.0001, 25, 0, 10, i * 10]);
const cumFrac = points.map((_, i) => i / 99);

test("路徑檢查：只接受 photos/ 底下的相對路徑，擋掉 ..、絕對路徑、網址與奇怪字元", () => {
  assert.equal(G.isPhotoPath("photos/abc/1.jpg"), true);
  assert.equal(G.isPhotoPath("photos/2026-10-10_abc/12.jpg"), true);
  for (const bad of ["../secret.json", "photos/../x.jpg", "photos/a/../../x.jpg", "photos//x.jpg", "/etc/passwd", "https://evil.example/x.jpg",
    "photos/a b.jpg", "photos/<script>.jpg", "photos/x.jpg?x=1", "", null, undefined, 5, ["photos/x.jpg"]]) {
    assert.equal(G.isPhotoPath(bad), false, String(bad));
  }
});

test("拍照點的進度：依拍照時間對應到軌跡點；沒有縮圖的照片不列入；依進度排序、保留序號", () => {
  const photos = [[121.51, 25, 500, "photos/t/b.jpg"], [121.5, 25, 100], [121.502, 25, 200, "photos/t/a.jpg"]];
  const m = G.photoMarks(points, photos, cumFrac);
  assert.equal(m.length, 2, "第 2 張沒有縮圖路徑，不列入");
  assert.deepEqual(m.map((x) => x.n), [3, 1], "依進度排序，n 保留原序號");
  assert.ok(Math.abs(m[0].frac - 20 / 99) < 1e-9 && Math.abs(m[1].frac - 50 / 99) < 1e-9);
  assert.equal(m[0].path, "photos/t/a.jpg");
  assert.deepEqual(G.photoMarks([], photos, cumFrac), []);
  assert.deepEqual(G.photoMarks(points, undefined, cumFrac), []);
  assert.equal(G.photoMarks(points, [[0, 0, 99999, "photos/x.jpg"]], cumFrac)[0].frac, 1, "拍照時間超過軌跡結束 → 對應到終點");
});

test("正常播放（小步前進）經過拍照點才彈出，每張只彈一次", () => {
  const m = [{ frac: 0.2 }, { frac: 0.5 }];
  let next = 0, prev = 0, shown = [];
  for (let f = 0.005; f <= 1.0001; f += 0.005) {
    const r = G.photoStep(m, next, prev, f, true);
    shown.push(...r.show.map((x) => x.frac)); next = r.next; prev = f;
    assert.equal(r.reset, false);
  }
  assert.deepEqual(shown, [0.2, 0.5]);
});

test("拖曳／重播／倒退（大幅變動）不連續彈出，只重設指標並要求收起", () => {
  const m = [{ frac: 0.2 }, { frac: 0.5 }, { frac: 0.8 }];
  let r = G.photoStep(m, 0, 0, 0.9, true);              // 一次跳過全部
  assert.deepEqual(r, { show: [], next: 3, reset: true });
  r = G.photoStep(m, 3, 0.9, 0.1, true);                 // 倒退（重播）
  assert.deepEqual(r, { show: [], next: 0, reset: true });
  r = G.photoStep(m, 0, 0.1, 0.55, true);                // 拖到中間
  assert.equal(r.next, 2); assert.deepEqual(r.show, []);
});

test("不是回放模式（自由探索、數據模式）不彈出，但指標會跟著走（切回回放不會一次彈出一堆）", () => {
  const m = [{ frac: 0.2 }, { frac: 0.5 }];
  const r = G.photoStep(m, 0, 0.19, 0.21, false);
  assert.deepEqual(r, { show: [], next: 1, reset: false });
});

test("沒有照片標記時什麼都不做", () => {
  assert.deepEqual(G.photoStep([], 0, 0.49, 0.5, true), { show: [], next: 0, reset: false });
});
