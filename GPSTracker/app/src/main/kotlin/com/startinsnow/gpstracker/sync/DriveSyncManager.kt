package com.startinsnow.gpstracker.sync

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 對應規格「46/78. Google Drive Upload / DriveSyncManager」。
 * 直接呼叫 Google Drive REST v3（而不是笨重的 google-api-client），採 Local First → Batch Upload，
 * GPS Recording（[com.startinsnow.gpstracker.service.TrackingForegroundService]）完全不依賴這個類別，
 * 即使 Drive 上傳失敗或還沒設定 OAuth Client，也不影響 GPS 記錄本身。
 */
class DriveSyncManager(
    private val client: OkHttpClient = OkHttpClient()
) {
    /** 依 "GPS TRACKER/2026/09/2026-09-16_142000" 逐層確保資料夾存在，回傳最深層的 folderId。 */
    suspend fun ensureFolderPath(accessToken: String, path: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var parentId = "root"
            path.split("/").filter { it.isNotBlank() }.forEach { segment ->
                parentId = findOrCreateFolder(accessToken, segment, parentId)
            }
            parentId
        }
    }

    private fun findOrCreateFolder(accessToken: String, name: String, parentId: String): String {
        val existing = findFolder(accessToken, name, parentId)
        if (existing != null) return existing
        return createFolder(accessToken, name, parentId)
    }

    private fun findFolder(accessToken: String, name: String, parentId: String): String? {
        val query = "mimeType='application/vnd.google-apps.folder' and name='${escapeQuery(name)}' " +
            "and '$parentId' in parents and trashed=false"
        val url = "https://www.googleapis.com/drive/v3/files?q=${urlEncode(query)}&fields=files(id,name)"
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DriveApiException(response.code, response.body?.string().orEmpty())
            val json = JSONObject(response.body?.string().orEmpty())
            val files = json.optJSONArray("files") ?: JSONArray()
            return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
        }
    }

    private fun createFolder(accessToken: String, name: String, parentId: String): String {
        val metadata = JSONObject().apply {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
            put("parents", JSONArray().put(parentId))
        }
        val body = metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files")
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DriveApiException(response.code, response.body?.string().orEmpty())
            return JSONObject(response.body?.string().orEmpty()).getString("id")
        }
    }

    suspend fun uploadFile(
        accessToken: String,
        parentFolderId: String,
        file: File,
        mimeType: String,
        maxRetries: Int = 3
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        repeat(maxRetries) { attempt ->
            try {
                val metadata = JSONObject().apply {
                    put("name", file.name)
                    put("parents", JSONArray().put(parentFolderId))
                }
                // Google Drive multipart upload 需要 multipart/related（metadata + media 兩個部分），
                // 不是一般表單的 multipart/form-data，因此不能用具名欄位，而是照順序給 Content-Type。
                val multipartBody = MultipartBody.Builder(java.util.UUID.randomUUID().toString())
                    .setType("multipart/related".toMediaTypeOrNull()!!)
                    .addPart(
                        okhttp3.Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                        metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull())
                    )
                    .addPart(
                        okhttp3.Headers.headersOf("Content-Type", mimeType),
                        file.asRequestBody(mimeType.toMediaTypeOrNull())
                    )
                    .build()

                val request = Request.Builder()
                    .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                    .header("Authorization", "Bearer $accessToken")
                    .post(multipartBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw DriveApiException(response.code, response.body?.string().orEmpty())
                    return@withContext Result.success(JSONObject(response.body?.string().orEmpty()).getString("id"))
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(RETRY_BACKOFF_MS * (attempt + 1))
                }
            }
        }
        Result.failure(lastError ?: IllegalStateException("Drive upload failed after $maxRetries attempts"))
    }

    /** 上傳整個 Track 匯出資料夾（index.html、track.gpx、track.json、track.csv、photos 目錄）到指定 Drive 路徑。 */
    suspend fun uploadTrackPackage(accessToken: String, exportDir: File, drivePath: String): Result<Unit> {
        val folderResult = ensureFolderPath(accessToken, drivePath)
        val folderId = folderResult.getOrElse { return Result.failure(it) }

        exportDir.listFiles()?.filter { it.isFile }?.forEach { file ->
            val mime = mimeTypeFor(file.name)
            val result = uploadFile(accessToken, folderId, file, mime)
            if (result.isFailure) return Result.failure(result.exceptionOrNull() ?: IllegalStateException("upload failed"))
        }

        val photosDir = File(exportDir, "photos")
        if (photosDir.exists()) {
            val photosFolderResult = ensureFolderPath(accessToken, "$drivePath/photos")
            val photosFolderId = photosFolderResult.getOrElse { return Result.failure(it) }
            photosDir.listFiles()?.filter { it.isFile }?.forEach { photo ->
                val result = uploadFile(accessToken, photosFolderId, photo, "image/jpeg")
                if (result.isFailure) return Result.failure(result.exceptionOrNull() ?: IllegalStateException("photo upload failed"))
            }
        }
        return Result.success(Unit)
    }

    private fun mimeTypeFor(fileName: String): String = when {
        fileName.endsWith(".html") -> "text/html"
        fileName.endsWith(".gpx") -> "application/gpx+xml"
        fileName.endsWith(".json") -> "application/json"
        fileName.endsWith(".csv") -> "text/csv"
        fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
        else -> "application/octet-stream"
    }

    private fun escapeQuery(value: String): String = value.replace("'", "\\'")
    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    class DriveApiException(val code: Int, val body: String) : Exception("Drive API error $code: $body")

    companion object {
        const val RETRY_BACKOFF_MS = 1500L
    }
}
