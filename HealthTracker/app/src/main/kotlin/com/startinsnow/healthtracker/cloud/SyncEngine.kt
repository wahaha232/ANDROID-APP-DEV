package com.startinsnow.healthtracker.cloud

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.db.SyncState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 同步引擎需要的本機資料存取（正式版由 Room 實作，測試用記憶體假實作）。 */
interface SyncStore {
    suspend fun pending(): List<HealthRecord>
    suspend fun get(id: String): HealthRecord?
    suspend fun saveFromRemote(record: HealthRecord)

    /** 只有在 updatedAt 沒變時才標為已同步（推送期間使用者若又改了這筆，要留待下次）。 */
    suspend fun markSynced(id: String, updatedAt: Long): Boolean
}

interface SessionStore {
    suspend fun load(): Session?
    suspend fun save(session: Session?)
    suspend fun cursor(): String?
    suspend fun setCursor(cursor: String?)
    suspend fun lastSyncAt(): Long?
    suspend fun setLastSyncAt(millis: Long)
    suspend fun lastResult(): String?
    suspend fun setLastResult(message: String?)
}

/** retryable：網路中斷、伺服器暫時錯誤這類「晚點再試可能就好」的失敗；沒登入、權限問題重試也沒用。 */
data class SyncResult(
    val pulled: Int = 0,
    val pushed: Int = 0,
    val failed: Int = 0,
    val error: String? = null,
    val retryable: Boolean = false,
) {
    val ok get() = error == null && failed == 0

    fun summary(): String = error ?: "同步完成：下載 $pulled 筆、上傳 $pushed 筆"
}

/**
 * 本機優先同步：先拉（雲端較新者覆蓋本機）、再推（本機待上傳者寫上雲端）。
 * 衝突規則：updatedAt 較新者勝（用戶端時間；單人自用、少數裝置，足夠）。
 * 單筆伺服器錯誤只計入 failed 並繼續；網路中斷、登入失效則整批中止，資料維持待同步，下次再試。
 */
class SyncEngine(
    private val store: SyncStore,
    private val api: RemoteApi,
    private val sessions: SessionStore,
    private val readPhoto: (String) -> ByteArray? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    suspend fun signIn(email: String, password: String) {
        sessions.save(api.signIn(email.trim(), password))
        sessions.setCursor(null) // 換帳號／重新登入就從頭拉，合併規則是冪等的
    }

    /** 登出只清除憑證與同步進度，本機資料與照片保留。 */
    suspend fun signOut() {
        sessions.save(null)
        sessions.setCursor(null)
    }

    suspend fun currentSession(): Session? = sessions.load()

    suspend fun hasPending(): Boolean = store.pending().isNotEmpty()

    suspend fun sync(): SyncResult = lock.withLock {
        val result = try {
            val r = withAuth { session -> doSync(session) }
            if (r.error == null) sessions.setLastSyncAt(clock())
            r
        } catch (e: CloudException) {
            SyncResult(error = e.message, retryable = isRetryable(e))
        } catch (e: Exception) {
            SyncResult(error = "同步失敗：${e.message ?: e::class.simpleName}")
        }
        sessions.setLastResult(result.summary())
        result
    }

    private suspend fun <T> withAuth(block: suspend (Session) -> T): T {
        var session = sessions.load() ?: throw CloudException(CloudErrorKind.NOT_SIGNED_IN, "尚未登入雲端")
        if (session.expiresAtMillis - EXPIRY_MARGIN_MS <= clock()) session = refresh(session)
        return try {
            block(session)
        } catch (e: CloudException) {
            if (e.kind != CloudErrorKind.UNAUTHORIZED) throw e
            block(refresh(session)) // token 被伺服器判定過期：續期後只重試一次
        }
    }

    private suspend fun refresh(old: Session): Session = try {
        api.refresh(old.refreshToken).also { sessions.save(it) }
    } catch (e: CloudException) {
        if (e.kind == CloudErrorKind.AUTH_FAILED) {
            sessions.save(null)
            throw CloudException(CloudErrorKind.NOT_SIGNED_IN, "登入已失效，請重新登入")
        }
        throw e
    }

    private suspend fun doSync(session: Session): SyncResult {
        val pulled = pull(session)
        return push(session).copy(pulled = pulled)
    }

    private suspend fun pull(session: Session): Int {
        val remote = api.fetchChanges(session.accessToken, sessions.cursor())
        var applied = 0
        var newCursor: String? = null
        for (r in remote) {
            r.syncedAt?.let { if (newCursor == null || it > newCursor!!) newCursor = it }
            val incoming = r.toLocal() ?: continue
            val local = store.get(incoming.id)
            when {
                local == null -> { store.saveFromRemote(incoming); applied++ }
                incoming.updatedAt > local.updatedAt -> {
                    store.saveFromRemote(incoming.copy(photoPath = local.photoPath)); applied++
                }
                incoming.updatedAt == local.updatedAt && local.syncState != SyncState.SYNCED ->
                    store.markSynced(local.id, local.updatedAt) // 雲端已有一模一樣的版本（例如上次推送後沒來得及標記）
                else -> {} // 本機較新：留給 push
            }
        }
        newCursor?.let { sessions.setCursor(it) }
        return applied
    }

    private suspend fun push(session: Session): SyncResult {
        var pushed = 0
        var failed = 0
        var anyRetryable = false
        var firstError: String? = null
        for (r in store.pending()) {
            try {
                pushOne(session, r)
                store.markSynced(r.id, r.updatedAt)
                pushed++
            } catch (e: CloudException) {
                if (e.kind == CloudErrorKind.NETWORK || e.kind == CloudErrorKind.UNAUTHORIZED ||
                    e.kind == CloudErrorKind.AUTH_FAILED
                ) throw e
                failed++
                if (isRetryable(e)) anyRetryable = true
                if (firstError == null) firstError = e.message
            }
        }
        return SyncResult(pushed = pushed, failed = failed, error = firstError?.let { "有 $failed 筆上傳失敗：$it" }, retryable = anyRetryable)
    }

    private suspend fun pushOne(session: Session, r: HealthRecord) {
        val path = photoObjectPath(session.userId, r.id)
        if (r.deletedAt != null) {
            if (r.source == RecordSource.OCR) {
                // 刪除紀錄時一併刪雲端照片；照片刪除失敗（非網路／登入問題）不阻擋紀錄本身同步。
                try {
                    api.deletePhoto(session.accessToken, path)
                } catch (e: CloudException) {
                    if (e.kind != CloudErrorKind.SERVER) throw e
                }
            }
        }
        var uploaded = false
        if (r.deletedAt == null && r.source == RecordSource.OCR && r.photoPath != null) {
            readPhoto(r.photoPath)?.let {
                api.uploadPhoto(session.accessToken, path, it)
                uploaded = true
            }
        }
        api.upsert(session.accessToken, r.toRemote(session.userId, photoObject = if (uploaded) path else null))
    }

    private companion object {
        const val EXPIRY_MARGIN_MS = 60_000L

        /**
         * 晚點再試可能就好的失敗：網路中斷、伺服器 5xx、429（太頻繁）、408（逾時）。
         * 其他 4xx（例如 403 沒權限、404 找不到資料表、400 資料不合規）重試只會白白消耗電量，直接放棄、等使用者處理。
         */
        fun isRetryable(e: CloudException): Boolean = when (e.kind) {
            CloudErrorKind.NETWORK -> true
            CloudErrorKind.SERVER -> e.httpCode == null || e.httpCode >= 500 || e.httpCode == 429 || e.httpCode == 408
            else -> false
        }
    }
}
