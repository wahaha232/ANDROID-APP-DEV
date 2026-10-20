package com.startinsnow.healthtracker.mijia

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.startinsnow.healthtracker.HealthTrackerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 使用者開啟米家「體脂計 S400」頁面時，讀取畫面上的體重與體脂率並存進本機。
 * 只監聽米家（見 res/xml/mijia_accessibility_config.xml），只解析體重／體脂率，不記錄其他畫面內容。
 * 必須由使用者自己到系統「無障礙」設定開啟；沒開啟就完全不運作。
 */
class MijiaAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSignature: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != MIJIA_PACKAGE) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != MIJIA_PACKAGE) return

        val texts = ArrayList<UiText>()
        collect(root, texts)
        val reading = MijiaScreenParser.parse(texts) ?: return
        val signature = "${reading.measuredAt}|${reading.weightKg}|${reading.bodyFatPercent}"
        if (signature == lastSignature) return // 畫面一直在更新，同一次量測只處理一次
        lastSignature = signature

        val records = (application as HealthTrackerApp).records
        scope.launch {
            val result = records.importRecords(MijiaScreenParser.toRecords(reading))
            // 記下這次讀到什麼，設定頁會顯示，讓你知道有沒有讀成功
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_LAST, "${System.currentTimeMillis()}|${reading.measuredAt}|${reading.weightKg ?: ""}|${reading.bodyFatPercent ?: ""}|${result.inserted}|${result.skipped}")
                .apply()
        }
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<UiText>) {
        if (out.size >= MAX_NODES) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            val r = Rect()
            node.getBoundsInScreen(r)
            out += UiText(it, r.left, r.top, r.right, r.bottom)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val MIJIA_PACKAGE = "com.xiaomi.smarthome"
        const val PREFS = "mijia_capture"
        const val KEY_LAST = "last"
        private const val MAX_NODES = 400
    }
}
