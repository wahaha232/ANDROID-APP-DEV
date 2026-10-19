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
    /** 伺服器時間，只在拉取時有值；推送時不送（由資料庫 trigger 蓋章）。 */
    @SerialName("synced_at") val syncedAt: String? = null,
)

fun photoObjectPath(userId: String, recordId: String) = "$userId/$recordId.jpg"

fun HealthRecord.toRemote(userId: String) = RemoteRecord(
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
    photoObject = if (source == RecordSource.OCR) photoObjectPath(userId, id) else null,
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
    )
}
