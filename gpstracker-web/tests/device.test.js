// 預設裝置的選擇規則：沒指定時排除「只有模擬紀錄」的裝置。
const test = require("node:test");
const assert = require("node:assert/strict");
const G = require("../gt-core.js");

function withDeviceEnv(search, stored, run) {
  const oldLocation = globalThis.location;
  const oldStorage = globalThis.localStorage;
  const store = new Map(stored ? [[G.DEVICE_KEY, stored]] : []);

  globalThis.location = { search: search || "" };
  globalThis.localStorage = {
    getItem: (key) => (store.has(key) ? store.get(key) : null),
    setItem: (key, value) => { store.set(key, value); },
    removeItem: (key) => { store.delete(key); },
  };

  try {
    run(store);
  } finally {
    if (oldLocation === undefined) delete globalThis.location;
    else globalThis.location = oldLocation;

    if (oldStorage === undefined) delete globalThis.localStorage;
    else globalThis.localStorage = oldStorage;
  }
}

test("沒有指定裝置時排除只有模擬紀錄的 PC 與 NB", () => {
  withDeviceEnv("", "", () => {
    const tracks = [
      { trackId: "phone-old", deviceName: "POCO X6 Pro 5G", startTimeMs: 1000 },
      { trackId: "phone", deviceName: "SONY XZ1", startTimeMs: 5000 },
      { trackId: "pc", deviceName: "PC", source: "simulated", startTimeMs: 9000 },
      { trackId: "nb", deviceName: "NB", source: "simulated", startTimeMs: 10000 },
    ];

    assert.equal(G.chooseDevice(tracks), "SONY XZ1");
  });
});

test("有非模擬紀錄的裝置優先，即使模擬紀錄更新", () => {
  withDeviceEnv("", "", () => {
    const tracks = [
      { trackId: "phone", deviceName: "POCO X6 Pro 5G", startTimeMs: 5000 },
      { trackId: "phone-sim", deviceName: "POCO X6 Pro 5G", source: "simulated", startTimeMs: 12000 },
      { trackId: "pc", deviceName: "PC", source: "simulated", startTimeMs: 9000 },
      { trackId: "nb", deviceName: "NB", source: "simulated", startTimeMs: 10000 },
    ];

    // POCO 有非模擬紀錄，所以它在候選名單內；NB 只有模擬紀錄，不搶預設。
    assert.equal(G.chooseDevice(tracks), "POCO X6 Pro 5G");
  });
});

test("全部都是模擬裝置時退回最新一筆", () => {
  withDeviceEnv("", "", () => {
    const tracks = [
      { trackId: "pc", deviceName: "PC", source: "simulated", startTimeMs: 9000 },
      { trackId: "nb", deviceName: "NB", source: "simulated", startTimeMs: 10000 },
    ];

    assert.equal(G.chooseDevice(tracks), "NB");
  });
});

test("網址明確指定 PC 時照常顯示 PC", () => {
  withDeviceEnv("?device=PC", "", () => {
    const tracks = [
      { trackId: "phone", deviceName: "POCO X6 Pro 5G", startTimeMs: 5000 },
      { trackId: "pc", deviceName: "PC", source: "simulated", startTimeMs: 9000 },
      { trackId: "nb", deviceName: "NB", source: "simulated", startTimeMs: 10000 },
    ];

    assert.equal(G.chooseDevice(tracks), "PC");
  });
});

test("localStorage 已選 PC 時照常顯示 PC", () => {
  withDeviceEnv("", "PC", () => {
    const tracks = [
      { trackId: "phone", deviceName: "POCO X6 Pro 5G", startTimeMs: 5000 },
      { trackId: "pc", deviceName: "PC", source: "simulated", startTimeMs: 9000 },
    ];

    assert.equal(G.chooseDevice(tracks), "PC");
  });
});

test("localStorage 已選 NB 時照常顯示 NB", () => {
  withDeviceEnv("", "NB", () => {
    const tracks = [
      { trackId: "pc", deviceName: "PC", source: "simulated", startTimeMs: 9000 },
      { trackId: "nb", deviceName: "NB", source: "simulated", startTimeMs: 10000 },
    ];

    assert.equal(G.chooseDevice(tracks), "NB");
  });
});

test("指定不存在的裝置時仍排除只有模擬紀錄的裝置", () => {
  withDeviceEnv("?device=NotExist", "", () => {
    const tracks = [
      { trackId: "phone", deviceName: "POCO X6 Pro 5G", startTimeMs: 1000 },
      { trackId: "pc", deviceName: "PC", source: "simulated", startTimeMs: 9000 },
      { trackId: "nb", deviceName: "NB", source: "simulated", startTimeMs: 10000 },
    ];

    assert.equal(G.chooseDevice(tracks), "POCO X6 Pro 5G");
  });
});

test("累積活動摘要依裝置分組：不同裝置的紀錄不會混在同一組", () => {
  const tracks = [
    { trackId: "poco-1", deviceName: "POCO X6 Pro 5G", startTimeMs: 1000, distanceMeters: 1000 },
    { trackId: "sony-1", deviceName: "SONY XZ1", startTimeMs: 3000, distanceMeters: 2000 },
    { trackId: "poco-2", deviceName: "POCO X6 Pro 5G", startTimeMs: 5000, distanceMeters: 3000 },
    { trackId: "legacy", startTimeMs: 2000, distanceMeters: 500 },
  ];

  const groups = G.groupTracksByDevice(tracks);

  assert.deepEqual(groups.map((g) => g.device), ["POCO X6 Pro 5G", "SONY XZ1", "未命名裝置"]);
  assert.deepEqual(groups.map((g) => g.tracks.length), [2, 1, 1]);
  assert.deepEqual(groups[0].tracks.map((t) => t.trackId), ["poco-1", "poco-2"]);
  assert.equal(groups[0].tracks.reduce((sum, t) => sum + t.distanceMeters, 0), 4000);
  assert.equal(groups[1].tracks.reduce((sum, t) => sum + t.distanceMeters, 0), 2000);
});
