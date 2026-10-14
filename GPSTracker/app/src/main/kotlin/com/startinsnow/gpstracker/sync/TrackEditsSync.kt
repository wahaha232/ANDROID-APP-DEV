package com.startinsnow.gpstracker.sync

import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.core.edits.TrackEdits
import com.startinsnow.gpstracker.data.prefs.TrackEditsRepository
import kotlinx.coroutines.flow.first

/** 把 App 內的編輯（標題、備註、移動方式）同步到網頁儀表板的 meta.json。 */
object TrackEditsSync {

    enum class Outcome {
        /** 已同步到記錄網頁。 */
        UPLOADED,
        /** 沒有需要同步的編輯。 */
        NOTHING,
        /** 還沒設定網頁上傳（GitHub Token／儲存庫）。 */
        NOT_CONFIGURED,
        /** 設定為僅限 Wi-Fi，目前不是 Wi-Fi。 */
        WIFI_ONLY,
        /** 這筆紀錄還沒上傳到網站；上傳之後會一併同步。 */
        NOT_ON_WEB
    }

    /** 同步單一筆；失敗（網路、權限…）回傳 failure，編輯仍標記為「尚未同步」，之後會重試。 */
    suspend fun flush(app: GpsTrackerApplication, trackId: String): Result<Outcome> = runCatching {
        val repo = TrackEditsRepository(app)
        val edit = repo.current()[trackId]
        if (edit == null || !edit.dirty) return@runCatching Outcome.NOTHING
        val s = app.settingsRepository.settings.first()
        if (s.githubToken.isBlank() || s.webRepo.isBlank()) return@runCatching Outcome.NOT_CONFIGURED
        if (s.webWifiOnly && !WebDashboardUploader.isOnWifi(app)) return@runCatching Outcome.WIFI_ONLY
        val track = app.trackRepository.getTrack(trackId)
        if (track == null || track.webUploadedAtMs == null) return@runCatching Outcome.NOT_ON_WEB
        WebDashboardUploader().updateMeta(s.githubToken, s.webRepo, s.webBranch, s.webDataPath, trackId, edit).getOrThrow()
        repo.markClean(trackId)
        Outcome.UPLOADED
    }

    /** 重試所有尚未同步的編輯（App 開啟首頁時呼叫）。個別失敗不影響其他筆。 */
    suspend fun flushAllDirty(app: GpsTrackerApplication) {
        TrackEdits.dirtyIds(TrackEditsRepository(app).current()).forEach { id -> runCatching { flush(app, id) } }
    }
}
