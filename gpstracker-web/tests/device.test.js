// 預設挑戰裝置的選擇規則：沒指定時排除 PC。
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

test("沒有指定裝置時挑選 PC 以外的最新一筆", () => {
  withDeviceEnv("", "", () => {
    const tracks = [
      { trackId: "phone", deviceName: "POCO X6 Pro 5G", startTimeMs: 1000 },
      { trackId: "pc", deviceName: "PC", startTimeMs: 9000 },
      { trackId: "old-phone", deviceName: "SONY XZ1", startTimeMs: 5000 },
    ];

    // PC 雖然最新，但預設要挑 PC 以外最新的一筆（SONY XZ1）。
    assert.equal(G.chooseDevice(tracks), "SONY XZ1");
  });
});

test("只有 PC 一台裝置時才退回 PC", () => {
  withDeviceEnv("", "", () => {
    const tracks = [{ trackId: "pc", deviceName: "PC", startTimeMs: 9000 }];

    assert.equal(G.chooseDevice(tracks), "PC");
  });
});

test("網址明確指定 PC 時照常顯示 PC", () => {
  withDeviceEnv("?device=PC", "", () => {
    const tracks = [
      { trackId: "phone", deviceName: "POCO X6 Pro 5G", startTimeMs: 1000 },
      { trackId: "pc", deviceName: "PC", startTimeMs: 9000 },
    ];

    assert.equal(G.chooseDevice(tracks), "PC");
  });
});

test("localStorage 已選 PC 時照常顯示 PC", () => {
  withDeviceEnv("", "PC", () => {
    const tracks = [
      { trackId: "phone", deviceName: "POCO X6 Pro 5G", startTimeMs: 1000 },
      { trackId: "pc", deviceName: "PC", startTimeMs: 9000 },
    ];

    assert.equal(G.chooseDevice(tracks), "PC");
  });
});

test("指定不存在的裝置時仍排除 PC", () => {
  withDeviceEnv("?device=NotExist", "", () => {
    const tracks = [
      { trackId: "phone", deviceName: "POCO X6 Pro 5G", startTimeMs: 1000 },
      { trackId: "pc", deviceName: "PC", startTimeMs: 9000 },
    ];

    assert.equal(G.chooseDevice(tracks), "POCO X6 Pro 5G");
  });
});
