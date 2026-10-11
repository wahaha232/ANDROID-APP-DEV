// 地球征服計畫 — 關卡與門檻設定（唯一來源，統一管理；未來調整只需改這裡）。
// 所有 km 皆為「累積活動里程」的遊戲門檻，不代表真實地理環遊、登高或航太任務。
window.EARTH_LEVELS = {
  note: "以下門檻皆為遊戲用累積里程 / 虛擬任務，並非真實地理位置到訪或航太成就。",

  // 子挑戰 A：全球里程征服
  mileage: [
    { id: "m100", name: "地球新手", km: 100 },
    { id: "m1000", name: "城市征服者", km: 1000 },
    { id: "m10000", name: "大陸漫遊者", km: 10000 },
    { id: "m40075", name: "環遊地球一圈", km: 40075 },
    { id: "m100000", name: "世界旅行家", km: 100000 },
    { id: "m400750", name: "地球十圈", km: 400750 },
    { id: "m4007500", name: "地球百圈", km: 4007500 },
    { id: "m40075000", name: "地球傳說", km: 40075000 }
  ],

  // 子挑戰 B：世界探索任務（明確標示為「虛擬遠征」，以累積里程推進）
  explore: [
    { id: "europe", name: "歐洲虛擬遠征", km: 500 },
    { id: "asia", name: "亞洲虛擬遠征", km: 1000 },
    { id: "africa", name: "非洲虛擬遠征", km: 1500 },
    { id: "namerica", name: "北美洲虛擬遠征", km: 2000 },
    { id: "samerica", name: "南美洲虛擬遠征", km: 2500 },
    { id: "oceania", name: "大洋洲虛擬遠征", km: 3000 },
    { id: "antarctica", name: "南極遠征", km: 3500 },
    { id: "arctic", name: "北極遠征", km: 4000 },
    { id: "worldtour", name: "虛擬世界旅行", km: 40075 }
  ],
  // 距離賓果 / 隨機里程任務（依累積里程）
  exploreBingo: [
    { id: "b5", name: "暖身 5 km", km: 5 },
    { id: "b20", name: "小旅行 20 km", km: 20 },
    { id: "b50", name: "半百 50 km", km: 50 },
    { id: "b100", name: "百里 100 km", km: 100 },
    { id: "b200", name: "兩百 200 km", km: 200 },
    { id: "b500", name: "五百 500 km", km: 500 }
  ],

  // 子挑戰 C：地球垂直極限挑戰
  vertical: {
    // 真實參考值（集中設定）
    everestM: 8848.86,        // 珠穆朗瑪峰官方高度（≈ +8,848.86 m）
    challengerDeepM: -10935,  // 挑戰者深淵（≈ 海平面下 10,935 m，實測值依基準而異）
    // 虛擬征服模式：用累積里程解鎖（非真實垂直高度）
    tasks: [
      { id: "v-everest", name: "珠穆朗瑪峰巔峰挑戰", km: 88 },
      { id: "v-high", name: "高空探索挑戰", km: 300 },
      { id: "v-below", name: "海平面以下探索", km: 600 },
      { id: "v-mariana", name: "馬里亞納海溝挑戰", km: 900 },
      { id: "v-challenger", name: "挑戰者深淵", km: 1100 },
      { id: "v-extreme", name: "最高峰至最深海底垂直極限", km: 2000 }
    ]
  },

  // 子挑戰 D：地球軌道與逃逸任務（虛擬里程）
  orbit: [
    { id: "o-karman", name: "進入地球軌道（卡門線 100 km）", km: 100 },
    { id: "o-orbit", name: "地球軌道里程挑戰", km: 42000 },
    { id: "o-escape", name: "地球逃逸任務", km: 1000000 },
    { id: "o-deep", name: "深空探索準備任務", km: 5000000 }
  ]
};
