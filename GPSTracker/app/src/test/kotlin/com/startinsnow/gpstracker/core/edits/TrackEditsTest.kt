package com.startinsnow.gpstracker.core.edits

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackEditsTest {

    private fun tracksOf(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject["tracks"]!!.jsonObject

    @Test
    fun put_trimsText_marksDirty_andTreatsEmptyModeAsNone() {
        val m = TrackEdits.put(emptyMap(), "a", "  晨跑  ", " 河濱 \n", "")
        val e = m["a"]!!
        assertEquals("晨跑", e.title); assertEquals("河濱", e.notes); assertNull(e.mode); assertTrue(e.dirty)
        assertEquals("BICYCLE", TrackEdits.put(emptyMap(), "a", "", "", "BICYCLE")["a"]!!.mode)
    }

    @Test
    fun json_roundTrips_andBadInputIsHarmless() {
        val m = TrackEdits.put(TrackEdits.put(emptyMap(), "a", "標題", "備註", "CAR"), "b", "x", "", null)
        assertEquals(m, TrackEdits.parse(TrackEdits.toJson(m)))
        assertTrue(TrackEdits.parse(null).isEmpty()); assertTrue(TrackEdits.parse("").isEmpty()); assertTrue(TrackEdits.parse("not json").isEmpty())
        assertTrue(TrackEdits.parse("{\"a\": 5}").isEmpty())
        assertEquals("T", TrackEdits.parse("{\"a\":{\"title\":\"T\",\"future\":1}}")["a"]!!.title) // 未知欄位忽略
    }

    @Test
    fun markClean_keepsContent_butDropsDeleteMarkers() {
        var m = TrackEdits.put(emptyMap(), "a", "T", "", null)
        assertEquals(listOf("a"), TrackEdits.dirtyIds(m))
        m = TrackEdits.markClean(m, "a")
        assertFalse(m["a"]!!.dirty); assertEquals("T", m["a"]!!.title); assertTrue(TrackEdits.dirtyIds(m).isEmpty())
        // 全部清空 → 仍要把網頁上的移除，所以先留下「刪除標記」，同步完才真的移除
        m = TrackEdits.put(m, "a", "", "", null)
        assertTrue(m["a"]!!.isBlank && m["a"]!!.dirty)
        assertTrue(TrackEdits.markClean(m, "a").isEmpty())
        assertEquals(m, TrackEdits.markClean(m, "zzz"))
    }

    @Test
    fun merge_addsOnlyThisTrack_andKeepsOthersAndUnknownFields() {
        val existing = """{"tracks":{"other":{"title":"網站上改的","notes":"n","mode":"TRAIN","extra":"keep"}},"version":2}"""
        val out = MetaMerge.apply(existing, "mine", TrackEdit("我的", "備註", "WALKING", true))
        val t = tracksOf(out)
        assertEquals("網站上改的", t["other"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("keep", t["other"]!!.jsonObject["extra"]!!.jsonPrimitive.content)
        assertEquals("我的", t["mine"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("WALKING", t["mine"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("2", Json.parseToJsonElement(out).jsonObject["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun merge_updatesTheSameTrack_andKeepsItsUnknownFields() {
        val existing = """{"tracks":{"mine":{"title":"舊","notes":"舊備註","mode":"CAR","pinned":true}}}"""
        val out = MetaMerge.apply(existing, "mine", TrackEdit("新", "", null, true))
        val m = tracksOf(out)["mine"]!!.jsonObject
        assertEquals("新", m["title"]!!.jsonPrimitive.content)
        assertEquals("", m["notes"]!!.jsonPrimitive.content)
        assertFalse("mode 回到 App 判斷時要移除", m.containsKey("mode"))
        assertEquals("true", m["pinned"]!!.jsonPrimitive.content)
    }

    @Test
    fun merge_removesTheTrackWhenBlank_andSurvivesMissingOrBrokenFiles() {
        val existing = """{"tracks":{"mine":{"title":"x"},"other":{"title":"y"}}}"""
        val t = tracksOf(MetaMerge.apply(existing, "mine", TrackEdit(dirty = true)))
        assertFalse(t.containsKey("mine")); assertTrue(t.containsKey("other"))
        assertFalse(tracksOf(MetaMerge.apply(existing, "mine", null)).containsKey("mine"))
        assertEquals("T", tracksOf(MetaMerge.apply(null, "a", TrackEdit("T", "", null, true)))["a"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("T", tracksOf(MetaMerge.apply("broken {", "a", TrackEdit("T", "", null, true)))["a"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertTrue(tracksOf(MetaMerge.apply("[]", "a", null)).isEmpty())
    }
}
