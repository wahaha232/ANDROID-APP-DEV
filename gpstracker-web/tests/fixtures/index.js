// 共用測試 fixture（全為假資料，不含真實位置）：
// manifest.json、meta.json 與兩條短軌跡 fx-walk / fx-bike。
// 點格式 [lon, lat, alt, speed(km/h), elapsedSec]。由 Web 測試共用，App 端 JVM 測試可參考同樣的數值對拍。
const fs = require("node:fs");
const path = require("node:path");

const dir = __dirname;
const read = (f) => JSON.parse(fs.readFileSync(path.join(dir, f), "utf8"));

module.exports = {
  dir,
  manifest: () => read("manifest.json"),
  meta: () => read("meta.json"),
  track: (id) => read(id + ".json")
};
