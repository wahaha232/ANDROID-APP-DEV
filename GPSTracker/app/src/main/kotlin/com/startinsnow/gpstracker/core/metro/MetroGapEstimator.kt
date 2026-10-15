package com.startinsnow.gpstracker.core.metro

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * GPS 中斷（例如搭捷運）時的「估算路徑」。與網站的 gt-metro.js 同一套邏輯：
 * 偵測軌跡裡的中斷段；若中斷前最後一點在 A 站附近、恢復後第一點在 B 站附近，
 * 就沿捷運路線（assets/metro.json，OpenStreetMap）找 A→B 的路徑，取代「兩點一線」的直線。
 * 純函式、不依賴 Android，可直接用 JVM 單元測試。只影響「顯示」：不改變原始軌跡、距離、均速。估算一律標示為估算。
 */
data class MetroStation(val id: Int, val name: String, val lon: Double, val lat: Double)
data class MetroEdge(val a: Int, val b: Int, val line: String, val meters: Double, val geometry: List<Pair<Double, Double>>)
data class MetroLine(val ref: String, val name: String, val color: String)

class MetroNetwork(val stations: List<MetroStation>, val edges: List<MetroEdge>, val lines: Map<String, MetroLine>) {
    internal class Hop(val to: Int, val edge: Int, val forward: Boolean)

    internal val adjacency: Map<Int, List<Hop>> by lazy {
        val adj = HashMap<Int, MutableList<Hop>>()
        stations.forEach { adj[it.id] = mutableListOf() }
        edges.forEachIndexed { idx, e ->
            if (adj.containsKey(e.a) && adj.containsKey(e.b)) {
                adj.getValue(e.a).add(Hop(e.b, idx, forward = true))
                adj.getValue(e.b).add(Hop(e.a, idx, forward = false))
            }
        }
        adj
    }
}

/** 用來偵測中斷的點：只需要位置與時間。 */
data class GapPoint(val lat: Double, val lon: Double, val timestampMs: Long)

/** 一段成功估算的中斷。coords 為 (lat, lon)，含兩端的真實 GPS 點。index＝中斷前最後一點的索引（中斷段是 index → index+1）。 */
data class MetroGapEstimate(
    val index: Int,
    val coords: List<Pair<Double, Double>>,
    val dtSec: Double,
    val lengthM: Double,
    val speedKmh: Double,
    val fromName: String,
    val toName: String,
    val lines: List<MetroLine>
)

/** 路線上的一點（含速度與海拔），給 [MetroGapEstimator.insertEstimated] 用。 */
data class RoutePt(val lat: Double, val lon: Double, val timestampMs: Long, val speedKmh: Double, val altitudeM: Double)

object MetroGapEstimator {
    /** 相鄰兩點時間差 ≥ 這個秒數、且直線距離 ≥ 這個公尺數，才可能是「訊號中斷」。 */
    const val MIN_GAP_SEC = 90.0
    const val MIN_GAP_M = 800.0

    /** 中斷兩端要在站點多遠以內才視為「從某站進、從某站出」。 */
    const val STATION_RADIUS_M = 800.0

    /** 估算路徑平均速度（含候車、停靠）合理範圍。 */
    const val MIN_SPEED_KMH = 5.0
    const val MAX_SPEED_KMH = 100.0

    /** 路線長度 / 直線距離 上限（避免不合理的繞路）。 */
    const val MAX_DETOUR_RATIO = 2.5

    /** 每次轉乘加的「等值公尺」（只影響選路，不計入回報的距離）。 */
    const val TRANSFER_PENALTY_M = 600.0

    fun parse(json: String): MetroNetwork {
        val root = Json.parseToJsonElement(json).jsonObject
        val stations = root.getValue("stations").jsonArray.map {
            val o = it.jsonObject
            MetroStation(
                id = o.getValue("id").jsonPrimitive.intOrNull ?: -1,
                name = o.getValue("n").jsonPrimitive.contentOrNull.orEmpty(),
                lon = o.getValue("x").jsonPrimitive.doubleOrNull ?: 0.0,
                lat = o.getValue("y").jsonPrimitive.doubleOrNull ?: 0.0
            )
        }
        val edges = root.getValue("edges").jsonArray.map {
            val o = it.jsonObject
            MetroEdge(
                a = o.getValue("a").jsonPrimitive.intOrNull ?: -1,
                b = o.getValue("b").jsonPrimitive.intOrNull ?: -1,
                line = o.getValue("l").jsonPrimitive.contentOrNull.orEmpty(),
                meters = o.getValue("m").jsonPrimitive.doubleOrNull ?: 0.0,
                geometry = (o["g"] as? JsonArray).orEmpty().map { c ->
                    val arr = c.jsonArray
                    (arr[0].jsonPrimitive.doubleOrNull ?: 0.0) to (arr[1].jsonPrimitive.doubleOrNull ?: 0.0)
                }
            )
        }
        val lines = (root["lines"] as? JsonObject).orEmpty().mapValues { (ref, v) ->
            val o = v.jsonObject
            MetroLine(ref, o["n"]?.jsonPrimitive?.contentOrNull ?: ref, o["c"]?.jsonPrimitive?.contentOrNull ?: "#888888")
        }
        return MetroNetwork(stations, edges, lines)
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    /** 需要估算的中斷段（index → index+1）。 */
    internal data class Gap(val index: Int, val from: GapPoint, val to: GapPoint, val dtSec: Double, val straightM: Double)

    internal fun findGaps(points: List<GapPoint>): List<Gap> {
        val out = ArrayList<Gap>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val dt = (b.timestampMs - a.timestampMs) / 1000.0
            if (dt < MIN_GAP_SEC) continue
            val d = distanceM(a.lat, a.lon, b.lat, b.lon)
            if (d >= MIN_GAP_M) out.add(Gap(i, a, b, dt, d))
        }
        return out
    }

    private fun nearestStation(net: MetroNetwork, lat: Double, lon: Double): MetroStation? {
        var best: MetroStation? = null
        var bd = Double.MAX_VALUE
        for (s in net.stations) {
            // 先用粗略的經緯度差排除，省掉大部分 haversine。
            if (abs(s.lat - lat) > 0.02 || abs(s.lon - lon) > 0.02) continue
            val d = distanceM(lat, lon, s.lat, s.lon)
            if (d < bd) {
                bd = d
                best = s
            }
        }
        return if (best != null && bd <= STATION_RADIUS_M) best else null
    }

    internal class RouteResult(val stations: List<Int>, val hops: List<Pair<Int, Boolean>>, val lines: List<String>, val transfers: Int)

    /**
     * 狀態式 Dijkstra：轉乘站已合併成同一節點，所以狀態＝(站, 上一段搭乘的路線)；換線（起點第一段不算）時
     * 成本加 [penalty]。懲罰只影響選路，不計入之後算出的實際距離。
     */
    internal fun route(net: MetroNetwork, from: Int, to: Int, penalty: Double = TRANSFER_PENALTY_M): RouteResult? {
        if (from == to || !net.adjacency.containsKey(from) || !net.adjacency.containsKey(to)) return null
        val lineIdx = HashMap<String, Int>()
        net.edges.forEach { e -> if (!lineIdx.containsKey(e.line)) lineIdx[e.line] = lineIdx.size + 1 } // 0 保留給「尚未搭乘」
        val nLines = lineIdx.size + 1
        fun key(station: Int, line: Int): Long = station.toLong() * nLines + line

        class Node(val cost: Double, val station: Int, val line: Int)
        class Prev(val fromKey: Long, val fromStation: Int, val hop: MetroNetwork.Hop, val transfer: Boolean)

        val cost = HashMap<Long, Double>()
        val prev = HashMap<Long, Prev>()
        val done = HashSet<Long>()
        val queue = PriorityQueue<Node>(compareBy<Node> { it.cost })
        val startKey = key(from, 0)
        cost[startKey] = 0.0
        queue.add(Node(0.0, from, 0))
        var goalKey: Long? = null
        while (queue.isNotEmpty()) {
            val u = queue.poll() ?: break
            val uKey = key(u.station, u.line)
            if (!done.add(uKey)) continue
            if (u.station == to) {
                goalKey = uKey
                break
            }
            for (h in net.adjacency.getValue(u.station)) {
                val edge = net.edges[h.edge]
                val l = lineIdx.getValue(edge.line)
                val transfer = u.line != 0 && l != u.line
                val nc = u.cost + edge.meters + if (transfer) penalty else 0.0
                val vKey = key(h.to, l)
                if (nc < (cost[vKey] ?: Double.MAX_VALUE)) {
                    cost[vKey] = nc
                    prev[vKey] = Prev(uKey, u.station, h, transfer)
                    queue.add(Node(nc, h.to, l))
                }
            }
        }
        val goal = goalKey ?: return null
        val hops = ArrayList<Pair<Int, Boolean>>()
        val stations = arrayListOf(to)
        var transfers = 0
        var cur = goal
        while (cur != startKey) {
            val p = prev.getValue(cur)
            hops.add(p.hop.edge to p.hop.forward)
            if (p.transfer) transfers++
            stations.add(p.fromStation)
            cur = p.fromKey
        }
        hops.reverse()
        stations.reverse()
        val lines = LinkedHashSet<String>()
        hops.forEach { lines.add(net.edges[it.first].line) }
        return RouteResult(stations, hops, lines.toList(), transfers)
    }

    /** 估算單一中斷段；失敗（站點不在範圍、找不到路、速度或繞路不合理）回傳 null。 */
    internal fun estimate(net: MetroNetwork, gap: Gap): MetroGapEstimate? {
        val a = nearestStation(net, gap.from.lat, gap.from.lon) ?: return null
        val b = nearestStation(net, gap.to.lat, gap.to.lon) ?: return null
        if (a.id == b.id) return null
        val r = route(net, a.id, b.id) ?: return null
        val coords = ArrayList<Pair<Double, Double>>()
        coords.add(gap.from.lat to gap.from.lon)
        r.hops.forEach { (edgeIdx, forward) ->
            val geo = net.edges[edgeIdx].geometry.let { if (forward) it else it.reversed() }
            geo.forEach { (lon, lat) -> coords.add(lat to lon) }
        }
        coords.add(gap.to.lat to gap.to.lon)
        var len = 0.0
        for (i in 1 until coords.size) {
            len += distanceM(coords[i - 1].first, coords[i - 1].second, coords[i].first, coords[i].second)
        }
        val speed = if (gap.dtSec > 0) len / gap.dtSec * 3.6 else 0.0
        if (len > gap.straightM * MAX_DETOUR_RATIO + 1500 || speed < MIN_SPEED_KMH || speed > MAX_SPEED_KMH) return null
        val lines = r.lines.map { ref -> net.lines[ref] ?: MetroLine(ref, ref, "#888888") }
        return MetroGapEstimate(gap.index, coords, gap.dtSec, len, speed, a.name, b.name, lines)
    }

    /** 對整條軌跡（依時間排序的可信點）回傳每一段「成功估算」的中斷；沒有捷運資料或估算失敗的中斷不會出現在結果裡。 */
    fun annotate(points: List<GapPoint>, net: MetroNetwork?): List<MetroGapEstimate> {
        if (net == null) return emptyList()
        return findGaps(points).mapNotNull { estimate(net, it) }
    }

    /**
     * 為 3D 飛越把估算路徑「插入」點陣列：沿路徑依距離比例內插時間與海拔，速度用該段平均，讓標記沿著路線走。
     * 只插入中斷段中間的點（兩端是原本的 GPS 點）。
     */
    fun insertEstimated(points: List<RoutePt>, estimates: List<MetroGapEstimate>): List<RoutePt> {
        if (estimates.isEmpty()) return points
        val byIndex = estimates.associateBy { it.index }
        val out = ArrayList<RoutePt>(points.size + estimates.sumOf { it.coords.size })
        for (i in points.indices) {
            val est = if (i > 0) byIndex[i - 1] else null
            if (est != null && est.coords.size > 2) {
                val a = points[i - 1]
                val b = points[i]
                val cs = est.coords
                val cum = DoubleArray(cs.size)
                for (m in 1 until cs.size) {
                    cum[m] = cum[m - 1] + distanceM(cs[m - 1].first, cs[m - 1].second, cs[m].first, cs[m].second)
                }
                val total = cum.last()
                for (m in 1 until cs.size - 1) {
                    val f = if (total > 0) cum[m] / total else 0.0
                    out.add(
                        RoutePt(
                            lat = cs[m].first,
                            lon = cs[m].second,
                            timestampMs = a.timestampMs + ((b.timestampMs - a.timestampMs) * f).toLong(),
                            speedKmh = est.speedKmh,
                            altitudeM = a.altitudeM + (b.altitudeM - a.altitudeM) * f
                        )
                    )
                }
            }
            out.add(points[i])
        }
        return out
    }
}
