package com.startinsnow.gpstracker.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.startinsnow.gpstracker.core.edits.TrackEdit
import com.startinsnow.gpstracker.core.edits.TrackEdits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.trackEditsStore by preferencesDataStore(name = "track_edits")

/**
 * 紀錄的編輯（標題、備註、移動方式）。放在獨立的小型儲存裡，不改資料庫結構（避免遷移風險），
 * 也不動原始 GPS 紀錄與統計。編輯儲存後會自動同步到網頁的 meta.json（見 TrackEditsSync）。
 */
class TrackEditsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val key = stringPreferencesKey("edits_json")

    val edits: Flow<Map<String, TrackEdit>> = appContext.trackEditsStore.data.map { TrackEdits.parse(it[key]) }

    suspend fun current(): Map<String, TrackEdit> = edits.first()

    suspend fun save(trackId: String, title: String, notes: String, mode: String?) {
        appContext.trackEditsStore.edit { p -> p[key] = TrackEdits.toJson(TrackEdits.put(TrackEdits.parse(p[key]), trackId, title, notes, mode)) }
    }

    suspend fun markClean(trackId: String) {
        appContext.trackEditsStore.edit { p -> p[key] = TrackEdits.toJson(TrackEdits.markClean(TrackEdits.parse(p[key]), trackId)) }
    }
}
