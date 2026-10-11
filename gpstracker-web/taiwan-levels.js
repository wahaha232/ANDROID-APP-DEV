// 台灣征服計畫 — 關卡與地理設定（唯一來源）。所有 km 為「累積有效里程」遊戲門檻，非實際到訪。
// 地理邊界為「概略矩形（bounding box）」，僅供近似判定，非官方行政邊界。
window.TAIWAN_LEVELS = {
  note: "門檻為遊戲用累積里程；地理區域為概略矩形近似，非官方邊界。虛擬解鎖不代表實際到訪。",

  // A. 台灣里程征服
  mileage: [
    { id: "t10", name: "台灣新手", km: 10 },
    { id: "t50", name: "鄉鎮探索者", km: 50 },
    { id: "t100", name: "城市征服者", km: 100 },
    { id: "t300", name: "縣市漫遊者", km: 300 },
    { id: "t500", name: "台灣旅行家", km: 500 },
    { id: "t1000", name: "台灣里程達人", km: 1000 },
    { id: "t3000", name: "台灣征服者", km: 3000 },
    { id: "t10000", name: "台灣傳說", km: 10000 }
  ],

  // B. 台灣環島挑戰（虛擬）
  loop: [
    { id: "l100", name: "環島起步", km: 100 },
    { id: "l300", name: "環島旅程進行中", km: 300 },
    { id: "l500", name: "半程里程挑戰", km: 500 },
    { id: "l1000", name: "環島里程達人", km: 1000 },
    { id: "l3000", name: "環島三千里", km: 3000 },
    { id: "l10000", name: "環島傳說", km: 10000 }
  ],

  // C. 台灣地理探索 — 概略矩形（s/w/n/e 為緯度/經度）
  regions: [
    { id: "north", name: "北台灣", box: { s: 24.6, n: 25.4, w: 121.0, e: 122.1 } },
    { id: "central", name: "中台灣", box: { s: 23.8, n: 24.6, w: 120.0, e: 121.5 } },
    { id: "south", name: "南台灣", box: { s: 21.8, n: 23.8, w: 120.0, e: 121.2 } },
    { id: "east", name: "東台灣", box: { s: 22.0, n: 25.0, w: 121.2, e: 122.1 } },
    { id: "westcoast", name: "西部沿海", box: { s: 22.0, n: 25.3, w: 120.0, e: 120.35 } },
    { id: "rift", name: "東部縱谷", box: { s: 22.8, n: 24.0, w: 121.1, e: 121.5 } },
    { id: "penghu", name: "澎湖", box: { s: 23.2, n: 23.8, w: 119.4, e: 119.8 } },
    { id: "kinmen", name: "金門", box: { s: 24.3, n: 24.6, w: 118.2, e: 118.5 } },
    { id: "matsu", name: "馬祖", box: { s: 25.9, n: 26.4, w: 119.9, e: 120.1 } },
    { id: "greenisland", name: "綠島", box: { s: 22.6, n: 22.7, w: 121.4, e: 121.5 } },
    { id: "lantau", name: "蘭嶼", box: { s: 22.0, n: 22.1, w: 121.5, e: 121.6 } }
  ],
  // 縱向 / 橫向跨距門檻（度，約 1° ≈ 111 km）
  span: { northSouthDeg: 1.0, eastWestDeg: 0.5 },

  // E. 台灣高山與垂直挑戰
  vertical: {
    yushanM: 3952, // 玉山主峰高度（參考值）
    tasks: [
      { id: "v-yushan", name: "玉山主峰高度挑戰（虛擬）", km: 40 },
      { id: "v-highest", name: "台灣最高峰高度挑戰（虛擬）", km: 100 },
      { id: "v-baiyue", name: "台灣百岳虛擬征服", km: 300 },
      { id: "v-ascent", name: "高山累積爬升挑戰（虛擬）", km: 600 },
      { id: "v-mountain", name: "山岳探索成就", km: 1000 }
    ]
  },

  // F. 台灣四極點挑戰（概略座標，半徑內視為到訪；座標待人工核實）
  extremes: [
    { id: "x-north", name: "台灣本島最北端（富貴角）", lon: 121.537, lat: 25.298, radiusKm: 3 },
    { id: "x-south", name: "台灣本島最南端（鵝鑾鼻）", lon: 120.858, lat: 21.897, radiusKm: 3 },
    { id: "x-east", name: "台灣本島最東端（三貂角）", lon: 122.003, lat: 25.008, radiusKm: 3 },
    { id: "x-west", name: "台灣本島最西端（國聖港燈塔）", lon: 120.035, lat: 23.100, radiusKm: 3 }
  ],
  extremesNote: "四極點座標為概略值（±約 1–2 km），非官方測量；判定採半徑 3 km，僅供參考，待人工核實。",

  // 縣市制霸：需外部縣市邊界 GeoJSON（data/taiwan-counties.json）
  countiesFile: "taiwan-counties.json"
};
