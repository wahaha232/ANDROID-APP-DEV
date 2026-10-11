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
 * 小米 Zepp Life 步數來源。
 *
 * Zepp Life（前 Mi Fit）與 Mi Fitness 會把步數同步到 Android 的 Health Connect；本類別只讀取
 * Health Connect 中來自這些 App 的步數，作為該趟活動的步數，不會混入其他來源。
 *
 * 前置條件（缺一不可）：
 * - 裝置已安裝／啟用 Health Connect（Android 14 以上為系統內建）。
 * - 已在 Zepp Life 開啟「同步到 Health Connect」。
 * - 使用者已同意本 App 讀取步數（android.permission.health.READ_STEPS）。
 *
 * 未滿足時 [stepsBetween] 會回傳失敗或 0，呼叫端應退回裝置感測器步數。
 */
class HealthConnectStepReader(private val context: Context) {

    /** Zepp Life（com.xiaomi.hm.health）與 Mi Fitness（com.xiaomi.wearable）的資料來源。 */
    private val zeppOrigins = setOf(
        DataOrigin("com.xiaomi.hm.health"),
        DataOrigin("com.xiaomi.wearable")
    )

    fun isAvailable(): Boolean =
        runCatching { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)

    suspend fun hasPermission(): Boolean = runCatching {
        val client = HealthConnectClient.getOrCreate(context)
        client.permissionController.getGrantedPermissions().contains(READ_PERMISSION)
    }.getOrDefault(false)

    /** 讀取 [startMs, endMs) 之間、來源為 Zepp Life／Mi Fitness 的步數。 */
    suspend fun stepsBetween(startMs: Long, endMs: Long): Result<Long> = runCatching {
        val client = HealthConnectClient.getOrCreate(context)
        val start = Instant.ofEpochMilli(startMs)
        val end = Instant.ofEpochMilli(endMs)
        var total = 0L
        zeppOrigins.forEach { origin ->
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
