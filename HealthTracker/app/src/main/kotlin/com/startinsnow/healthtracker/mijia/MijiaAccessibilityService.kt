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
 *
 * 成功率相關：
 * - 文字與 contentDescription 都會收集（米家有些版本把數字放在 contentDescription）。
 * - 先用嚴格解析；失敗再用寬鬆解析（沒有標題、沒有時間也能讀，時間用上次量測或現在推估）。
 * - 解析失敗時把畫面文字存到除錯區，設定頁可以查看；服務連線時也可從設定頁「立即讀取畫面」強制重讀。
 */
class MijiaAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSignature: String? = null
    private var lastFailureSignature: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        MijiaCaptureStore.attach(this)
    }

    private var lastRunAt = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var trailingScheduled = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != MIJIA_PACKAGE) return
        // 米家畫面一直在更新，事件很密；每次都走訪整棵畫面樹很耗電，所以最多每 350ms 解析一次。
        // 被略過的事件補一次延後的解析，避免「最後一次畫面更新」沒被讀到。
        val t = android.os.SystemClock.elapsedRealtime()
        if (t - lastRunAt < MIN_INTERVAL_MS) {
            if (!trailingScheduled) {
                trailingScheduled = true
                handler.postDelayed({
                    trailingScheduled = false
                    lastRunAt = android.os.SystemClock.elapsedRealtime()
                    captureNow(force = false)
                }, MIN_INTERVAL_MS)
            }
            return
        }
        lastRunAt = t
        captureNow(force = false)
    }

    /** 解析目前米家畫面；回傳 true 表示這次有讀到一組體重／體脂率（不代表一定新增紀錄）。 */
    fun captureNow(force: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != MIJIA_PACKAGE) return false

        val texts = ArrayList<UiText>()
        collect(root, texts)
        if (texts.isEmpty()) return false

        val now = System.currentTimeMillis()
        val reading = MijiaScreenParser.parse(texts, now = now)
            ?: MijiaScreenParser.parseLoose(texts, now = now, fallbackTime = fallbackTime(now))
        if (reading == null) {
            if (MijiaScreenParser.looksLikeScale(texts)) publishFailure(texts, now)
            return false
        }
        if (reading.measuredAt > now + MAX_FUTURE_MS) return false

        val signature = "${reading.measuredAt}|${reading.weightKg}|${reading.bodyFatPercent}"
        if (!force && signature == lastSignature) return true
        lastSignature = signature

        val raw = rawLines(texts)
        val records = (application as HealthTrackerApp).records
        scope.launch {
            val result = records.importMijiaReading(MijiaScreenParser.toRecords(reading))
            val event = MijiaCaptureStore.Event(
                seq = MijiaCaptureStore.nextSeq(),
                at = now,
                reading = reading,
                inserted = result.inserted,
                skipped = result.skipped,
                raw = raw,
            )
            MijiaCaptureStore.saveLast(this@MijiaAccessibilityService, event)
            MijiaCaptureStore.publish(event)
        }
        return true
    }

    /** 找不到時間時，用上次量測時間（2 小時內）推估，否則用現在。 */
    private fun fallbackTime(now: Long): Long {
        val last = MijiaCaptureStore.loadLast(this)?.measuredAt ?: return now
        return last.takeIf { now - it in 0..2 * 3_600_000L } ?: now
    }

    private fun publishFailure(texts: List<UiText>, now: Long) {
        val raw = rawLines(texts)
        val sig = raw.joinToString("|").take(600)
        if (sig == lastFailureSignature) return
        lastFailureSignature = sig
        MijiaCaptureStore.publish(
            MijiaCaptureStore.Event(
                seq = MijiaCaptureStore.nextSeq(),
                at = now,
                reading = null,
                raw = raw,
                message = "有看到米家體脂計頁面，但讀不到體重／體脂率。請停在量測結果頁再按「立即讀取畫面」。",
            ),
        )
    }

    private fun rawLines(texts: List<UiText>): List<String> =
        texts.asSequence().map { it.text.trim() }.filter { it.isNotEmpty() }.take(MAX_RAW_LINES)
            .map { it.take(80) }.toList()

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<UiText>) {
        if (out.size >= MAX_NODES) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) out += UiText(text, rect.left, rect.top, rect.right, rect.bottom)
        if (desc.isNotEmpty() && desc != text) out += UiText(desc, rect.left, rect.top, rect.right, rect.bottom)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        MijiaCaptureStore.detach(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val MIJIA_PACKAGE = "com.xiaomi.smarthome"
        const val PREFS = "mijia_capture"
        const val KEY_LAST = "last"
        private const val MAX_NODES = 600
        private const val MIN_INTERVAL_MS = 350L
        private const val MAX_RAW_LINES = 80
        private const val MAX_FUTURE_MS = 24 * 3_600_000L
    }
}
