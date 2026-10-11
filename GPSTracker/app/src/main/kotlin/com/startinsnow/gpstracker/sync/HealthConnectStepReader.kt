package com.startinsnow.gpstracker.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

/**
 * 步數來源（經 Health Connect）。
 *
 * 小米 Zepp Life / Mi Fitness，以及 Google Fit，都會把步數同步到 Android 的 Health Connect；
 * 本類別只讀取 Health Connect 中「指定來源 App」的步數，不會混入其他來源，也不需要 Google OAuth
 * （因此不受 OAuth 用戶端／簽章 SHA-1 不符造成的 DEVELOPER_ERROR 影響）。
 *
 * 前置條件（缺一不可）：
 * - 裝置已安裝／啟用 Health Connect（Android 14 以上為系統內建）。
 * - 來源 App（Zepp Life 或 Google Fit）已開啟同步到 Health Connect。
 * - 使用者已同意本 App 讀取步數（android.permission.health.READ_STEPS）。
 *
 * 未滿足時讀取會失敗或為 0，呼叫端應退回裝置感測器步數。
 */
class HealthConnectStepReader(private val context: Context) {

    /** 小米 Zepp Life（com.xiaomi.hm.health）與 Mi Fitness（com.xiaomi.wearable）。 */
    private val zeppOrigins = setOf(
        DataOrigin("com.xiaomi.hm.health"),
        DataOrigin("com.xiaomi.wearable")
    )

    /** Google Fit。 */
    private val googleFitOrigins = setOf(
        DataOrigin("com.google.android.apps.fitness")
    )

    fun isAvailable(): Boolean =
        runCatching { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)

    suspend fun hasPermission(): Boolean = runCatching {
        val client = HealthConnectClient.getOrCreate(context)
        client.permissionController.getGrantedPermissions().contains(READ_PERMISSION)
    }.getOrDefault(false)

    /** 小米 Zepp Life / Mi Fitness 來源步數。 */
    suspend fun zeppSteps(startMs: Long, endMs: Long): Result<Long> = stepsFrom(zeppOrigins, startMs, endMs)

    /** Google Fit 來源步數（經 Health Connect，免 Google OAuth）。 */
    suspend fun googleFitSteps(startMs: Long, endMs: Long): Result<Long> = stepsFrom(googleFitOrigins, startMs, endMs)

    private suspend fun stepsFrom(origins: Set<DataOrigin>, startMs: Long, endMs: Long): Result<Long> = runCatching {
        val client = HealthConnectClient.getOrCreate(context)
        val start = Instant.ofEpochMilli(startMs)
        val end = Instant.ofEpochMilli(endMs)
        var total = 0L
        origins.forEach { origin ->
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    dataOriginFilter = setOf(origin)
                )
            )
            response.records.forEach { total += it.count }
        }
        total
    }

    companion object {
        val READ_PERMISSION: String = HealthPermission.getReadPermission(StepsRecord::class)
    }
}
