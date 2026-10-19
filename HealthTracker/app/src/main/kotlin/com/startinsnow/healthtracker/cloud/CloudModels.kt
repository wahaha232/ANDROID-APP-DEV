package com.startinsnow.healthtracker.cloud

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.db.SyncState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 登入工作階段。refreshToken 與 accessToken 都只存在 App 私有儲存。 */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val userId: String,
    val email: String?,
)

enum class CloudErrorKind { NOT_CONFIGURED, NOT_SIGNED_IN, AUTH_FAILED, UNAUTHORIZED, NETWORK, SERVER }

class CloudException(val kind: CloudErrorKind, message: String, val httpCode: Int? = null) : Exception(message)

/** Supabase 資料表 health_records 的一列（欄位名稱需與 supabase/schema.sql 一致）。 */
@Serializable
data class RemoteRecord(
    val id: String,
    val type: String,
    val value: Double,
    val value2: Double? = null,
    @SerialName("measured_at") val measuredAt: Long,
    val source: String = "MANUAL",
    val note: String = "",
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    @SerialName("photo_object") val photoObject: String? = null,
    /** 血糖測量情境（FASTING／BEFORE_MEAL／AFTER_MEAL／BEDTIME）；需要先在 Supabase 加上 context 欄位。 */
    val context: String? = null,
    /** 伺服器時間，只在拉取時有值；推送時不送（由資料庫 trigger 蓋章）。 */
    @SerialName("synced_at") val syncedAt: String? = null,
)

fun photoObjectPath(userId: String, recordId: String) = "$userId/$recordId.jpg"

/**
 * photoObject 由呼叫端決定：只有這次確實把照片上傳到雲端時才帶路徑；
 * 本機沒有照片（還原備份、檔案遺失）就傳 null，上傳時整個欄位省略，不會蓋掉雲端原有的照片連結，
 * 也不會憑空產生指向不存在檔案的連結。
 */
fun HealthRecord.toRemote(userId: String, photoObject: String? = null) = RemoteRecord(
    id = id,
    type = type.name,
    value = value,
    value2 = value2,
    measuredAt = measuredAt,
    source = source.name,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    photoObject = photoObject,
    context = context?.name,
)

/** 未知的類型或來源（例如日後網站新增的種類）回傳 null，由呼叫端略過，不讓整批同步失敗。 */
fun RemoteRecord.toLocal(): HealthRecord? {
    val t = RecordType.entries.firstOrNull { it.name == type } ?: return null
    val s = RecordSource.entries.firstOrNull { it.name == source } ?: RecordSource.IMPORT
    return HealthRecord(
        id = id,
        type = t,
        value = value,
        value2 = value2,
        measuredAt = measuredAt,
        source = s,
        note = note,
        syncState = SyncState.SYNCED,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        photoPath = null,
        context = context?.let { n -> com.startinsnow.healthtracker.data.db.MeasureContext.entries.firstOrNull { it.name == n } },
    )
}
