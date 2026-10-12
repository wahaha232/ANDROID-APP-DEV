package com.startinsnow.gpstracker.sync

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

    /** 一次涵蓋的天數：Health Connect 能讀到多少就同步多少（沒有資料的天會是 0）。 */
    const val DEFAULT_DAYS = 1095 // 3 年

    /**
     * 背景定期同步只重讀最近這幾天：更早的天數已經在網站上（合併只增不減），而且幾乎不會再變，
     * 每次重讀 3 年既慢又耗電。涵蓋這麼多天是為了：手環離開一陣子、回來同步後，Zepp／Google Fit 會補寫過去幾天的步數。
     * 網站上還沒有資料時（第一次）與按「同步到網站」時仍會讀完整歷史。
     */
    const val RECENT_DAYS = 30

    suspend fun sync(app: GpsTrackerApplication, days: Int = DEFAULT_DAYS, force: Boolean = false): Result<Outcome> = runCatching {
        val settings = app.settingsRepository.settings.first()
        if (settings.githubToken.isBlank() || settings.webRepo.isBlank()) return@runCatching Outcome.NOT_CONFIGURED
        if (settings.webWifiOnly && !WebDashboardUploader.isOnWifi(app)) return@runCatching Outcome.SKIPPED_WIFI_ONLY

        val reader = HealthConnectStepReader(app)
        check(reader.isAvailable()) { "Health Connect unavailable" }
        check(reader.hasPermission()) { "Health Connect permission missing" }

        // 持續累計：合併進網站上現有的 steps.json，而不是整份覆寫。
        // 已上傳的天數永遠保留——就算 Health Connect 日後不再保留舊資料、或超出同步範圍，累計也不會變少。
        val uploader = WebDashboardUploader()
        val path = "${settings.webDataPath.trim('/')}/steps.json"
        val existingText = uploader.getFileText(settings.githubToken, settings.webRepo, settings.webBranch, path).getOrThrow()
        val existing = existingText?.let { runCatching { toMap(JSONObject(it).getJSONArray("days")) }.getOrNull() } ?: emptyMap()

        // 網站上還沒有資料時（第一次）一次讀完整歷史；之後只重讀最近幾天。
        val effectiveDays = if (existing.isEmpty()) maxOf(days, DEFAULT_DAYS) else days
        // 完全沒有資料的日子不上傳（網站把缺少的日期當 0），歷史很長時檔案才不會被零灌大。
        val rows = reader.dailySteps(effectiveDays).getOrThrow().filter { it.total > 0L || it.perOrigin.isNotEmpty() }
        val fresh = toMap(buildDaysJson(rows))
        val merged = mergeByDate(existing, fresh) { hasData(it) }
        val mergedJson = JSONArray().also { arr -> merged.toSortedMap().values.forEach { arr.put(it) } }

        if (!force && existingText != null && existing.isNotEmpty() && sha1(mergedJson.toString()) == sha1(toJsonArray(existing).toString())) {
            return@runCatching Outcome.UNCHANGED
        }

        val json = JSONObject()
            .put("updatedAt", System.currentTimeMillis())
            .put("timezone", java.time.ZoneId.systemDefault().id)
            .put("days", mergedJson)
        uploader.putFile(
            token = settings.githubToken,
            repo = settings.webRepo,
            branch = settings.webBranch,
            path = path,
            content = json.toString(),
            message = "GPS Tracker: update daily steps"
        ).getOrThrow()
        Outcome.UPLOADED
    }

    /**
     * 依日期合併，只增不減：[fresh] 該日有資料就以它為準（Health Connect 可能回頭修正當天數字）；
     * 沒有資料的日子保留 [existing] 原本的，[existing] 有而 [fresh] 完全沒有的日子也一律保留。
     */
    fun <T> mergeByDate(existing: Map<String, T>, fresh: Map<String, T>, hasData: (T) -> Boolean): Map<String, T> {
        val result = LinkedHashMap<String, T>(existing)
        fresh.forEach { (date, value) ->
            if (hasData(value) || !result.containsKey(date)) result[date] = value
        }
        return result
    }

    private fun hasData(day: JSONObject): Boolean =
        day.optLong("total", 0L) > 0L || (day.optJSONObject("sources")?.length() ?: 0) > 0

    private fun toMap(arr: JSONArray): Map<String, JSONObject> {
        val m = LinkedHashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            m[o.getString("date")] = o
        }
        return m
    }

    private fun toJsonArray(map: Map<String, JSONObject>): JSONArray =
        JSONArray().also { arr -> map.toSortedMap().values.forEach { arr.put(it) } }

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
