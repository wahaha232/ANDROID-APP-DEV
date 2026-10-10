package com.startinsnow.gpstracker.ui.theme

import androidx.compose.ui.graphics.Color

// ── Brand palette ──────────────────────────────────────────────────────────────
// 以「戶外軌跡 + 科技感」為基調：沉穩藍（移動/定位）、翡翠綠（GPS 訊號/速度）、
// 暖橘（記錄中/警示），三色互相搭配，淺色與深色各自調整明度以維持對比。
val BrandBlue = Color(0xFF2563EB)
val BrandBlueDark = Color(0xFF7AA2FF)
val BrandEmerald = Color(0xFF059669)
val BrandEmeraldDark = Color(0xFF3DD68C)
val BrandOrange = Color(0xFFEA580C)
val BrandOrangeDark = Color(0xFFFFB27A)

// 地圖與圖表用的固定色（不隨主題改變，確保路線在各主題下都清楚）
val RouteGreen = Color(0xFF00C853)
val RouteBlue = Color(0xFF2F6BFF)
val RouteRed = Color(0xFFE53935)
val PhotoAmber = Color(0xFFFFB300)
val OutageGrey = Color(0xFF9E9E9E)

// GPS 品質語意色（對應 LocationQuality）
val QualityGood = Color(0xFF22C55E)
val QualityNormal = Color(0xFFEAB308)
val QualityWeak = Color(0xFFF97316)
val QualityLost = Color(0xFFEF4444)
