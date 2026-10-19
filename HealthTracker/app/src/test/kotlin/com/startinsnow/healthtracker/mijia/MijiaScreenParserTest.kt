package com.startinsnow.healthtracker.mijia

import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.db.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

private val TAIPEI = ZoneId.of("Asia/Taipei")
private val NOW = Instant.parse("2026-10-19T04:00:00Z").toEpochMilli()

private fun t(text: String, l: Int, tp: Int, r: Int, b: Int) = UiText(text, l, tp, r, b)

/** 依真實 S400 頁面的元件位置結構編寫（數值為虛構）。 */
private fun s400Page(intPart: String = "68", dec: String = ".4", time: String = "2026/10/19 06:33", fat: String = "22", title: String = "Xiaomi 體脂計 S400") = listOf(
    t(title, 365, 165, 867, 236),
    t("中斷連線", 216, 236, 1016, 291),
    t("$intPart ", 425, 612, 733, 899),
    t("kg ", 704, 666, 770, 731),
    t("$dec ", 676, 731, 798, 875),
    t(time, 439, 869, 782, 925),
    t("偏高", 572, 943, 650, 995),
    t("相比1日前上升0.2kg", 432, 1001, 789, 1053),
    t("25.1", 96, 2169, 415, 2281), t("BMI", 96, 2281, 415, 2335),
    t(fat, 451, 2169, 547, 2281), t("%", 553, 2214, 587, 2281), t("體脂率", 451, 2281, 769, 2335),
    t("51.1", 805, 2169, 937, 2281), t("kg", 943, 2210, 989, 2266), t("肌肉量", 805, 2281, 1124, 2335),
)

class MijiaScreenParserTest {
    private fun parse(texts: List<UiText>) = MijiaScreenParser.parse(texts, TAIPEI, NOW)

    @Test fun readsWeightFatAndTime() {
        val r = parse(s400Page())!!
        assertEquals(68.4, r.weightKg!!, 0.0)
        assertEquals(22.0, r.bodyFatPercent!!, 0.0)
        assertEquals(Instant.parse("2026-10-18T22:33:00Z").toEpochMilli(), r.measuredAt) // 台北 06:33
    }

    @Test fun bmiAndMuscleMassAreNotMistakenForBodyFat() {
        assertEquals(22.0, parse(s400Page(fat = "22"))!!.bodyFatPercent!!, 0.0)
    }

    @Test fun threeDigitWeight() = assertEquals(102.3, parse(s400Page(intPart = "102", dec = ".3"))!!.weightKg!!, 0.0)

    @Test fun decimalBodyFat() = assertEquals(21.7, parse(s400Page(fat = "21.7"))!!.bodyFatPercent!!, 0.0)

    @Test fun singleNodeWeightAlsoWorks() {
        val page = s400Page().filterNot { it.text.trim() in listOf("68", ".4") } + t("68.4", 425, 612, 733, 899)
        assertEquals(68.4, parse(page)!!.weightKg!!, 0.0)
    }

    @Test fun otherPagesAreIgnored() {
        assertNull(parse(s400Page(title = "我的家")))
        assertNull(parse(listOf(t("米家", 0, 0, 100, 50), t("70", 0, 100, 50, 200))))
    }

    @Test fun missingTimeMeansNoReading() = assertNull(parse(s400Page().filterNot { it.text.startsWith("2026") }))

    @Test fun futureTimestampRejected() = assertNull(parse(s400Page(time = "2026/10/25 06:33")))

    @Test fun noNumbersMeansNoReading() {
        val page = s400Page().filter { it.text.trim() !in listOf("68", ".4", "22") }
        assertNull(parse(page))
    }

    @Test fun weightOnlyWhenFatLabelMissing() {
        val r = parse(s400Page().filterNot { it.text == "體脂率" })!!
        assertNotNull(r.weightKg)
        assertNull(r.bodyFatPercent)
    }

    @Test fun englishLabelsSupported() {
        val page = s400Page(title = "Xiaomi Body Composition Scale S400").map { if (it.text == "體脂率") it.copy(text = "Body fat") else it }
        assertEquals(22.0, parse(page)!!.bodyFatPercent!!, 0.0)
    }

    @Test fun recordsHaveStableIdsAndMijiaSource() {
        val reading = parse(s400Page())!!
        val a = MijiaScreenParser.toRecords(reading, NOW)
        val b = MijiaScreenParser.toRecords(reading, NOW + 5_000)
        assertEquals(listOf(RecordType.WEIGHT, RecordType.BODY_FAT), a.map { it.type })
        assertEquals(a.map { it.id }, b.map { it.id })
        assertTrue(a.all { it.source == RecordSource.MIJIA })
    }

    @Test fun absurdValuesDroppedWhenConverting() {
        val recs = MijiaScreenParser.toRecords(MijiaReading(NOW, 700.0, 1.0), NOW)
        assertTrue(recs.isEmpty())
    }
}
