package com.startinsnow.healthtracker.cloud

import android.content.Context
import com.startinsnow.healthtracker.BuildConfig
import com.startinsnow.healthtracker.data.db.HealthRecordDao
import com.startinsnow.healthtracker.ocr.PhotoStore
import java.io.File

/** 組裝正式環境的同步元件。雲端網址與金鑰來自 local.properties（見 app/build.gradle.kts）。 */
class CloudSync(context: Context, dao: HealthRecordDao) {
    val pendingCount: kotlinx.coroutines.flow.Flow<Int> = dao.observePendingCount()

    val isConfigured: Boolean = BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val engine: SyncEngine? = if (isConfigured) {
        SyncEngine(
            store = RoomSyncStore(dao),
            api = SupabaseApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY),
            sessions = DataStoreSessionStore(context.applicationContext),
            readPhoto = { path -> File(path).takeIf { it.isFile }?.readBytes() },
            savePhoto = { id, bytes -> PhotoStore.saveBytes(context.applicationContext, id, bytes)?.absolutePath },
            photoExists = { path -> path != null && File(path).isFile },
        )
    } else {
        null
    }

    val sessions: SessionStore = DataStoreSessionStore(context.applicationContext)
}
