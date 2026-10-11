// 太陽系征服計畫 — 關卡設定（唯一來源，統一管理門檻；未來調整只需改這裡）。
// 門檻為「累積里程」的里程碑，單位 km，採單調遞增。
// 數值參考各天體的平均軌道半徑（由太陽起算；月球為地月平均距離），
// 這是遊戲里程碑，不是實際航線。
window.SOLAR_LEVELS = {
  auKm: 149597870.7,
  note: "關卡門檻＝各天體實際參考距離（平均軌道半徑／地月平均距離），僅作為累積里程里程碑，非實際航線。",
  levels: [
    { id: "moon",    zh: "月球",   icon: "🌕", display: "🌕 月球",   km: 384400,     desc: "地球的天然衛星（地月平均距離）" },
    { id: "mercury", zh: "水星",   icon: "☿",  display: "☿ 水星",    km: 57900000,   desc: "最靠近太陽的行星（平均日距）" },
    { id: "venus",   zh: "金星",   icon: "♀",  display: "♀ 金星",    km: 108200000,  desc: "地球的鄰居（平均日距）" },
    { id: "earth",   zh: "地球軌道", icon: "🌍", display: "🌍 地球軌道", km: 149600000,  desc: "地球繞日軌道尺度挑戰（平均日距）" },
    { id: "mars",    zh: "火星",   icon: "♂",  display: "♂ 火星",    km: 227900000,  desc: "紅色行星（平均日距）" },
    { id: "jupiter", zh: "木星",   icon: "♃",  display: "♃ 木星",    km: 778500000,  desc: "太陽系最大行星（平均日距）" },
    { id: "saturn",  zh: "土星",   icon: "♄",  display: "♄ 土星",    km: 1434000000, desc: "光環行星（平均日距）" },
    { id: "uranus",  zh: "天王星", icon: "⛢",  display: "⛢ 天王星",  km: 2871000000, desc: "冰巨行星（平均日距）" },
    { id: "neptune", zh: "海王星", icon: "♆",  display: "♆ 海王星",  km: 4495000000, desc: "最遠的行星（平均日距）" },
    { id: "pluto",   zh: "冥王星", icon: "🪐", display: "🪐 冥王星",  km: 5906000000, desc: "隱藏關卡：矮行星（平均日距）", hidden: true }
  ],
  // 追逐關卡：資料由 data/voyager.json 提供（NASA/JPL Horizons），玩家永遠追不上、差距會持續變大。
  voyagers: [
    { id: "voyager1", icon: "🛰️", display: "🛰️ 航海家一號", desc: "1977 年發射，已進入星際空間" },
    { id: "voyager2", icon: "🛰️", display: "🛰️ 航海家二號", desc: "1977 年發射，飛越四顆巨行星" }
  ]
};
