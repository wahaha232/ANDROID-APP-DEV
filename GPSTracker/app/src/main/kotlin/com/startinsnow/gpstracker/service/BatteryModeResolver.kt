package com.startinsnow.gpstracker.service

import android.content.Context
import android.os.BatteryManager
import com.startinsnow.gpstracker.core.model.BatteryMode

/** 對應規格「55. Low Battery」，讀取實際電量（BatteryManager），不做假電量模擬。 */
object BatteryModeResolver {
    fun currentBatteryPercent(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        return if (level in 0..100) level else 100
    }

    fun modeFor(batteryPercent: Int): BatteryMode = when {
        batteryPercent > 50 -> BatteryMode.NORMAL
        batteryPercent >= 20 -> BatteryMode.POWER_SAVING
        else -> BatteryMode.ULTRA_POWER_SAVING
    }
}
