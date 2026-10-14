package com.startinsnow.gpstracker.sync

import org.json.JSONObject
import com.startinsnow.gpstracker.data.db.TrackEntity
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
            payload = WebDashboardUploader.buildPayload(data, settings.privacyZones, settings.webUploadPhotos)
        ).getOrThrow()
        app.trackRepository.setWebUploadedAt(trackId, System.currentTimeMillis())
        Outcome.UPLOADED
    }

    /** 本機記得「上傳過」、但網站的 manifest 已經沒有的軌跡 id（純邏輯，可測試）。 */
    fun missingFromManifest(uploadedLocally: Collection<String>, manifestIds: Set<String>): List<String> =
        uploadedLocally.filter { it !in manifestIds }

    /**
     * 找出「先前上傳過、現在在網站被刪除」的本機紀錄。網站讀不到或 manifest 不是有效 JSON 時一律回傳空清單
     * （或失敗），不能因為網路或網站暫時異常就提示使用者刪除。
     */
    suspend fun findDeletedOnWeb(app: GpsTrackerApplication): Result<List<TrackEntity>> = runCatching {
        val settings = app.settingsRepository.settings.first()
        if (settings.githubToken.isBlank() || settings.webRepo.isBlank()) return@runCatching emptyList()
        if (settings.webWifiOnly && !WebDashboardUploader.isOnWifi(app)) return@runCatching emptyList()
        val local = app.trackRepository.getWebUploadedTracks()
        if (local.isEmpty()) return@runCatching emptyList()

        val text = WebDashboardUploader().getFileText(
            settings.githubToken, settings.webRepo, settings.webBranch, "${settings.webDataPath.trim('/')}/manifest.json"
        ).getOrThrow() ?: return@runCatching emptyList()
        val tracks = JSONObject(text).optJSONArray("tracks") ?: return@runCatching emptyList()
        val ids = (0 until tracks.length()).map { tracks.getJSONObject(it).optString("trackId") }.toSet()
        val missing = missingFromManifest(local.map { it.trackId }, ids).toSet()
        local.filter { it.trackId in missing }
    }
}
