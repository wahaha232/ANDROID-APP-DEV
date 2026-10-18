package com.startinsnow.gpstracker.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.startinsnow.gpstracker.export.ExportTrackData
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把一筆紀錄上傳到 GitHub repo 的資料夾（預設 `gpstracker-web/data/`），
 * 供 GitHub Pages 的網頁儀表板讀取。全部使用 GitHub Contents API，0 元、無後端。
 *
 * 需要一組 fine-grained PAT（Contents: Read and write）。Token 由使用者在 App 設定內填入。
 */
class WebDashboardUploader(
    private val client: OkHttpClient = SharedClient.instance
) {

    /** 要上傳的照片縮圖：path 相對於資料夾（photos/<trackId>/<photoId>.jpg）。 */
    class PhotoFile(val path: String, val bytes: ByteArray)

    data class Payload(val trackJson: String, val manifestEntry: JSONObject, val photoFiles: List<PhotoFile> = emptyList())

    suspend fun uploadTrack(
        token: String,
        repo: String,
        branch: String,
        dataPath: String,
        trackId: String,
        payload: Payload
    ): Result<Unit> = withContext(Dispatchers.IO) {
        writeLock.withLock { runCatching {
            val dir = dataPath.trim('/')
            // 照片縮圖先上傳（軌跡檔參照它們）；縮圖不會改變，已存在就略過。
            payload.photoFiles.forEach { pf ->
                if (getContent(token, repo, branch, "$dir/${pf.path}") == null) {
                    putBytes(token, repo, branch, "$dir/${pf.path}", pf.bytes, "GPS Tracker: add photo thumbnail for $trackId")
                }
            }
            putContent(token, repo, branch, "$dir/$trackId.json", payload.trackJson, "GPS Tracker: add track $trackId")

            val manifestPath = "$dir/manifest.json"
            // 自動上傳與手動上傳（或兩台裝置）同時更新 manifest 時，GitHub 會回 409/422（sha 已過期）。
            // 重新讀取最新 manifest 再合併重試，否則軌跡檔會成為孤兒、儀表板看不到。
            var attempt = 0
            while (true) {
                try {
                    val existing = getContent(token, repo, branch, manifestPath)
                    val manifest = existing?.let { JSONObject(it.content) }
                        ?: JSONObject().put("updatedAt", 0L).put("tracks", JSONArray())
                    val oldTracks = manifest.optJSONArray("tracks") ?: JSONArray()
                    val merged = JSONArray()
                    for (i in 0 until oldTracks.length()) {
                        val entry = oldTracks.getJSONObject(i)
                        if (entry.optString("trackId") != trackId) merged.put(entry)
                    }
                    merged.put(payload.manifestEntry)
                    manifest.put("tracks", merged)
                    manifest.put("updatedAt", System.currentTimeMillis())
                    putContent(token, repo, branch, manifestPath, manifest.toString(), "GPS Tracker: update manifest", existing?.sha)
                    break
                } catch (e: GitHubApiException) {
                    attempt++
                    if (isWriteConflict(e) && attempt < MAX_MANIFEST_ATTEMPTS) { backoff(attempt); continue }
                    throw e
                }
            }
        } }
    }

    /**
     * 這筆軌跡目前是否還在網頁的 manifest 裡。用在「事後補傳」（步數補同步）：
     * 使用者若已在網頁「紀錄管理」刪除，就不能再把它傳回去讓它復活。
     */
    suspend fun isTrackInManifest(
        token: String,
        repo: String,
        branch: String,
        dataPath: String,
        trackId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = getContent(token, repo, branch, "${dataPath.trim('/')}/manifest.json")
                ?: return@runCatching false
            val tracks = JSONObject(existing.content).optJSONArray("tracks") ?: return@runCatching false
            (0 until tracks.length()).any { tracks.getJSONObject(it).optString("trackId") == trackId }
        }
    }

    /** 讀取 repo 內檔案的文字內容；檔案不存在回傳 null。 */
    suspend fun getFileText(token: String, repo: String, branch: String, path: String): Result<String?> =
        withContext(Dispatchers.IO) { runCatching { getContent(token, repo, branch, path)?.content } }

    /** 寫入（新增或覆寫）repo 內的任意檔案，例如逐日步數 steps.json。 */
    suspend fun putFile(
        token: String,
        repo: String,
        branch: String,
        path: String,
        content: String,
        message: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        writeLock.withLock { runCatching { putContent(token, repo, branch, path, content, message) } }
    }

    /** 把一筆編輯合併進網頁的 meta.json（只動這一筆，其他筆原封不動）；遇到寫入衝突（409／422）重讀再合併重試。 */
    suspend fun updateMeta(
        token: String, repo: String, branch: String, dataPath: String, trackId: String,
        edit: com.startinsnow.gpstracker.core.edits.TrackEdit?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        writeLock.withLock { runCatching {
            val path = dataPath.trim('/') + "/meta.json"
            var attempt = 0
            while (true) {
                try {
                    val existing = getContent(token, repo, branch, path)
                    val merged = com.startinsnow.gpstracker.core.edits.MetaMerge.apply(existing?.content, trackId, edit)
                    putContent(token, repo, branch, path, merged, "GPS Tracker: update notes for $trackId", existing?.sha)
                    break
                } catch (e: GitHubApiException) {
                    attempt++
                    if (isWriteConflict(e) && attempt < MAX_MANIFEST_ATTEMPTS) { backoff(attempt); continue }
                    throw e
                }
            }
        } }
    }

    private data class ContentFile(val content: String, val sha: String?)

    private fun getContent(token: String, repo: String, branch: String, path: String): ContentFile? {
        val url = "https://api.github.com/repos/$repo/contents/$path?ref=$branch"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "GPSTracker-Android")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw GitHubApiException(response.code, response.body?.string().orEmpty())
            val json = JSONObject(response.body?.string().orEmpty())
            val raw = json.optString("content").replace("\n", "").replace("\r", "")
            val decoded = if (raw.isEmpty()) "" else String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
            return ContentFile(decoded, json.optString("sha").ifBlank { null })
        }
    }

    private fun putContent(token: String, repo: String, branch: String, path: String, content: String, message: String, sha: String? = null) =
        putBytes(token, repo, branch, path, content.toByteArray(Charsets.UTF_8), message, sha)

    private fun putBytes(token: String, repo: String, branch: String, path: String, bytes: ByteArray, message: String, sha: String? = null) {
        // 呼叫端已帶 sha（讀-改-寫）時，衝突要由呼叫端重讀再合併，這裡不能自己重試。
        // 沒帶 sha 的單純覆寫則可以直接重試：GitHub 的 409 "is at X but expected Y" 是分支最新 commit
        // 在寫入瞬間被別的寫入推進了（兩個同步同時跑、自動同步 workflow 剛好提交），重試時重新查 sha 即可。
        if (sha != null) return putBytesOnce(token, repo, branch, path, bytes, message, sha)
        var attempt = 0
        while (true) {
            try {
                return putBytesOnce(token, repo, branch, path, bytes, message, null)
            } catch (e: GitHubApiException) {
                attempt++
                if (!isWriteConflict(e) || attempt >= MAX_WRITE_ATTEMPTS) throw e
                backoff(attempt)
            }
        }
    }

    private fun putBytesOnce(token: String, repo: String, branch: String, path: String, bytes: ByteArray, message: String, sha: String?) {
        // 未提供 sha 時先查既有檔案：GitHub Contents API 對「路徑已存在但未帶 sha」的更新會回 422，
        // 造成同一筆紀錄重複上傳或失敗重試時無法寫入。補上 sha 讓上傳具冪等性。
        val effectiveSha = sha ?: getContent(token, repo, branch, path)?.sha
        val body = JSONObject().apply {
            put("message", message)
            put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("branch", branch)
            if (effectiveSha != null) put("sha", effectiveSha)
        }.toString()
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/contents/$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "GPSTracker-Android")
            .put(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw GitHubApiException(response.code, response.body?.string().orEmpty())
        }
    }

    class GitHubApiException(val code: Int, val body: String) : Exception("GitHub API error $code: ${body.take(200)}")

    private fun isWriteConflict(e: GitHubApiException) = e.code == 409 || e.code == 422

    /** 衝突後稍等再重試（遞增並加亂數），避免兩邊同時重試又撞在一起。 */
    private fun backoff(attempt: Int) {
        Thread.sleep(500L * attempt + (0..400).random())
    }

    companion object {
        private const val MAX_POINTS = 20000

        /** 同一個 App 內所有寫入 GitHub 的動作排隊執行，避免自己的兩個同步互相造成分支衝突。 */
        private val writeLock = Mutex()
        private const val MAX_MANIFEST_ATTEMPTS = 5
        private const val MAX_WRITE_ATTEMPTS = 5

        private object SharedClient {
            val instance: OkHttpClient by lazy {
                OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
            }
        }

        fun isOnWifi(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        /** 把一筆紀錄轉成精簡 JSON（陣列格式以縮小體積）。 */
        fun buildPayload(
            data: ExportTrackData,
            privacyZones: List<com.startinsnow.gpstracker.core.privacy.PrivacyZone> = emptyList(),
            uploadPhotos: Boolean = false,
            deviceName: String = ""
        ): Payload {
            val track = data.track
            val startMs = track.startTimeMs
            val trusted = data.points
                .filter { it.reliability == "TRUSTED" }
                .sortedBy { it.timestampMs }
            // 隱私區域：網頁儀表板是公開的，開頭與結尾落在區域內的點不上傳（本機資料與本機匯出仍是完整的）。
            val visible = com.startinsnow.gpstracker.core.privacy.PrivacyZones.trimEnds(trusted, privacyZones, { it.latitude }, { it.longitude })
            val sampled = downsample(visible, MAX_POINTS)

            val points = JSONArray()
            sampled.forEach { p ->
                points.put(
                    JSONArray()
                        .put(round(p.longitude, 6))
                        .put(round(p.latitude, 6))
                        .put(round(p.altitudeMeters ?: 0.0, 1))
                        .put(round((p.speedMps?.toDouble() ?: 0.0) * 3.6, 1))
                        .put(((p.timestampMs - startMs) / 1000.0).toInt())
                )
            }

            val photos = JSONArray()
            val photoFiles = mutableListOf<PhotoFile>()
            data.photos.forEach { ph ->
                if (ph.latitude != null && ph.longitude != null &&
                    !com.startinsnow.gpstracker.core.privacy.PrivacyZones.isInside(ph.latitude, ph.longitude, privacyZones)
                ) {
                    val entry = JSONArray()
                        .put(round(ph.longitude, 6))
                        .put(round(ph.latitude, 6))
                        .put(((ph.timestampMs - startMs) / 1000.0).toInt())
                    // 使用者開啟「上傳照片縮圖」時，附上縮圖路徑（第 4 欄）；縮圖已去除 EXIF，且不在隱私區域內（上面已篩掉）。
                    if (uploadPhotos && photoFiles.size < com.startinsnow.gpstracker.export.PhotoThumbnails.MAX_PHOTOS_PER_TRACK) {
                        com.startinsnow.gpstracker.export.PhotoThumbnails.makeJpeg(ph.filePath)?.let { jpeg ->
                            val rel = com.startinsnow.gpstracker.export.PhotoThumbnails.relativePath(track.trackId, ph.photoId)
                            photoFiles.add(PhotoFile(rel, jpeg))
                            entry.put(rel)
                        }
                    }
                    photos.put(entry)
                }
            }

            val segments = JSONArray()
            data.segments.forEach { seg ->
                segments.put(
                    JSONArray()
                        .put(seg.mode.name)
                        .put(((seg.startTimeMs - startMs) / 1000.0).toInt())
                        .put((((seg.endTimeMs ?: track.endTimeMs ?: seg.startTimeMs) - startMs) / 1000.0).toInt())
                )
            }

            val json = JSONObject().apply {
                put("trackId", track.trackId)
                put("startTimeMs", track.startTimeMs)
                put("endTimeMs", track.endTimeMs ?: track.startTimeMs)
                put("durationMs", track.durationMs)
                put("distanceMeters", round(track.distanceMeters, 2))
                put("avgSpeedMps", round(track.avgSpeedMps, 3))
                put("maxSpeedMps", round(track.maxSpeedMps, 3))
                put("stepCount", track.stepCount)
                put("stepSource", track.stepSource)
                put("dominantMode", track.dominantMode.name)
                put("gpsPointCount", track.gpsPointCount)
                put("photoCount", track.photoCount)
                put("totalAscentMeters", round(track.totalAscentMeters, 1))
                put("totalDescentMeters", round(track.totalDescentMeters, 1))
                put("maxAltitudeMeters", track.maxAltitudeMeters?.let { round(it, 1) } ?: JSONObject.NULL)
                put("minAltitudeMeters", track.minAltitudeMeters?.let { round(it, 1) } ?: JSONObject.NULL)
                put("points", points)
                put("photos", photos)
                put("segments", segments)
                if (deviceName.isNotBlank()) put("deviceName", deviceName)
            }

            val entry = JSONObject().apply {
                put("trackId", track.trackId)
                put("startTimeMs", track.startTimeMs)
                put("distanceMeters", round(track.distanceMeters, 2))
                put("durationMs", track.durationMs)
                put("avgSpeedMps", round(track.avgSpeedMps, 3))
                put("maxSpeedMps", round(track.maxSpeedMps, 3))
                put("stepCount", track.stepCount)
                put("stepSource", track.stepSource)
                put("dominantMode", track.dominantMode.name)
                put("totalAscentMeters", round(track.totalAscentMeters, 1))
                put("totalDescentMeters", round(track.totalDescentMeters, 1))
                put("photoCount", track.photoCount)
                put("file", "${track.trackId}.json")
                if (deviceName.isNotBlank()) put("deviceName", deviceName)
            }
            return Payload(json.toString(), entry, photoFiles)
        }

        private fun <T> downsample(list: List<T>, max: Int): List<T> {
            if (list.size <= max) return list
            val step = list.size.toDouble() / max
            val out = ArrayList<T>(max)
            var i = 0.0
            while (i < list.size) {
                out.add(list[i.toInt()])
                i += step
            }
            // 固定步長取樣會漏掉最後一點（終點）；補上，避免軌跡少畫最後一段。
            if (out.last() !== list.last()) out.add(list.last())
            return out
        }

        private fun round(value: Double, decimals: Int): Double {
            val factor = Math.pow(10.0, decimals.toDouble())
            return Math.round(value * factor) / factor
        }
    }
}
