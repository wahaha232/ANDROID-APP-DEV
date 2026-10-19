package com.startinsnow.healthtracker.mijia

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.db.SyncState
import com.startinsnow.healthtracker.data.repository.RecordValidator
import com.startinsnow.healthtracker.importer.ImportIds
import java.time.LocalDateTime
import java.time.ZoneId

/** 螢幕上一個有文字的元件與它的位置（像素）。 */
data class UiText(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

data class MijiaReading(val measuredAt: Long, val weightKg: Double?, val bodyFatPercent: Double?)

/**
 * 從米家「Xiaomi 體脂計 S400」頁面的文字元件解析體重與體脂率。
 * 這頁是 React Native 外掛：體重整數與小數分成兩個元件（"70" 與 ".0"），
 * 時間在體重正下方（"2026/10/19 06:33"），體脂率數字在「體脂率」標籤正上方。
 * 米家改版時最容易壞的就是這個檔案；解析不到就回傳 null，不會亂寫入資料。
 */
object MijiaScreenParser {
    private val timeRe = Regex("""^(\d{4})[/-](\d{1,2})[/-](\d{1,2})\s+(\d{1,2}):(\d{2})$""")
    private val intRe = Regex("""^\d{2,3}$""")
    private val decRe = Regex("""^\.\d$""")
    private val wholeRe = Regex("""^\d{2,3}\.\d$""")
    private val fatValueRe = Regex("""^\d{1,2}(\.\d)?$""")
    private val pageTitleRe = Regex("""體脂計|體脂秤|Body Composition""", RegexOption.IGNORE_CASE)
    private val fatLabelRe = Regex("""^(體脂率|Body fat( rate| percentage)?)$""", RegexOption.IGNORE_CASE)
    private const val MAX_FUTURE_MS = 24 * 3_600_000L

    fun parse(raw: List<UiText>, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): MijiaReading? {
        val texts = raw.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
        if (texts.none { pageTitleRe.containsMatchIn(it.text) }) return null

        val timeNode = texts.firstOrNull { timeRe.matches(it.text) } ?: return null
        val measuredAt = toMillis(timeRe.matchEntire(timeNode.text)!!, zone) ?: return null
        if (measuredAt > now + MAX_FUTURE_MS) return null

        val weight = readWeight(texts, timeNode)
        val fat = readBodyFat(texts)
        if (weight == null && fat == null) return null
        return MijiaReading(measuredAt, weight, fat)
    }

    private fun readWeight(texts: List<UiText>, timeNode: UiText): Double? {
        // 真實頁面上體重整數元件的框會延伸到時間文字的高度，所以只要求「從時間上方開始」
        val above = texts.filter { it.top < timeNode.top }
        // 單一元件的 "70.5"
        above.filter { wholeRe.matches(it.text) }.maxByOrNull { it.top }?.let { return it.text.toDouble() }
        // 整數與小數分開：小數取時間上方最近的，整數取與它垂直重疊且在它左邊的
        val dec = above.filter { decRe.matches(it.text) }.maxByOrNull { it.top } ?: return null
        val int = above.filter { intRe.matches(it.text) && it.top < dec.bottom && it.bottom > dec.top && it.left < dec.left }
            .maxByOrNull { it.bottom - it.top } ?: return null
        return (int.text + dec.text).toDoubleOrNull()
    }

    private fun readBodyFat(texts: List<UiText>): Double? {
        val label = texts.firstOrNull { fatLabelRe.matches(it.text) } ?: return null
        return texts
            .filter { fatValueRe.matches(it.text) && it.bottom <= label.top + 8 && it.left < label.right && it.right > label.left }
            .maxByOrNull { it.bottom }?.text?.toDoubleOrNull()
    }

    private fun toMillis(m: MatchResult, zone: ZoneId): Long? = runCatching {
        val (y, mo, d, h, mi) = m.groupValues.drop(1).map { it.toInt() }
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    /** 轉成本機紀錄（數值不合理的略過）。id 由時間與數值決定，同一次量測重複擷取只會留一筆。 */
    fun toRecords(r: MijiaReading, now: Long = System.currentTimeMillis()): List<HealthRecord> {
        fun make(type: RecordType, v: Double) = HealthRecord(
            id = ImportIds.fromMijia(type, r.measuredAt, v), type = type, value = v, measuredAt = r.measuredAt,
            source = RecordSource.MIJIA, note = "來源：米家畫面", syncState = SyncState.PENDING, createdAt = now, updatedAt = now,
        )
        return buildList {
            r.weightKg?.takeIf { RecordValidator.validate(RecordType.WEIGHT, it, null) == null }?.let { add(make(RecordType.WEIGHT, it)) }
            r.bodyFatPercent?.takeIf { RecordValidator.validate(RecordType.BODY_FAT, it, null) == null }?.let { add(make(RecordType.BODY_FAT, it)) }
        }
    }
}
