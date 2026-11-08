package com.startinsnow.healthtracker.cloud

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.startinsnow.healthtracker.BuildConfig
import com.startinsnow.healthtracker.data.db.HealthRecordDao
import com.startinsnow.healthtracker.ocr.PhotoStore
import java.io.File

/**
 * 組裝雲端同步與更新用的元件。
 *
 * 設定來源（優先順序）：
 * 1. 使用者在 App 內「雲端同步」輸入的 Supabase 網址與 anon 公開金鑰（存在本機 SharedPreferences）。
 * 2. 建置時寫在 local.properties 的 supabase.url／supabase.anonKey。
 *
 * anon key 本來就是給用戶端用的公開金鑰，資料安全靠 Supabase RLS；絕不要放 service_role 金鑰。
 * engine 用 Compose mutableStateOf 保存，使用者在設定頁存好設定後，各畫面會立即重組、不必重開 App。
 */
class CloudSync(context: Context, private val dao: HealthRecordDao) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val pendingCount: kotlinx.coroutines.flow.Flow<Int> = dao.observePendingCount()

    var engine by mutableStateOf<SyncEngine?>(null)
        private set

    val isConfigured: Boolean get() = engine != null

    val sessions: SessionStore = DataStoreSessionStore(appContext)

    init {
        engine = build(readUrl(), readKey())
    }

    /** 目前使用的設定（給畫面顯示；金鑰不直接顯示）。 */
    fun configuredUrl(): String? = readUrl()

    /** App 內設定或建置時的 anon key（只給雲端更新等內部功能使用，不顯示在畫面）。 */
    fun configuredAnonKey(): String? = readKey()

    fun hasRuntimeConfig(): Boolean = prefs.getString(KEY_URL, null)?.isNotBlank() == true

    /** 儲存使用者輸入的雲端設定；回傳錯誤訊息，成功回傳 null。 */
    fun configure(url: String, anonKey: String): String? {
        val u = normalizeSupabaseUrl(url)
        val k = anonKey.trim()
        if (!u.startsWith("https://") || !u.contains(".supabase.co")) return "網址格式應為 https://xxxx.supabase.co"
        if (k.length < 30) return "anon 金鑰格式不正確（請貼上 Supabase 的 anon public key）"
        prefs.edit().putString(KEY_URL, u).putString(KEY_KEY, k).apply()
        engine = build(u, k)
        return null
    }

    /** 移除 App 內設定，回到建置時的預設值（如果有的話）。 */
    fun clearConfig() {
        prefs.edit().remove(KEY_URL).remove(KEY_KEY).apply()
        engine = build(readUrl(), readKey())
    }

    private fun readUrl(): String? =
        (prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.SUPABASE_URL.trim().takeIf { it.startsWith("https://") && it.isNotBlank() })
            ?.let { normalizeSupabaseUrl(it) }
            ?.takeIf { it.startsWith("https://") && it.contains(".supabase.co") }

    private fun readKey(): String? =
        prefs.getString(KEY_KEY, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.SUPABASE_ANON_KEY.trim().takeIf { it.isNotBlank() }

    private fun build(url: String?, key: String?): SyncEngine? {
        if (url.isNullOrBlank() || key.isNullOrBlank()) return null
        return SyncEngine(
            store = RoomSyncStore(dao = dao),
            api = SupabaseApi(url, key),
            sessions = DataStoreSessionStore(appContext),
            readPhoto = { path -> File(path).takeIf { it.isFile }?.readBytes() },
            savePhoto = { id, bytes -> PhotoStore.saveBytes(appContext, id, bytes)?.absolutePath },
            photoExists = { path -> path != null && File(path).isFile },
        )
    }

    private companion object {
        const val PREFS = "cloud_config"
        const val KEY_URL = "url"
        const val KEY_KEY = "anon_key"
    }
}

/**
 * 只保留 Supabase 專案根網址。
 * 使用者常直接貼上 REST 端點（https://xxx.supabase.co/rest/v1）或後面帶其他路徑；
 * 程式原本又會自己接 /rest/v1，結果變成 /rest/v1/rest/v1 而 404。這裡統一取出 scheme + host。
 */
internal fun normalizeSupabaseUrl(input: String): String {
    val trimmed = input.trim()
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
    if (uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank()) {
        return "https://${uri.host}"
    }
    // 沒有 scheme 或 URI 解析失敗時，至少去掉常見的 API 後綴與結尾斜線
    return trimmed
        .trimEnd('/')
        .replace(Regex("(?i)/(rest|auth|storage)/v1$"), "")
        .trimEnd('/')
}
