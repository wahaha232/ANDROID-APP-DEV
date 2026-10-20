package com.startinsnow.gpstracker.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** GitHub Release「gpstracker-latest」上的最新版資訊。 */
data class RemoteUpdate(
    /** 例如 1.0.330。 */
    val versionName: String,
    val apkUrl: String,
    val size: Long,
)

/** 版本比較與網址檢查。純 Kotlin，可用 JVM 單元測試。 */
object UpdateLogic {
    private val versionPattern = Regex("""(\d+)\.(\d+)\.(\d+)""")

    /** 從「GPS Tracker 1.0.330（最新 debug 版）」或「1.0.330」取出 1.0.330；找不到回傳 null。 */
    fun versionOf(text: String): String? = versionPattern.find(text)?.value

    fun isNewer(currentVersionName: String, remoteVersionName: String): Boolean {
        val current = versionOf(currentVersionName)?.split('.')?.map { it.toInt() } ?: return false
        val remote = versionOf(remoteVersionName)?.split('.')?.map { it.toInt() } ?: return false
        for (i in 0 until 3) if (remote[i] != current[i]) return remote[i] > current[i]
        return false
    }

    fun isSafeDownloadUrl(url: String, repo: String): Boolean =
        url.startsWith("https://github.com/$repo/releases/download/")
}

class UpdateException(message: String) : IOException(message)

/**
 * App 內更新：查 GitHub Release「gpstracker-latest」→ 下載 APK → 檢查套件名稱、版本與簽章 → 交給系統安裝器。
 * 簽章必須和目前安裝的相同才會安裝（Android 也只允許同簽章覆蓋更新，資料會保留）。
 * 安裝前系統一定會跳出確認畫面，由使用者按下，這一步無法省略。
 */
class AppUpdater(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    /** 回傳最新版資訊（可能和目前相同，由呼叫端用 [UpdateLogic.isNewer] 判斷）。 */
    suspend fun fetchLatest(): RemoteUpdate = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "gpstracker-app")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UpdateException("查詢失敗（HTTP ${response.code}）")
                val body = response.body?.string().orEmpty()
                if (body.length > MAX_JSON_CHARS) throw UpdateException("版本資訊過大")
                val json = Json.parseToJsonElement(body).jsonObject
                val title = json["name"]?.jsonPrimitive?.content.orEmpty()
                val version = UpdateLogic.versionOf(title) ?: throw UpdateException("看不出最新版本號")
                val assets: JsonArray = json["assets"]?.jsonArray ?: throw UpdateException("Release 沒有附檔")
                val apk = assets.map { it as JsonObject }.firstOrNull {
                    it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true &&
                        UpdateLogic.isSafeDownloadUrl(it["browser_download_url"]?.jsonPrimitive?.content.orEmpty(), REPO)
                } ?: throw UpdateException("Release 裡找不到安全的 APK")
                RemoteUpdate(
                    versionName = version,
                    apkUrl = apk["browser_download_url"]!!.jsonPrimitive.content,
                    size = apk["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                )
            }
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException("無法連線，請檢查網路後再試")
        } catch (e: Exception) {
            throw UpdateException("版本資訊格式不正確")
        }
    }

    /** 下載到 [dest]；回報 0..1 的進度，[isActive] 回傳 false 就中止。 */
    suspend fun download(update: RemoteUpdate, dest: File, onProgress: (Float?) -> Unit) = withContext(Dispatchers.IO) {
        if (!UpdateLogic.isSafeDownloadUrl(update.apkUrl, REPO)) throw UpdateException("下載網址不安全，已中止")
        try {
            dest.parentFile?.mkdirs()
            val request = Request.Builder().url(update.apkUrl).header("User-Agent", "gpstracker-app").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UpdateException("下載失敗（HTTP ${response.code}）")
                val body = response.body ?: throw UpdateException("下載內容是空的")
                val total = body.contentLength().takeIf { it > 0 } ?: update.size
                if (total > MAX_APK_BYTES) throw UpdateException("檔案過大，已中止")
                var done = 0L
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            if (done > MAX_APK_BYTES) throw UpdateException("檔案過大，已中止")
                            onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null)
                        }
                    }
                }
            }
        } catch (e: UpdateException) {
            dest.delete()
            throw e
        } catch (e: IOException) {
            dest.delete()
            throw UpdateException("下載中斷，請檢查網路後再試")
        }
    }

    /** 檢查下載的 APK；有問題回傳要給使用者看的原因，沒問題回傳 null。 */
    fun verifyArchive(context: Context, apk: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return "下載的檔案不是有效的 APK"
        if (archive.packageName != context.packageName) return "下載的 APK 不是這個 App"
        @Suppress("DEPRECATION")
        val installedCode = pm.getPackageInfo(context.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
        }
        @Suppress("DEPRECATION")
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archiveCode <= installedCode) return "下載的版本不比目前新"
        if (Build.VERSION.SDK_INT >= 28) {
            val newSigners = archive.signingInfo?.apkContentsSigners?.map(::fingerprint)?.toSet()
            val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.map(::fingerprint)?.toSet()
            if (newSigners != null && installed != null && newSigners != installed) {
                return "新版的簽章和目前安裝的不同，系統不允許覆蓋安裝（資料會被保護）。請先備份再手動重裝。"
            }
        }
        return null
    }

    companion object {
        const val REPO = "wahaha232/ANDROID-APP-DEV"
        private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/tags/gpstracker-latest"
        private const val MAX_JSON_CHARS = 512 * 1024
        private const val MAX_APK_BYTES = 200L * 1024 * 1024

        fun apkFile(context: Context) = File(File(context.cacheDir, "updates"), "update.apk")

        private fun fingerprint(signature: android.content.pm.Signature): String =
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }

        fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

        /** 開啟系統裡「允許這個 App 安裝未知應用程式」的頁面，由使用者自己決定。 */
        fun openInstallPermissionSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun install(context: Context, apk: File) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
