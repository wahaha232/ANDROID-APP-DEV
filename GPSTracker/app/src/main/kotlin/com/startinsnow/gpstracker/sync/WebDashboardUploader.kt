package com.startinsnow.gpstracker.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.startinsnow.gpstracker.export.ExportTrackData
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
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

    data class Payload(val trackJson: String, val manifestEntry: JSONObject)

    suspend fun uploadTrack(
        token: String,
        repo: String,
        branch: String,
        dataPath: String,
        trackId: String,
        payload: Payload
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = dataPath.trim('/')
            putContent(token, repo, branch, "$dir/$trackId.json", payload.trackJson, "GPS Tracker: add track $trackId")

            val manifestPath = "$dir/manifest.json"
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
        }
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

    private fun putContent(token: String, repo: String, branch: String, path: String, content: String, message: String, sha: String? = null) {
        // 未提供 sha 時先查既有檔案：GitHub Contents API 對「路徑已存在但未帶 sha」的更新會回 422，
        // 造成同一筆紀錄重複上傳或失敗重試時無法寫入。補上 sha 讓上傳具冪等性。
        val effectiveSha = sha ?: getContent(token, repo, branch, path)?.sha
        val body = JSONObject().apply {
            put("message", message)
            put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
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

    companion object {
        private const val MAX_POINTS = 20000

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
        fun buildPayload(data: ExportTrackData): Payload {
            val track = data.track
            val startMs = track.startTimeMs
            val trusted = data.points
                .filter { it.reliability == "TRUSTED" }
                .sortedBy { it.timestampMs }
            val sampled = downsample(trusted, MAX_POINTS)

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
            data.photos.forEach { ph ->
                if (ph.latitude != null && ph.longitude != null) {
                    photos.put(
                        JSONArray()
                            .put(round(ph.longitude, 6))
                            .put(round(ph.latitude, 6))
                            .put(((ph.timestampMs - startMs) / 1000.0).toInt())
                    )
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
            }

            val entry = JSONObject().apply {
                put("trackId", track.trackId)
                put("startTimeMs", track.startTimeMs)
                put("distanceMeters", round(track.distanceMeters, 2))
                put("durationMs", track.durationMs)
                put("avgSpeedMps", round(track.avgSpeedMps, 3))
                put("maxSpeedMps", round(track.maxSpeedMps, 3))
                put("stepCount", track.stepCount)
                put("dominantMode", track.dominantMode.name)
                put("totalAscentMeters", round(track.totalAscentMeters, 1))
                put("totalDescentMeters", round(track.totalDescentMeters, 1))
                put("photoCount", track.photoCount)
                put("file", "${track.trackId}.json")
            }
            return Payload(json.toString(), entry)
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
            return out
        }

        private fun round(value: Double, decimals: Int): Double {
            val factor = Math.pow(10.0, decimals.toDouble())
            return Math.round(value * factor) / factor
        }
    }
}
