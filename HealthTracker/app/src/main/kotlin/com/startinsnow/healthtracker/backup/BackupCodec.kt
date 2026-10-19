package com.startinsnow.healthtracker.backup

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.db.SyncState
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/** 備份檔裡的一筆紀錄。不含裝置專屬的照片路徑與同步狀態（還原後一律重新標為待同步）。 */
@Serializable
data class BackupRecord(
    val id: String,
    val type: String,
    val value: Double,
    val value2: Double? = null,
    val measuredAt: Long,
    val source: String,
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
private data class Envelope(
    val format: String,
    val version: Int,
    val createdAt: Long,
    val encrypted: Boolean,
    // 未加密
    val records: List<BackupRecord>? = null,
    // 加密（AES-256-GCM，金鑰由密碼經 PBKDF2-HMAC-SHA256 推導）
    val kdf: String? = null,
    val iterations: Int? = null,
    val salt: String? = null,
    val iv: String? = null,
    val data: String? = null,
)

/**
 * 備份檔格式（JSON）。可選密碼加密：沒有密碼就是可讀的 JSON，有密碼則整份紀錄用 AES-GCM 加密，
 * 密碼錯誤或檔案被改動都會在解密時失敗（GCM 驗證標籤），不會還原出錯誤資料。
 */
object BackupCodec {
    const val FORMAT = "healthtracker-backup"
    const val VERSION = 1
    internal const val PBKDF2_ITERATIONS = 210_000

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val listSerializer = ListSerializer(BackupRecord.serializer())
    private val rng = SecureRandom()

    fun toBackupRecord(r: HealthRecord) = BackupRecord(
        id = r.id, type = r.type.name, value = r.value, value2 = r.value2, measuredAt = r.measuredAt,
        source = r.source.name, note = r.note, createdAt = r.createdAt, updatedAt = r.updatedAt, deletedAt = r.deletedAt,
    )

    /** 未知的類型／來源（來自較新版本）回傳 null，由呼叫端略過。 */
    fun toHealthRecord(b: BackupRecord): HealthRecord? {
        val type = RecordType.entries.firstOrNull { it.name == b.type } ?: return null
        val source = RecordSource.entries.firstOrNull { it.name == b.source } ?: RecordSource.IMPORT
        return HealthRecord(
            id = b.id, type = type, value = b.value, value2 = b.value2, measuredAt = b.measuredAt,
            source = source, note = b.note, syncState = SyncState.PENDING,
            createdAt = b.createdAt, updatedAt = b.updatedAt, deletedAt = b.deletedAt, photoPath = null,
        )
    }

    fun encode(records: List<HealthRecord>, password: String?, now: Long = System.currentTimeMillis()): ByteArray {
        val list = records.map(::toBackupRecord)
        val envelope = if (password.isNullOrEmpty()) {
            Envelope(FORMAT, VERSION, now, encrypted = false, records = list)
        } else {
            val salt = ByteArray(16).also(rng::nextBytes)
            val iv = ByteArray(12).also(rng::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, PBKDF2_ITERATIONS), GCMParameterSpec(128, iv))
            val sealed = cipher.doFinal(json.encodeToString(listSerializer, list).toByteArray(Charsets.UTF_8))
            val b64 = Base64.getEncoder()
            Envelope(
                FORMAT, VERSION, now, encrypted = true, kdf = "PBKDF2WithHmacSHA256", iterations = PBKDF2_ITERATIONS,
                salt = b64.encodeToString(salt), iv = b64.encodeToString(iv), data = b64.encodeToString(sealed),
            )
        }
        return json.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
    }

    /** 是否為加密備份（用來決定要不要請使用者輸入密碼）。不是備份檔會丟 BackupException。 */
    fun isEncrypted(bytes: ByteArray): Boolean = parse(bytes).encrypted

    fun decode(bytes: ByteArray, password: String?): List<BackupRecord> {
        val env = parse(bytes)
        if (!env.encrypted) return env.records ?: throw BackupException("備份檔內容是空的")
        if (password.isNullOrEmpty()) throw BackupException("這個備份有加密，請輸入密碼")
        val iterations = env.iterations ?: throw BackupException("備份檔格式不完整")
        if (iterations < 10_000 || iterations > 5_000_000) throw BackupException("備份檔的加密參數不合理")
        try {
            val b64 = Base64.getDecoder()
            val salt = b64.decode(env.salt ?: throw BackupException("備份檔格式不完整"))
            val iv = b64.decode(env.iv ?: throw BackupException("備份檔格式不完整"))
            val data = b64.decode(env.data ?: throw BackupException("備份檔格式不完整"))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
            return json.decodeFromString(listSerializer, String(cipher.doFinal(data), Charsets.UTF_8))
        } catch (e: AEADBadTagException) {
            throw BackupException("密碼錯誤，或備份檔已損毀")
        } catch (e: IllegalArgumentException) {
            throw BackupException("備份檔已損毀")
        }
    }

    private fun parse(bytes: ByteArray): Envelope {
        val env = try {
            json.decodeFromString(Envelope.serializer(), String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("這不是有效的備份檔")
        }
        if (env.format != FORMAT) throw BackupException("這不是本 App 的備份檔")
        if (env.version > VERSION) throw BackupException("備份檔來自較新的版本，請先更新 App")
        return env
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, "AES")
    }
}

/** 還原時如何處理單筆：不存在就新增；備份較新才覆蓋；其餘略過（刪除與較新的修改優先，不會被舊備份復活）。 */
enum class RestoreAction { INSERT, UPDATE, SKIP }

fun planRestore(existing: HealthRecord?, incoming: HealthRecord): RestoreAction = when {
    existing == null -> RestoreAction.INSERT
    incoming.updatedAt > existing.updatedAt -> RestoreAction.UPDATE
    else -> RestoreAction.SKIP
}
