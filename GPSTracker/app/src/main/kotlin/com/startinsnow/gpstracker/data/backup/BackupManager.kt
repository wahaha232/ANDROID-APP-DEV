package com.startinsnow.gpstracker.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.data.db.AppDatabase
import com.startinsnow.gpstracker.photo.TrackPhotoManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 本機資料的匯出／匯入還原（Android 層：資料庫 checkpoint、檔案路徑、分享、重啟）。檔案格式見 [BackupArchive]。 */
object BackupManager {

    private const val DB_NAME = "gps_tracker.db"

    /** 匯出全部軌跡與照片成一個 ZIP（放在 cache/share，之後由使用者用分享面板決定存到哪）。 */
    suspend fun export(app: GpsTrackerApplication): File = withContext(Dispatchers.IO) {
        // WAL 模式下最新資料可能還在 -wal 檔裡：先併回主檔，備份才完整。
        app.database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }

        val dir = File(app.cacheDir, "share").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("gpstracker-backup-") }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir, "gpstracker-backup-$stamp.zip")
        BackupArchive.create(
            dbFile = app.getDatabasePath(DB_NAME),
            photosDir = app.getExternalFilesDir("photos"),
            out = out,
            readme = "GPS TRACKER 備份（$stamp）\n" +
                "gps_tracker.db：所有軌跡、定位點與照片紀錄（SQLite）。\n" +
                "photos/：拍攝的照片。\n" +
                "不含設定與 GitHub Token。還原請用 App「設定 → 資料備份 → 匯入還原」。\n"
        )
        out
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 讀備份檔的摘要（不動任何現有資料），給「確認還原」對話框用。 */
    suspend fun inspect(app: GpsTrackerApplication, uri: Uri): Result<BackupArchive.Info> = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = copyToCache(app, uri)
            try {
                val info = BackupArchive.inspect(tmp).getOrThrow()
                val current = app.database.openHelper.readableDatabase.version
                check(info.dbUserVersion <= current) { "這份備份來自較新版本的 App，請先更新 App 再還原" }
                info
            } finally {
                tmp.delete()
            }
        }
    }

    /**
     * 覆蓋還原：關閉現有資料庫、換成備份的資料庫與照片，修正照片路徑，最後呼叫端要重啟 App。
     * 會覆蓋這支手機上目前所有的軌跡與照片，無法復原。
     */
    suspend fun restore(app: GpsTrackerApplication, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = copyToCache(app, uri)
            try {
                val info = BackupArchive.inspect(tmp).getOrThrow()
                val current = app.database.openHelper.readableDatabase.version
                check(info.dbUserVersion <= current) { "這份備份來自較新版本的 App，請先更新 App 再還原" }

                app.database.close()
                BackupArchive.restore(
                    zip = tmp,
                    dbFile = app.getDatabasePath(DB_NAME),
                    photosDir = app.getExternalFilesDir("photos"),
                    stagingDir = File(app.cacheDir, "restore-staging")
                )
                fixPhotoPaths(app)
            } finally {
                tmp.delete()
            }
        }
    }

    /** 備份裡的照片路徑是原本那支手機（或原本的套件）的絕對路徑，還原後要改成這裡實際的位置。 */
    private suspend fun fixPhotoPaths(app: GpsTrackerApplication) {
        val db = AppDatabase.build(app) // 原本的實例已關閉；用一個臨時實例修正完就關掉
        try {
            db.photoDao().getAll().forEach { photo ->
                val local = File(TrackPhotoManager.photoDir(app, photo.trackId), File(photo.filePath).name)
                if (local.exists() && local.absolutePath != photo.filePath) {
                    db.photoDao().updatePath(photo.photoId, local.absolutePath)
                }
            }
        } finally {
            db.close()
        }
    }

    /** 還原後重啟 App，讓所有元件重新開啟新的資料庫。 */
    fun restartApp(app: GpsTrackerApplication) {
        val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
        app.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    private fun copyToCache(app: GpsTrackerApplication, uri: Uri): File {
        val tmp = File(app.cacheDir, "restore-incoming.zip")
        val input = app.contentResolver.openInputStream(uri) ?: error("無法讀取選取的檔案")
        input.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
        return tmp
    }
}
