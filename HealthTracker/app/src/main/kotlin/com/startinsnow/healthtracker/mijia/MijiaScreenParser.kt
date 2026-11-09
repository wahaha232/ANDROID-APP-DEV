package com.startinsnow.healthtracker.mijia

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.db.SyncState
import com.startinsnow.healthtracker.data.repository.RecordValidator
import com.startinsnow.healthtracker.importer.ImportIds
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 螢幕上一個有文字的元件與它的位置（像素）。 */
data class UiText(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Int get() = height * (right - left).coerceAtLeast(0)
}

data class MijiaReading(
    val measuredAt: Long,
    val weightKg: Double?,
    val bodyFatPercent: Double?,
    /** true = 畫面上找不到時間，改用讀取時間推估。 */
    val timeEstimated: Boolean = false,
)

/**
 * 從米家「Xiaomi 體脂計 S400」頁面的文字元件解析體重與體脂率。
 * 這頁是 React Native 外掛：體重整數與小數常分成兩個元件（"68" 與 ".4"），
 * 時間在體重正下方（"2026/10/19 06:33"），體脂率數字在「體脂率」標籤正上方。
 * 米家改版時最容易壞的就是這個檔案；解析不到就回傳 null，不會亂寫入資料。
 *
 * 為了降低漏讀率，另外提供 [parseLoose]：
 * - 標題不在無障礙畫面裡（常見）時，改用「體脂率／BMI＋肌肉量」判断這是體脂計頁面；
 * - 支援更多時間格式（-、.、今天／昨天、12 小時制、只有月日）；
 * - 找不到時間時，用 [fallbackTime] 推估（由呼叫端帶入上次量測時間或現在）。
 */
object MijiaScreenParser {
    private val pageTitleRe = Regex("""體脂計|體脂秤|體重計|Body\s*Composition|Body\s*Fat\s*Scale""", RegexOption.IGNORE_CASE)
    private val fatLabelRe = Regex("""^(體脂率|體脂肪率|體脂|Body\s*fat(\s*(rate|percentage))?|Fat\s*%|Body\s*fat\s*%)$""", RegexOption.IGNORE_CASE)
    private val bmiLabelRe = Regex("""^(BMI|身體質量指數)$""", RegexOption.IGNORE_CASE)
    private val muscleLabelRe = Regex("""^(肌肉量|骨骼肌|Muscle(\s*mass)?|Skeletal\s*muscle)$""", RegexOption.IGNORE_CASE)

    private val wholeRe = Regex("""^(\d{1,3})\.(\d{1,2})\s*(?:kg|公斤)?$""", RegexOption.IGNORE_CASE)
    private val intRe = Regex("""^(\d{1,3})\s*(?:kg|公斤)?$""", RegexOption.IGNORE_CASE)
    private val decRe = Regex("""^\.(\d{1,2})$""")
    private val fatValueRe = Regex("""^(\d{1,2})(?:\.(\d))?\s*%?$""")
    private const val MAX_FUTURE_MS = 24 * 3_600_000L

    /** 嚴格解析：必須看得到體脂計頁面標題（保留給舊行為與測試）。 */
    fun parse(raw: List<UiText>, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): MijiaReading? =
        parseInternal(raw, zone, now, allowMissingTime = false, fallbackTime = now)

    /**
     * 寬鬆解析（給無障礙服務用）：標題看不到也能讀，沒有時間時用 [fallbackTime]。
     * 仍要求畫面有體脂計的明確特徵，避免在其他米家頁面亂抓數字。
     */
    fun parseLoose(
        raw: List<UiText>,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
        fallbackTime: Long = now,
    ): MijiaReading? = parseInternal(raw, zone, now, allowMissingTime = true, fallbackTime = fallbackTime)

    /** 畫面上是否像體脂計頁面（標題、體脂率標籤，或 BMI＋肌肉量同時出現）。 */
    fun looksLikeScale(raw: List<UiText>): Boolean {
        val texts = normalized(raw)
        if (texts.isEmpty()) return false
        if (texts.any { pageTitleRe.containsMatchIn(it.text) }) return true
        if (texts.any { fatLabelRe.matches(it.text) }) return true
        return texts.any { bmiLabelRe.matches(it.text) } && texts.any { muscleLabelRe.matches(it.text) }
    }

    private fun parseInternal(raw: List<UiText>, zone: ZoneId, now: Long, allowMissingTime: Boolean, fallbackTime: Long): MijiaReading? {
        val texts = normalized(raw)
        if (texts.isEmpty()) return null
        val titled = texts.any { pageTitleRe.containsMatchIn(it.text) }
        if (!titled) {
            if (!allowMissingTime) return null
            if (!looksLikeScale(texts)) return null
        }

        val headers = texts.mapNotNull { t -> parseDateHeader(t.text)?.let { t to it } }
        val times = texts.mapNotNull { t ->
            (timeUnderHeader(t, headers, zone) ?: parseTimeMillis(t.text, zone, now))
                ?.takeIf { it <= now + MAX_FUTURE_MS }?.let { t to it }
        }
        val paired = readWeightWithTime(texts, times)
        // 有時間時只在時間上方找體重（避免把下方的 BMI／肌肉量當體重）；
        // 真的找不到才用「字體明顯最大」的數字當備援。
        val weight = paired?.let { it.first to it.second }
            ?: if (allowMissingTime) {
                if (times.isEmpty()) readWeightLoose(texts) else readWeightLooseIfDominant(texts)
            } else {
                null
            }
        val fat = readBodyFat(texts)
        if (weight == null && fat == null) return null

        val anchorTop = weight?.first?.top ?: fat?.first?.top ?: 0
        val nearestTime = times.minByOrNull { abs(it.first.top - anchorTop) }?.second
        val chosen = paired?.third ?: nearestTime
        if (chosen == null && !allowMissingTime) return null
        val at = chosen ?: fallbackTime

        val w = weight?.second
        val f = fat?.second
        if (w == null && f == null) return null
        return MijiaReading(at, w, f, timeEstimated = chosen == null)
    }

    /** 找出體重與它對應的時間；回傳 (體重節點, 體重, 量測時間)。 */
    private fun readWeightWithTime(texts: List<UiText>, times: List<Pair<UiText, Long>>): Triple<UiText, Double, Long>? {
        var best: Triple<UiText, Double, Long>? = null
        for ((timeNode, ms) in times) {
            val pair = readWeightAbove(texts, timeNode) ?: continue
            val current = best
            if (current == null || pair.first.height > current.first.height) best = Triple(pair.first, pair.second, ms)
        }
        return best
    }

    /** 時間文字上方的體重；S400 的體重整數框會延伸到時間文字的高度，所以只要求「從時間上方開始」。 */
    private fun readWeightAbove(texts: List<UiText>, timeNode: UiText): Pair<UiText, Double>? {
        val above = texts.filter { it.top < timeNode.top }
        return combineSplit(above) ?: bestWhole(above) ?: bestInt(above)
    }

    /**
     * 有時間但體重不在時間上方時的備援：只接受字體明顯最大的數字。
     * 體重字級遠大於 BMI／體脂／肌肉量；若最大的兩個數字差不多大，就視為不可靠、不猜。
     */
    private fun readWeightLooseIfDominant(texts: List<UiText>): Pair<UiText, Double>? {
        val candidates = listOfNotNull(combineSplit(texts), bestWhole(texts), bestInt(texts))
            .distinctBy { it.first.text to it.first.top }
            .sortedByDescending { it.first.height }
        val top = candidates.firstOrNull() ?: return null
        val second = candidates.getOrNull(1)
        if (second != null && top.first.height < second.first.height * 1.35) return null
        return top
    }

    /** 沒有時間可定位時，用字體最大的數字當體重（體重數字遠大於 BMI／體脂／肌肉量）。 */
    private fun readWeightLoose(texts: List<UiText>): Pair<UiText, Double>? {
        combineSplit(texts)?.let { split ->
            val whole = bestWhole(texts)
            val int = bestInt(texts)
            val tallest = listOfNotNull(split, whole, int).maxByOrNull { it.first.height }
            if (tallest != null) return tallest
        }
        val whole = bestWhole(texts)
        val int = bestInt(texts)
        return when {
            whole != null && (int == null || whole.first.height >= int.first.height) -> whole
            int != null -> int
            else -> null
        }
    }

    /** 整數與小數分開的體重（"68" + ".4"）。小數取字體最大的，整數取與它垂直重疊且在最左邊的。 */
    private fun combineSplit(texts: List<UiText>): Pair<UiText, Double>? {
        val dec = texts.filter { decRe.matches(it.text) }.maxByOrNull { it.height } ?: return null
        val int = texts.filter {
            intRe.matches(it.text) && it.top < dec.bottom && it.bottom > dec.top && it.left < dec.left
        }.maxByOrNull { it.height } ?: return null
        val value = (int.text + dec.text).toDoubleOrNull() ?: return null
        return int to value
    }

    private fun bestWhole(texts: List<UiText>): Pair<UiText, Double>? =
        texts.mapNotNull { t ->
            wholeRe.matchEntire(t.text)?.let { m ->
                val v = "${m.groupValues[1]}.${m.groupValues[2]}".toDoubleOrNull()
                if (v != null) t to v else null
            }
        }.maxByOrNull { it.first.height }

    private fun bestInt(texts: List<UiText>): Pair<UiText, Double>? =
        texts.filter { intRe.matches(it.text) }
            .mapNotNull { t -> t.text.replace(Regex("""\s*(?:kg|公斤)$""", RegexOption.IGNORE_CASE), "").toDoubleOrNull()?.let { t to it } }
            .maxByOrNull { it.first.height }

    /** 體脂率：找「體脂率」標籤附近的數字，避免把 BMI／肌肉量當成體脂率。 */
    private fun readBodyFat(texts: List<UiText>): Pair<UiText, Double>? {
        val labels = texts.filter { fatLabelRe.matches(it.text) }
        if (labels.isEmpty()) return null
        val values = texts.mapNotNull { t ->
            val m = fatValueRe.matchEntire(t.text) ?: return@mapNotNull null
            val v = buildString {
                append(m.groupValues[1])
                m.groupValues[2].takeIf { it.isNotEmpty() }?.let { append('.').append(it) }
            }.toDoubleOrNull() ?: return@mapNotNull null
            if (v !in 2.0..70.0) return@mapNotNull null
            t to v
        }
        var best: Pair<UiText, Double>? = null
        var bestScore = Double.MAX_VALUE
        for (label in labels) {
            for ((node, v) in values) {
                val gapX = when {
                    node.right < label.left -> label.left - node.right
                    node.left > label.right -> node.left - label.right
                    else -> 0
                }
                val gapY = when {
                    node.bottom < label.top -> label.top - node.bottom
                    node.top > label.bottom -> node.top - label.bottom
                    else -> 0
                }
                // 必須在標籤附近；分數越小越可能是同一組數字。
                // 水平容差要小：S400 的 BMI／肌肉量數值可能在隔壁 30–40px，容差太大會被誤認成體脂率。
                val allowedY = max(label.height, node.height) * 1.5 + 24
                if (gapX > 16 || gapY > allowedY) continue
                val score = gapX + gapY * 2.0
                if (score < bestScore) {
                    bestScore = score
                    best = node to v
                }
            }
        }
        return best
    }

    private fun normalized(raw: List<UiText>): List<UiText> {
        val out = ArrayList<UiText>(raw.size)
        for (t in raw) {
            val s = normalize(t.text)
            if (s.isNotEmpty()) out += t.copy(text = s)
        }
        return out
    }

    /** 全形數字／標點與單位空白正規化。 */
    private fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input.trim()) {
            when (ch) {
                in '０'..'９' -> sb.append(('0'.code + (ch.code - '０'.code)).toChar())
                '．', '，', '、' -> sb.append('.')
                '％' -> sb.append('%')
                '　', '\u00A0' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("""\s+"""), " ").trim()
    }

    /** 支援多種米家版本的時間格式；解析成功回傳 epoch millis。 */
    internal fun parseTimeMillis(text: String, zone: ZoneId, now: Long = System.currentTimeMillis()): Long? {
        val t = normalize(text)
        Regex("""^(\d{4})[/\-.](\d{1,2})[/\-.](\d{1,2})[ T]+(\d{1,2}):(\d{2})(?::(\d{2}))?$""")
            .matchEntire(t)?.let { m ->
                val (y, mo, d, h, mi) = m.groupValues.drop(1).take(5).map { it.toInt() }
                val sec = m.groupValues.getOrNull(6)?.toIntOrNull() ?: 0
                return localMillis(y, mo, d, h, mi, sec, zone)
            }
        Regex("""^(\d{1,2})[/\-.](\d{1,2})\s+(\d{1,2}):(\d{2})$""").matchEntire(t)?.let { m ->
            val (mo, d, h, mi) = m.groupValues.drop(1).map { it.toInt() }
            return noYearMillis(mo, d, h, mi, zone, now)
        }
        Regex("""^(今天|今日|昨天|昨日)\s*(\d{1,2}):(\d{2})$""").matchEntire(t)?.let { m ->
            val days = if (m.groupValues[1].startsWith("昨")) -1L else 0L
            val (h, mi) = m.groupValues.drop(2).map { it.toInt() }
            val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(days)
            return localMillis(day.year, day.monthValue, day.dayOfMonth, h, mi, 0, zone)
        }
        Regex("""^(\d{1,2})月(\d{1,2})日\s*(\d{1,2}):(\d{2})$""").matchEntire(t)?.let { m ->
            val (mo, d, h, mi) = m.groupValues.drop(1).map { it.toInt() }
            return noYearMillis(mo, d, h, mi, zone, now)
        }
        Regex("""^(上午|早上|凌晨|下午|晚上|中午)\s*(\d{1,2}):(\d{2})$""").matchEntire(t)?.let { m ->
            val (h, mi) = m.groupValues.drop(2).map { it.toInt() }
            val hh = when (m.groupValues[1]) {
                "下午", "晚上" -> if (h == 12) 12 else h + 12
                "中午" -> 12
                else -> if (h == 12) 0 else h
            }
            return todayMillis(hh, mi, zone, now)
        }
        Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(AM|PM)?$""", RegexOption.IGNORE_CASE).matchEntire(t)?.let { m ->
            var h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            val sec = m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            val ampm = m.groupValues.getOrNull(4).orEmpty().uppercase()
            if (ampm == "PM" && h != 12) h += 12
            if (ampm == "AM" && h == 12) h = 0
            return todayMillis(h, mi, zone, now, sec)
        }
        return null
    }

    private val dateHeaderRe = Regex("""^(\d{4})[/\-.](\d{1,2})[/\-.](\d{1,2})$""")
    private val timeOnlyRe = Regex("""^(\d{1,2}):(\d{2})$""")

    /** 單獨一行的日期（例如歷史清單的分組標題「2026/11/01」）。 */
    internal fun parseDateHeader(text: String): LocalDate? {
        val m = dateHeaderRe.matchEntire(normalize(text)) ?: return null
        return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
    }

    /**
     * 只有「17:54」這種時間時，不能當成今天：歷史清單的日期在上方的分組標題。
     * 有標題就用「在它上方、最靠近」的那個日期；沒有標題才回傳 null，交給原本的推估。
     */
    private fun timeUnderHeader(t: UiText, headers: List<Pair<UiText, LocalDate>>, zone: ZoneId): Long? {
        val m = timeOnlyRe.matchEntire(t.text) ?: return null
        val header = headers.filter { it.first.top < t.top }.maxByOrNull { it.first.top } ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        val d = header.second
        return localMillis(d.year, d.monthValue, d.dayOfMonth, h, mi, 0, zone)
    }

    private fun noYearMillis(month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId, now: Long): Long? {
        val year = Instant.ofEpochMilli(now).atZone(zone).year
        var ms = localMillis(year, month, day, hour, minute, 0, zone) ?: return null
        // 只有月日時，若解析出的時間比現在晚超過一天，通常是去年的紀錄
        if (ms > now + MAX_FUTURE_MS) {
            ms = localMillis(year - 1, month, day, hour, minute, 0, zone) ?: return null
        }
        return ms
    }

    private fun todayMillis(hour: Int, minute: Int, zone: ZoneId, now: Long, second: Int = 0): Long? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var ms = localMillis(today.year, today.monthValue, today.dayOfMonth, hour, minute, second, zone) ?: return null
        if (ms > now + MAX_FUTURE_MS) {
            val yesterday = today.minusDays(1)
            ms = localMillis(yesterday.year, yesterday.monthValue, yesterday.dayOfMonth, hour, minute, second, zone) ?: return null
        }
        return ms
    }

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, zone: ZoneId): Long? = runCatching {
        LocalDateTime.of(year, month, day, hour, minute, second).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    /** 轉成本機紀錄（數值不合理的略過）。id 由時間與數值決定，同一次量測重複擷取只會留一筆。 */
    fun toRecords(r: MijiaReading, now: Long = System.currentTimeMillis()): List<HealthRecord> {
        val note = if (r.timeEstimated) "來源：米家畫面（時間以讀取時間推估）" else "來源：米家畫面"
        fun make(type: RecordType, v: Double) = HealthRecord(
            id = ImportIds.fromMijia(type, r.measuredAt, v), type = type, value = v, measuredAt = r.measuredAt,
            source = RecordSource.MIJIA, note = note, syncState = SyncState.PENDING, createdAt = now, updatedAt = now,
        )
        return buildList {
            r.weightKg?.takeIf { RecordValidator.validate(RecordType.WEIGHT, it, null) == null }?.let { add(make(RecordType.WEIGHT, it)) }
            r.bodyFatPercent?.takeIf { RecordValidator.validate(RecordType.BODY_FAT, it, null) == null }?.let { add(make(RecordType.BODY_FAT, it)) }
        }
    }
}
