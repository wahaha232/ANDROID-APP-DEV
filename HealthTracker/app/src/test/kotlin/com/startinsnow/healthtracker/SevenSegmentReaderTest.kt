package com.startinsnow.healthtracker

import com.startinsnow.healthtracker.ocr.SevenSegmentReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

/** 用程式畫出斜體七段數字（模擬血糖機液晶），驗證能讀回正確數字。 */
class SevenSegmentReaderTest {
    private val segments = mapOf(
        0 to "abcdef", 1 to "bc", 2 to "abdeg", 3 to "abcdg", 4 to "bcfg",
        5 to "acdfg", 6 to "acdefg", 7 to "abc", 8 to "abcdefg", 9 to "abcdfg",
    )

    private class Img(val w: Int, val h: Int, val px: IntArray)

    private fun render(text: String, slant: Double = 0.14, seed: Long = 1, noise: Boolean = true): Img {
        val dh = 150
        val cw = 85
        val t = 14
        val gap = 3
        val pitch = cw + 24
        val margin = 50
        val w = margin * 2 + pitch * text.length + 60
        val h = dh + margin * 2
        val rnd = Random(seed)
        // 底色有左暗右亮的光線漸層
        val px = IntArray(w * h) { i -> 150 - ((w - i % w) * 25 / w) }
        fun fill(x0: Double, y0: Double, x1: Double, y1: Double, ox: Double) {
            for (y in y0.toInt()..y1.toInt()) {
                val shift = slant * (dh / 2.0 - y)
                for (x in (x0 + shift + ox).toInt()..(x1 + shift + ox).toInt()) {
                    if (x in 0 until w && y + margin in 0 until h) px[(y + margin) * w + x] = 55
                }
            }
        }
        text.forEachIndexed { i, ch ->
            val ox = margin + i * pitch.toDouble()
            val on = segments.getValue(ch - '0')
            val d = dh.toDouble()
            val c = cw.toDouble()
            // 橫條與直條在水平方向錯開（模擬液晶的斜切接縫），各自是獨立的線段
            val hx0 = t + 6.0
            val hx1 = c - t - 6
            if ('a' in on) fill(hx0, 0.0, hx1, t.toDouble(), ox)
            if ('g' in on) fill(hx0, d / 2 - t / 2, hx1, d / 2 + t / 2, ox)
            if ('d' in on) fill(hx0, d - t, hx1, d, ox)
            val top = 4.0
            val mid1 = d / 2 - t / 2 - 3
            val mid2 = d / 2 + t / 2 + 3
            val bot = d - 4
            if ('f' in on) fill(0.0, top, t.toDouble(), mid1, ox)
            if ('b' in on) fill(c - t, top, c, mid1, ox)
            if ('e' in on) fill(0.0, mid2, t.toDouble(), bot, ox)
            if ('c' in on) fill(c - t, mid2, c, bot, ox)
        }
        if (noise) {
            // 單位底線、左下的刮痕線、散落的小點（都很細，不該被當成線段）
            for (x in margin + 100 until margin + 190) for (y in h - 40 until h - 38) px[y * w + x] = 70
            for (x in 5 until 90) for (y in h - 60 until h - 58) px[y * w + x] = 60
            repeat(40) { val x = rnd.nextInt(w - 3); val y = rnd.nextInt(h - 3); px[y * w + x] = 70 }
        }
        return Img(w, h, px)
    }

    private fun read(text: String, slant: Double = 0.14, noise: Boolean = true): String? {
        val i = render(text, slant, noise = noise)
        return SevenSegmentReader.read(i.px, i.w, i.h)
    }

    @Test fun readsTheCommonCase() = assertEquals("108", read("108"))

    @Test fun readsEveryDigit() {
        val wrong = listOf("234", "567", "890", "123", "456", "789").filter { read(it) != it }.map { "$it→${read(it)}" }
        assertEquals("全部讀對", emptyList<String>(), wrong)
    }

    @Test fun readsTwoDigitValues() {
        assertEquals("95", read("95"))
        assertEquals("72", read("72"))
    }

    @Test fun readsWithoutSlantAndWithMoreSlant() {
        assertEquals("187", read("187", slant = 0.0))
        assertEquals("187", read("187", slant = 0.22))
    }

    @Test fun readsLeadingOnes() {
        assertEquals("112", read("112"))
        assertEquals("311", read("311"))
    }

    @Test fun blankImageGivesNull() {
        assertNull(SevenSegmentReader.read(IntArray(200 * 200) { 140 }, 200, 200))
    }

    @Test fun glucoseConversionChecksLengthAndRange() {
        assertEquals(108.0, SevenSegmentReader.toGlucose("108")!!, 0.0)
        assertNull(SevenSegmentReader.toGlucose("8"))
        assertNull(SevenSegmentReader.toGlucose("1234"))
        assertNull(SevenSegmentReader.toGlucose("09"))
        assertNull(SevenSegmentReader.toGlucose(null))
    }
}
