package com.startinsnow.gpstracker.sync

import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.export.ExportTrackData
import kotlinx.coroutines.flow.first

/**
 * 依目前設定把一筆已存在的軌跡上傳到網頁儀表板（錄製結束與步數補同步後共用）。
 * 上傳具冪等性：同一 trackId 會覆寫軌跡檔並更新 manifest 對應項目。
 */
object WebDashboardSync {

    enum class Outcome {
        /** 未啟用自動上傳、沒有 Token 或沒有儲存庫設定。 */
        NOT_CONFIGURED,

        /** 設定為僅 Wi-Fi，但目前不是 Wi-Fi。 */
        SKIPPED_WIFI_ONLY,

        /** 事後補傳時發現網頁上已沒有這筆（使用者在網頁刪除了），不重傳以免讓它復活。 */
        SKIPPED_NOT_ON_WEB,

        UPLOADED
    }

    /**
     * @param onlyIfAlreadyOnWeb true 時只更新「網頁上還存在」的軌跡（步數補同步用）；
     *   錄製剛結束的第一次上傳要傳 false（此時網頁上本來就還沒有）。
     */
    suspend fun uploadIfConfigured(
        app: GpsTrackerApplication,
        trackId: String,
        onlyIfAlreadyOnWeb: Boolean = false
    ): Result<Outcome> = runCatching {
        val settings = app.settingsRepository.settings.first()
        if (!settings.webUploadEnabled || settings.githubToken.isBlank() || settings.webRepo.isBlank()) {
            return@runCatching Outcome.NOT_CONFIGURED
        }
        if (settings.webWifiOnly && !WebDashboardUploader.isOnWifi(app)) {
            return@runCatching Outcome.SKIPPED_WIFI_ONLY
        }
        if (onlyIfAlreadyOnWeb) {
            val onWeb = WebDashboardUploader().isTrackInManifest(
                token = settings.githubToken,
                repo = settings.webRepo,
                branch = settings.webBranch,
                dataPath = settings.webDataPath,
                trackId = trackId
            ).getOrThrow()
            if (!onWeb) return@runCatching Outcome.SKIPPED_NOT_ON_WEB
        }
        val track = app.trackRepository.getTrack(trackId) ?: return@runCatching Outcome.NOT_CONFIGURED
        val data = ExportTrackData(
            track = track,
            points = app.trackRepository.getPoints(trackId),
            photos = app.trackRepository.getPhotos(trackId),
            segments = app.trackRepository.getSegments(trackId),
            outages = app.trackRepository.getOutages(trackId)
        )
        WebDashboardUploader().uploadTrack(
            token = settings.githubToken,
            repo = settings.webRepo,
            branch = settings.webBranch,
            dataPath = settings.webDataPath,
            trackId = trackId,
            payload = WebDashboardUploader.buildPayload(data)
        ).getOrThrow()
        Outcome.UPLOADED
    }
}
