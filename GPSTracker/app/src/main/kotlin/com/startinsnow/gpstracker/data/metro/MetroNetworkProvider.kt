package com.startinsnow.gpstracker.data.metro

import android.content.Context
import com.startinsnow.gpstracker.core.metro.GapPoint
import com.startinsnow.gpstracker.core.metro.MetroGapEstimate
import com.startinsnow.gpstracker.core.metro.MetroGapEstimator
import com.startinsnow.gpstracker.core.metro.MetroNetwork
import com.startinsnow.gpstracker.data.db.TrackPointEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 讀取內建的捷運路網（assets/metro.json，約 55 KB）並快取；讀取或解析失敗就當作沒有（中斷段維持原樣）。 */
object MetroNetworkProvider {
    private val lock = Mutex()
    private var cached: MetroNetwork? = null
    private var failed = false

    suspend fun get(context: Context): MetroNetwork? = lock.withLock {
        cached?.let { return@withLock it }
        if (failed) return@withLock null
        withContext(Dispatchers.IO) {
            runCatching {
                context.applicationContext.assets.open("metro.json").use { MetroGapEstimator.parse(it.readBytes().toString(Charsets.UTF_8)) }
            }.getOrNull()
        }.also { net -> if (net == null) failed = true else cached = net }
    }

    /** 軌跡裡「GPS 中斷（搭捷運）」的估算路徑；[points] 只取可信點並依時間排序。沒有中斷或估算不出來就回傳空清單。 */
    suspend fun estimateGaps(context: Context, points: List<TrackPointEntity>): List<MetroGapEstimate> {
        val trusted = trustedSorted(points)
        if (trusted.size < 2) return emptyList()
        val net = get(context) ?: return emptyList()
        return withContext(Dispatchers.Default) {
            MetroGapEstimator.annotate(trusted.map { GapPoint(it.latitude, it.longitude, it.timestampMs) }, net)
        }
    }

    /** 估算結果的索引是相對於這個清單（可信點、依時間排序），呼叫端要用同一份才對得上。 */
    fun trustedSorted(points: List<TrackPointEntity>): List<TrackPointEntity> =
        points.filter { it.reliability == "TRUSTED" }.sortedBy { it.timestampMs }
}
