package com.startinsnow.gpstracker.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalFeatureAvailabilityApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 步數來源（經 Health Connect）。
 *
 * 小米 Zepp Life / Mi Fitness / Zepp，以及 Google Fit，都會把步數同步到 Android 的 Health Connect；
 * 本類別讀取 Health Connect 中指定來源 App 的步數，不需要 Google OAuth
 * （因此不受 OAuth 用戶端／簽章 SHA-1 不符造成的 DEVELOPER_ERROR 影響）。
 *
 * 前置條件（缺一不可）：
 * - 裝置已安裝／啟用 Health Connect（Android 14 以上為系統內建）。
 * - 來源 App（Zepp Life 或 Google Fit）已開啟同步到 Health Connect，且手環已同步到手機。
 * - 使用者已同意本 App 讀取步數（android.permission.health.READ_STEPS）。
 * - 在背景（錄製結束、WorkManager 補同步）讀取時，另需 READ_HEALTH_DATA_IN_BACKGROUND。
 *
 * 步數一律使用 aggregate（COUNT_TOTAL）：Health Connect 會依時間比例切分跨越邊界的記錄，
 * 也會處理多來源重複，避免「整筆記錄加總」造成的邊界多算。
 */
class HealthConnectStepReader(private val context: Context) {

    /** 小米 Zepp Life（com.xiaomi.hm.health）、Mi Fitness（com.xiaomi.wearable）與 Zepp（com.huami.watch.hmwatchmanager）。 */
    private val zeppOrigins = setOf(
        DataOrigin("com.xiaomi.hm.health"),
        DataOrigin("com.xiaomi.wearable"),
        DataOrigin("com.huami.watch.hmwatchmanager")
    )

    /** Google Fit。 */
    private val googleFitOrigins = setOf(
        DataOrigin("com.google.android.apps.fitness")
    )

    fun isAvailable(): Boolean =
        runCatching { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)

    private suspend fun grantedPermissions(): Set<String> =
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()

    suspend fun hasPermission(): Boolean =
        runCatching { grantedPermissions().contains(READ_PERMISSION) }.getOrDefault(false)

    /** 目前 Health Connect 版本是否支援「背景讀取」權限。 */
    @OptIn(ExperimentalFeatureAvailabilityApi::class)
    suspend fun isBackgroundReadSupported(): Boolean = runCatching {
        HealthConnectClient.getOrCreate(context).features
            .getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    /** 是否已授權背景讀取（錄製結束時 App 多半不在前景，沒有它讀取會被系統拒絕）。 */
    suspend fun hasBackgroundPermission(): Boolean =
        runCatching { grantedPermissions().contains(BACKGROUND_PERMISSION) }.getOrDefault(false)

    /** 小米 Zepp Life / Mi Fitness / Zepp 來源步數。 */
    suspend fun zeppSteps(startMs: Long, endMs: Long): Result<Long> = aggregateSteps(startMs, endMs, zeppOrigins)

    /** Google Fit 來源步數（經 Health Connect，免 Google OAuth）。 */
    suspend fun googleFitSteps(startMs: Long, endMs: Long): Result<Long> = aggregateSteps(startMs, endMs, googleFitOrigins)

    /** 診斷用：近 [days] 天各來源 App 的步數統計（package -> 步數），會逐頁讀完全部記錄。 */
    suspend fun stepsByOriginLastDays(days: Int): Result<Map<String, Long>> = runCatching {
        val client = HealthConnectClient.getOrCreate(context)
        val end = Instant.now()
        val start = end.minus(days.toLong(), ChronoUnit.DAYS)
        val map = sortedMapOf<String, Long>()
        var pageToken: String? = null
        var pages = 0
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken
                )
            )
            response.records.forEach { r ->
                val pkg = r.metadata.dataOrigin.packageName
                map[pkg] = (map[pkg] ?: 0L) + r.count
            }
            pageToken = response.pageToken
            pages++
        } while (!pageToken.isNullOrEmpty() && pages < MAX_PAGES)
        map
    }

    private suspend fun aggregateSteps(startMs: Long, endMs: Long, origins: Set<DataOrigin>): Result<Long> = runCatching {
        val client = HealthConnectClient.getOrCreate(context)
        val result = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs)),
                dataOriginFilter = origins
            )
        )
        result[StepsRecord.COUNT_TOTAL] ?: 0L
    }

    companion object {
        val READ_PERMISSION: String = HealthPermission.getReadPermission(StepsRecord::class)
        const val BACKGROUND_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        private const val PAGE_SIZE = 5000
        private const val MAX_PAGES = 40
    }
}
