package com.startinsnow.healthtracker.cloud

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.HealthRecordDao
import kotlinx.coroutines.flow.first

private val Context.cloudStore: DataStore<Preferences> by preferencesDataStore(name = "cloud_session")

/**
 * 登入憑證放在 App 私有的 DataStore（不進系統備份、不進版控）。
 * 沒有另外加密：資料靠手機本身的加密與 App 沙盒保護，憑證只代表單一自用帳號。
 */
class DataStoreSessionStore(private val context: Context) : SessionStore {
    private object K {
        val access = stringPreferencesKey("access_token")
        val refresh = stringPreferencesKey("refresh_token")
        val expires = longPreferencesKey("expires_at")
        val userId = stringPreferencesKey("user_id")
        val email = stringPreferencesKey("email")
        val cursor = stringPreferencesKey("pull_cursor")
        val lastSync = longPreferencesKey("last_sync_at")
        val lastResult = stringPreferencesKey("last_result")
    }

    override suspend fun load(): Session? {
        val p = context.cloudStore.data.first()
        return Session(
            accessToken = p[K.access] ?: return null,
            refreshToken = p[K.refresh] ?: return null,
            expiresAtMillis = p[K.expires] ?: 0L,
            userId = p[K.userId] ?: return null,
            email = p[K.email],
        )
    }

    override suspend fun save(session: Session?) {
        context.cloudStore.edit { p ->
            if (session == null) {
                p.remove(K.access); p.remove(K.refresh); p.remove(K.expires); p.remove(K.userId); p.remove(K.email)
            } else {
                p[K.access] = session.accessToken
                p[K.refresh] = session.refreshToken
                p[K.expires] = session.expiresAtMillis
                p[K.userId] = session.userId
                session.email?.let { p[K.email] = it } ?: p.remove(K.email)
            }
        }
    }

    override suspend fun cursor(): String? = context.cloudStore.data.first()[K.cursor]

    override suspend fun setCursor(cursor: String?) {
        context.cloudStore.edit { p -> cursor?.let { p[K.cursor] = it } ?: p.remove(K.cursor) }
    }

    override suspend fun lastSyncAt(): Long? = context.cloudStore.data.first()[K.lastSync]

    override suspend fun lastResult(): String? = context.cloudStore.data.first()[K.lastResult]

    override suspend fun setLastResult(message: String?) {
        context.cloudStore.edit { p -> message?.let { p[K.lastResult] = it } ?: p.remove(K.lastResult) }
    }

    override suspend fun setLastSyncAt(millis: Long) {
        context.cloudStore.edit { it[K.lastSync] = millis }
    }
}

class RoomSyncStore(private val dao: HealthRecordDao) : SyncStore {
    override suspend fun pending() = dao.pendingSync()
    override suspend fun get(id: String) = dao.get(id)
    override suspend fun saveFromRemote(record: HealthRecord) = dao.upsert(record)
    override suspend fun markSynced(id: String, updatedAt: Long) = dao.markSynced(id, updatedAt) > 0
}
