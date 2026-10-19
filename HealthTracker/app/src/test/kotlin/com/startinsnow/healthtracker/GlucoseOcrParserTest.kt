package com.startinsnow.healthtracker

import com.startinsnow.healthtracker.ocr.GlucoseOcrParser
import com.startinsnow.healthtracker.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlucoseOcrParserTest {
    private fun values(vararg lines: OcrLine) = GlucoseOcrParser.candidates(lines.toList()).map { it.value }

    @Test fun biggestDigitsWin() {
        val v = values(OcrLine("10/19 09:27", 20), OcrLine("112", 120), OcrLine("mg/dL", 25))
        assertEquals(listOf(112.0), v)
    }

    @Test fun datesTimesAndYearsAreIgnored() {
        assertTrue(values(OcrLine("2026/10/19", 30), OcrLine("09:27", 30), OcrLine("10-19", 30)).isEmpty())
    }

    @Test fun unitNeighbourBoostsRanking() {
        // 兩行同高，緊鄰 mg/dL 的那行應排前面。
        val v = values(OcrLine("88", 50), OcrLine("134", 50), OcrLine("mg/dL", 20))
        assertEquals(134.0, v.first(), 0.0)
    }

    @Test fun lookalikeLettersAreFixed() {
        assertEquals(listOf(105.0), values(OcrLine("1O5", 100)))
        assertEquals(listOf(110.0), values(OcrLine("ll0", 100)))
    }

    @Test fun pureLettersNotConverted() {
        assertTrue(values(OcrLine("OIl", 100), OcrLine("FORA", 40)).isEmpty())
    }

    @Test fun outOfRangeDropped() {
        assertTrue(values(OcrLine("10", 100), OcrLine("999", 100)).isEmpty())
    }

    @Test fun duplicatesCollapsed() {
        assertEquals(listOf(120.0), values(OcrLine("120", 100), OcrLine("120", 40)))
    }

    @Test fun emptyInput() = assertTrue(GlucoseOcrParser.candidates(emptyList()).isEmpty())
}
