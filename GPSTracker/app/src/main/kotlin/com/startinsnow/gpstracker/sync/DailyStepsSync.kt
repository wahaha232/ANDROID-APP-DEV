package com.startinsnow.gpstracker.sync

import android.content.Context
import com.startinsnow.gpstracker.GpsTrackerApplication
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** 某一天的步數：[total] 是 Health Connect 去除重複後的合計；[perOrigin] 是各來源 App 分開的數字（只含有資料的）。 */
data class DailyStepsRow(val date: LocalDate, val total: Long, val perOrigin: Map<String, Long>)

/**
 * 把「每天」的步數（Health Connect，各來源分開 + 去重合計）上傳到網頁儀表板的 `data/steps.json`，
 * 讓網站的「步數統計」有逐日資料可顯示。網站原本只有「每趟活動」的步數，看不到全天步數。
 *
 * 內容沒有變時不重傳（比對雜湊），避免每次都產生一個 GitHub commit 與 Pages 重新部署。
 */
object DailyStepsSync {

    enum class Outcome { NOT_CONFIGURED, SKIPPED_WIFI_ONLY, UNCHANGED, UPLOADED }

    private const val PREFS = "daily_steps_sync"
    private const val KEY_LAST_HASH = "last_hash"
    /** 一次涵蓋的天數：Health Connect 能讀到多少就同步多少（沒有資料的天會是 0）。 */
    const val DEFAULT_DAYS = 1095 // 3 年

    suspend fun sync(app: GpsTrackerApplication, days: Int = DEFAULT_DAYS, force: Boolean = false): Result<Outcome> = runCatching {
        val settings = app.settingsRepository.settings.first()
        if (settings.githubToken.isBlank() || settings.webRepo.isBlank()) return@runCatching Outcome.NOT_CONFIGURED
        if (settings.webWifiOnly && !WebDashboardUploader.isOnWifi(app)) return@runCatching Outcome.SKIPPED_WIFI_ONLY

        val reader = HealthConnectStepReader(app)
        check(reader.isAvailable()) { "Health Connect unavailable" }
        check(reader.hasPermission()) { "Health Connect permission missing" }

        // 完全沒有資料的日子不上傳（網站把缺少的日期當 0），歷史很長時檔案才不會被零灌大。
        val rows = reader.dailySteps(days).getOrThrow().filter { it.total > 0L || it.perOrigin.isNotEmpty() }
        val daysJson = buildDaysJson(rows)
        val hash = sha1(daysJson.toString())
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && prefs.getString(KEY_LAST_HASH, null) == hash) return@runCatching Outcome.UNCHANGED

        val json = JSONObject()
            .put("updatedAt", System.currentTimeMillis())
            .put("timezone", java.time.ZoneId.systemDefault().id)
            .put("days", daysJson)
        WebDashboardUploader().putFile(
            token = settings.githubToken,
            repo = settings.webRepo,
            branch = settings.webBranch,
            path = "${settings.webDataPath.trim('/')}/steps.json",
            content = json.toString(),
            message = "GPS Tracker: update daily steps"
        ).getOrThrow()
        prefs.edit().putString(KEY_LAST_HASH, hash).apply()
        Outcome.UPLOADED
    }

    /** 網站讀的格式：每天一筆，來源分成 phone／googleFit／zepp／others{package: 步數}。 */
    fun buildDaysJson(rows: List<DailyStepsRow>): JSONArray {
        val arr = JSONArray()
        rows.forEach { row ->
            val sources = JSONObject()
            val others = JSONObject()
            var phone = 0L
            var googleFit = 0L
            var zepp = 0L
            row.perOrigin.forEach { (pkg, steps) ->
                when {
                    HealthConnectStepReader.isPhone(pkg) -> phone += steps
                    HealthConnectStepReader.isGoogleFit(pkg) -> googleFit += steps
                    HealthConnectStepReader.isZepp(pkg) -> zepp += steps
                    else -> others.put(pkg, steps)
                }
            }
            if (phone > 0) sources.put("phone", phone)
            if (googleFit > 0) sources.put("googleFit", googleFit)
            if (zepp > 0) sources.put("zepp", zepp)
            if (others.length() > 0) sources.put("others", others)
            arr.put(JSONObject().put("date", row.date.toString()).put("total", row.total).put("sources", sources))
        }
        return arr
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
