package com.startinsnow.healthtracker.importer

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.db.SyncState
import com.startinsnow.healthtracker.data.repository.RecordValidator
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 一筆從 Health Connect 讀到的資料（還沒轉成本機紀錄），保留來源 App 套件名以便使用者挑選。 */
data class HcSample(
    val hcId: String,
    val type: RecordType,
    val value: Double,
    val measuredAt: Long,
    val originPackage: String,
)

object HealthConnectMapping {
    /** 轉成本機紀錄；數值不合理（例如單位錯誤造成的離譜值）回傳 null，不匯入。 */
    fun toRecord(s: HcSample, now: Long): HealthRecord? {
        if (RecordValidator.validate(s.type, s.value, null) != null) return null
        return HealthRecord(
            id = ImportIds.fromHealthConnect(s.hcId),
            type = s.type,
            value = s.value,
            measuredAt = s.measuredAt,
            source = RecordSource.HEALTH_CONNECT,
            note = "來源：${s.originPackage}",
            syncState = SyncState.PENDING,
            createdAt = now,
            updatedAt = now,
        )
    }

    fun countByOrigin(samples: List<HcSample>): Map<String, Int> =
        samples.groupingBy { it.originPackage }.eachCount().toSortedMap()
}

/**
 * 從 Health Connect 讀體重與體脂率。
 * 先「掃描」列出實際有資料的來源 App，由使用者挑要匯入哪些，避免重複來源（例如多個 App 都寫入同一筆體重）。
 */
class HealthConnectImporter(private val context: Context) {
    companion object {
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
        )
        private const val LOOKBACK_DAYS = 365L
        private const val PAGE_SIZE = 1000
        private const val MAX_PAGES = 100
    }

    fun isAvailable(): Boolean =
        runCatching { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)

    suspend fun grantedPermissions(): Set<String> = runCatching {
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
    }.getOrDefault(emptySet())

    /** 還缺哪些權限；只要求缺少的，避免已授權體重卻被體脂率卡住、每次都重跳授權。 */
    suspend fun missingPermissions(): Set<String> = PERMISSIONS - grantedPermissions()

    suspend fun hasPermissions(): Boolean = runCatching { missingPermissions().isEmpty() }.getOrDefault(false)

    /**
     * 讀體重與體脂率。只讀「已授權」的類型（允許只授權其中一種），
     * 分頁有上限，避免 pageToken 異常時無限迴圈；結果依時間新到舊排序。
     */
    suspend fun scan(now: Instant = Instant.now()): List<HcSample> {
        val client = HealthConnectClient.getOrCreate(context)
        val granted = grantedPermissions()
        val filter = TimeRangeFilter.between(now.minus(LOOKBACK_DAYS, ChronoUnit.DAYS), now)
        val out = ArrayList<HcSample>()

        if (HealthPermission.getReadPermission(WeightRecord::class) in granted) {
            readAll(out, MAX_PAGES) { token ->
                client.readRecords(ReadRecordsRequest(WeightRecord::class, filter, pageSize = PAGE_SIZE, pageToken = token))
                    .also { resp ->
                        resp.records.mapTo(out) {
                            HcSample(it.metadata.id, RecordType.WEIGHT, it.weight.inKilograms, it.time.toEpochMilli(), it.metadata.dataOrigin.packageName)
                        }
                    }.pageToken
            }
        }
        if (HealthPermission.getReadPermission(BodyFatRecord::class) in granted) {
            readAll(out, MAX_PAGES) { token ->
                client.readRecords(ReadRecordsRequest(BodyFatRecord::class, filter, pageSize = PAGE_SIZE, pageToken = token))
                    .also { resp ->
                        resp.records.mapTo(out) {
                            HcSample(it.metadata.id, RecordType.BODY_FAT, it.percentage.value, it.time.toEpochMilli(), it.metadata.dataOrigin.packageName)
                        }
                    }.pageToken
            }
        }
        return out.distinctBy { it.hcId }.sortedByDescending { it.measuredAt }
    }

    private inline fun readAll(out: MutableList<HcSample>, maxPages: Int, read: (String?) -> String?) {
        var token: String? = null
        var pages = 0
        do {
            token = read(token)?.takeIf { it.isNotBlank() }
            pages++
        } while (token != null && pages < maxPages)
    }
}
