package com.startinsnow.gpstracker.core.metro

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetroGapEstimatorTest {

    // ---- 合成的小路網：0-1(X,1000) 1-3(Y,1000) 與 0-2(X,1200) 2-3(X,1200)。0→3 有「換一次車但較短」與「不換車但較長」兩條路。
    private fun station(id: Int, lat: Double, lon: Double) = MetroStation(id, "S$id", lon, lat)
    private fun edge(a: Int, b: Int, line: String, m: Double, aS: MetroStation, bS: MetroStation) =
        MetroEdge(a, b, line, m, listOf(aS.lon to aS.lat, bS.lon to bS.lat))

    private fun smallNet(): MetroNetwork {
        val s = listOf(station(0, 25.00, 121.50), station(1, 25.01, 121.51), station(2, 25.00, 121.52), station(3, 25.02, 121.52))
        val edges = listOf(
            edge(0, 1, "X", 1000.0, s[0], s[1]),
            edge(1, 3, "Y", 1000.0, s[1], s[3]),
            edge(0, 2, "X", 1200.0, s[0], s[2]),
            edge(2, 3, "X", 1200.0, s[2], s[3])
        )
        return MetroNetwork(s, edges, mapOf("X" to MetroLine("X", "X線", "#ff0000"), "Y" to MetroLine("Y", "Y線", "#0000ff")))
    }

    @Test
    fun `transfer penalty prefers the route with fewer transfers`() {
        val net = smallNet()
        val noPenalty = MetroGapEstimator.route(net, 0, 3, penalty = 0.0)!!
        assertEquals(1, noPenalty.transfers)
        assertEquals(listOf(0, 1, 3), noPenalty.stations)
        val withPenalty = MetroGapEstimator.route(net, 0, 3)!!
        assertEquals(0, withPenalty.transfers)
        assertEquals(listOf(0, 2, 3), withPenalty.stations)
    }

    @Test
    fun `first leg is not counted as a transfer`() {
        val r = MetroGapEstimator.route(smallNet(), 0, 1)!!
        assertEquals(0, r.transfers)
        assertEquals(listOf("X"), r.lines)
    }

    @Test
    fun `route is null for identical or unknown stations`() {
        val net = smallNet()
        assertNull(MetroGapEstimator.route(net, 1, 1))
        assertNull(MetroGapEstimator.route(net, 0, 99))
    }

    @Test
    fun `short or near gaps are not treated as outages`() {
        val t0 = 1_000_000L
        // 只隔 60 秒（低於 90 秒門檻）
        val a = listOf(GapPoint(25.0, 121.5, t0), GapPoint(25.02, 121.52, t0 + 60_000))
        assertTrue(MetroGapEstimator.findGaps(a).isEmpty())
        // 隔了很久但只移動約 100 公尺（低於 800 公尺門檻）
        val b = listOf(GapPoint(25.0, 121.5, t0), GapPoint(25.0009, 121.5, t0 + 300_000))
        assertTrue(MetroGapEstimator.findGaps(b).isEmpty())
        // 兩者都達標
        val c = listOf(GapPoint(25.0, 121.5, t0), GapPoint(25.02, 121.52, t0 + 300_000))
        assertEquals(1, MetroGapEstimator.findGaps(c).size)
    }

    @Test
    fun `gap between two stations is estimated along the line`() {
        val net = smallNet()
        val t0 = 0L
        // 站 0 附近出發、站 3 附近恢復；4 分鐘（240 秒）
        val pts = listOf(GapPoint(25.0001, 121.5001, t0), GapPoint(25.0199, 121.5199, t0 + 240_000))
        val est = MetroGapEstimator.annotate(pts, net).single()
        assertEquals(0, est.index)
        assertEquals("S0", est.fromName)
        assertEquals("S3", est.toName)
        // 兩端是真實 GPS 點
        assertEquals(25.0001, est.coords.first().first, 1e-9)
        assertEquals(25.0199, est.coords.last().first, 1e-9)
        assertTrue(est.speedKmh in MetroGapEstimator.MIN_SPEED_KMH..MetroGapEstimator.MAX_SPEED_KMH)
        assertEquals(listOf("X"), est.lines.map { it.ref })
    }

    @Test
    fun `unreasonable speed or missing stations give no estimate`() {
        val net = smallNet()
        // 時間太長 → 平均速度低於 5 km/h
        val slow = listOf(GapPoint(25.0001, 121.5001, 0), GapPoint(25.0199, 121.5199, 5 * 3600_000L))
        assertTrue(MetroGapEstimator.annotate(slow, net).isEmpty())
        // 兩端都離任何站很遠
        val far = listOf(GapPoint(26.0, 122.5, 0), GapPoint(26.02, 122.52, 300_000))
        assertTrue(MetroGapEstimator.annotate(far, net).isEmpty())
        // 沒有捷運資料
        assertTrue(MetroGapEstimator.annotate(slow, null).isEmpty())
    }

    @Test
    fun `insertEstimated adds interior points with interpolated time and altitude`() {
        val net = smallNet()
        val pts = listOf(GapPoint(25.0001, 121.5001, 0), GapPoint(25.0199, 121.5199, 240_000))
        val est = MetroGapEstimator.annotate(pts, net)
        val route = listOf(
            RoutePt(25.0001, 121.5001, 0, 4.0, 10.0),
            RoutePt(25.0199, 121.5199, 240_000, 4.0, 30.0)
        )
        val out = MetroGapEstimator.insertEstimated(route, est)
        assertEquals(route.size + est.single().coords.size - 2, out.size)
        assertEquals(route.first(), out.first())
        assertEquals(route.last(), out.last())
        assertTrue("時間遞增", out.zipWithNext().all { (a, b) -> b.timestampMs >= a.timestampMs })
        assertTrue("海拔在兩端之間", out.all { it.altitudeM in 10.0..30.0 })
        assertEquals(route, MetroGapEstimator.insertEstimated(route, emptyList()))
    }

    // ---- 真實資料（App 內建的 metro.json）----
    private fun realNetwork(): MetroNetwork {
        val file = listOf("src/main/assets/metro.json", "app/src/main/assets/metro.json").map(::File).first { it.isFile }
        return MetroGapEstimator.parse(file.readText(Charsets.UTF_8))
    }

    @Test
    fun `bundled metro data parses`() {
        val net = realNetwork()
        assertTrue("站數", net.stations.size > 100)
        assertTrue("邊數", net.edges.size > 100)
        assertTrue(net.lines.containsKey("BL"))
        assertTrue("每條邊都有幾何", net.edges.all { it.geometry.size >= 2 })
    }

    @Test
    fun `real network estimates Zhongxiao Fuxing to Taipei Main`() {
        val net = realNetwork()
        // 忠孝復興（25.04131, 121.54376）附近 → 台北車站（25.04775, 121.51711）附近，約 11 分鐘
        val pts = listOf(GapPoint(25.0414, 121.5440, 0), GapPoint(25.0480, 121.5175, 11 * 60_000L))
        val found = MetroGapEstimator.annotate(pts, net).firstOrNull()
        assertNotNull(found)
        val est = found!!
        assertTrue(est.lines.any { it.ref == "BL" })
        assertTrue(est.lengthM >= MetroGapEstimator.distanceM(25.0414, 121.5440, 25.0480, 121.5175))
        assertTrue(est.speedKmh in MetroGapEstimator.MIN_SPEED_KMH..MetroGapEstimator.MAX_SPEED_KMH)
    }

    // ---- 與網站 gt-metro.js 對拍：同一批真實軌跡，結果（中斷索引、站名、路線、長度、座標數）必須一致。
    // 預期值由網站的 JS 實作算出（node 執行 GTMetro.annotate）；找不到網站資料夾時略過（例如只拉了 App 目錄）。
    private data class Expected(val id: String, val index: Int, val from: String, val to: String, val lengthM: Int, val line: String, val coords: Int)

    @Test
    fun `matches the website implementation on real tracks`() {
        val dir = listOf("../../gpstracker-web/data", "../gpstracker-web/data", "gpstracker-web/data").map(::File).firstOrNull { it.isDirectory }
        assumeTrue("找不到網站資料夾", dir != null)
        val expected = listOf(
            Expected("40cd1987-3d41-4406-a4bf-c9bcec40111d", 414, "新埔", "頂埔", 9443, "BL", 60),
            Expected("71670052-35a0-43f7-9bb8-038843d53f73", 0, "永寧", "新埔", 7537, "BL", 49),
            Expected("d36d77b8-bb8f-42ec-a1ef-c4aea12ca093", 413, "新埔", "亞東醫院", 3466, "BL", 21),
            Expected("d36d77b8-bb8f-42ec-a1ef-c4aea12ca093", 414, "亞東醫院", "頂埔", 6126, "BL", 41),
            Expected("d414e684-d99c-4797-ab46-ddded4bb83d4", 307, "新埔", "頂埔", 9389, "BL", 60),
            Expected("d45abd5d-5d5c-4b98-83e1-63cb9d254ce9", 405, "新埔", "板橋", 1229, "BL", 9),
            Expected("d45abd5d-5d5c-4b98-83e1-63cb9d254ce9", 406, "板橋", "頂埔", 8280, "BL", 53)
        )
        val net = MetroGapEstimator.parse(File(dir, "metro.json").readText(Charsets.UTF_8))
        expected.groupBy { it.id }.forEach { (id, exps) ->
            val track = Json.parseToJsonElement(File(dir, "$id.json").readText(Charsets.UTF_8)).jsonObject
            val pts = track.getValue("points").jsonArray.map { p ->
                val a = p.jsonArray
                // 網站點格式：[lon, lat, alt, km/h, 秒]
                GapPoint(a[1].jsonPrimitive.doubleOrNull!!, a[0].jsonPrimitive.doubleOrNull!!, (a[4].jsonPrimitive.doubleOrNull!! * 1000).toLong())
            }
            val got = MetroGapEstimator.annotate(pts, net)
            assertEquals("$id 中斷數", exps.size, got.size)
            exps.zip(got).forEach { (e, g) ->
                assertEquals("$id 索引", e.index, g.index)
                assertEquals("$id 起站", e.from, g.fromName)
                assertEquals("$id 迄站", e.to, g.toName)
                assertEquals("$id 路線", listOf(e.line), g.lines.map { it.ref })
                assertEquals("$id 長度", e.lengthM.toDouble(), g.lengthM, 1.0)
                assertEquals("$id 座標數", e.coords, g.coords.size)
            }
        }
    }
}
