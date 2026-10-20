package com.startinsnow.healthtracker.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.startinsnow.healthtracker.cloud.CloudErrorKind
import com.startinsnow.healthtracker.cloud.CloudException
import com.startinsnow.healthtracker.cloud.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 雲端放的最新版資訊（app-updates bucket 裡的 latest.json）。 */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    /** bucket 內的 APK 檔名。 */
    val apk: String,
    /** APK 的 SHA-256（小寫十六進位），下載後必須吻合才會安裝。 */
    val sha256: String,
    val size: Long = 0,
    val notes: String = "",
    @SerialName("package") val packageName: String = "",
)

object UpdateLogic {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): UpdateInfo? = try {
        json.decodeFromString(UpdateInfo.serializer(), text).takeIf { it.versionCode > 0 && it.apk.isNotBlank() && it.sha256.length == 64 }
    } catch (e: Exception) {
        null
    }

    fun isNewer(remote: UpdateInfo, currentVersionCode: Int) = remote.versionCode > currentVersionCode

    /** 檔名只允許單層安全字元，避免 latest.json 被竄改成指向其他路徑。 */
    fun isSafeApkName(name: String) = Regex("""[A-Za-z0-9._\-]{1,80}\.apk""").matches(name)

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * App 內雲端更新：從你自己 Supabase 的私人 bucket「app-updates」讀最新版資訊與 APK（要登入才讀得到）。
 * 下載後比對 SHA-256，再交給系統安裝程式；Android 只允許用同一把簽章金鑰的 APK 覆蓋更新，
 * 資料不會被清掉。最後一步的安裝確認一定由你在手機上按。
 */
class AppUpdater(
    private val engine: SyncEngine,
    private val baseUrl: String,
    private val anonKey: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
) {
    private fun objectRequest(token: String, name: String) = Request.Builder()
        .url("${baseUrl.trim().trimEnd('/')}/storage/v1/object/authenticated/$BUCKET/$name")
        .header("apikey", anonKey).header("Authorization", "Bearer $token").build()

    /** 讀雲端最新版資訊；雲端還沒放版本檔回傳 null。 */
    suspend fun fetchLatest(): UpdateInfo? = engine.withSession { s ->
        withContext(Dispatchers.IO) {
            try {
                client.newCall(objectRequest(s.accessToken, "latest.json")).execute().use { r ->
                    when {
                        r.code == 404 || r.code == 400 -> null
                        r.code == 401 -> throw CloudException(CloudErrorKind.UNAUTHORIZED, "登入已過期", 401)
                        !r.isSuccessful -> throw CloudException(CloudErrorKind.SERVER, "讀取版本資訊失敗（HTTP ${r.code}）", r.code)
                        else -> UpdateLogic.parse(r.body?.string().orEmpty())
                            ?: throw CloudException(CloudErrorKind.SERVER, "雲端的版本資訊格式不正確")
                    }
                }
            } catch (e: IOException) {
                throw CloudException(CloudErrorKind.NETWORK, "無法連線到雲端，請檢查網路後再試")
            }
        }
    }

    /** 下載 APK 到 dest，邊下載邊回報進度；完成後驗證 SHA-256，不吻合就刪檔並丟出錯誤。 */
    suspend fun download(info: UpdateInfo, dest: File, onProgress: (done: Long, total: Long) -> Unit) {
        if (!UpdateLogic.isSafeApkName(info.apk)) throw CloudException(CloudErrorKind.SERVER, "雲端的版本資訊不安全，已中止")
        engine.withSession { s ->
            withContext(Dispatchers.IO) {
                try {
                    dest.parentFile?.mkdirs()
                    client.newCall(objectRequest(s.accessToken, info.apk)).execute().use { r ->
                        if (r.code == 401) throw CloudException(CloudErrorKind.UNAUTHORIZED, "登入已過期", 401)
                        if (!r.isSuccessful) throw CloudException(CloudErrorKind.SERVER, "下載失敗（HTTP ${r.code}）", r.code)
                        val body = r.body ?: throw CloudException(CloudErrorKind.SERVER, "下載內容是空的")
                        val total = body.contentLength().takeIf { it > 0 } ?: info.size
                        var done = 0L
                        body.byteStream().use { input ->
                            dest.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    onProgress(done, total)
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    dest.delete()
                    throw CloudException(CloudErrorKind.NETWORK, "下載中斷，請檢查網路後再試")
                }
                if (UpdateLogic.sha256Hex(dest) != info.sha256.lowercase()) {
                    dest.delete()
                    throw CloudException(CloudErrorKind.SERVER, "檔案檢查碼不符，已丟棄下載的檔案")
                }
            }
        }
    }

    companion object {
        const val BUCKET = "app-updates"

        fun apkFile(context: Context) = File(File(context.cacheDir, "updates"), "update.apk")

        fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

        /** 要使用者先允許「安裝未知應用程式」：開系統設定頁。 */
        fun openInstallPermissionSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** 交給系統安裝程式；系統會跳出安裝確認，由使用者按下。 */
        fun install(context: Context, apk: File) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
