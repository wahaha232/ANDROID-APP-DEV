package com.startinsnow.gpstracker.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * 透過 Android 系統分享面板（ACTION_SEND / Chooser）把檔案或文字分享到任何已安裝的社群 App
 * （Instagram、TikTok、LINE、Facebook、X…）。不需整合各家 SDK，也沒有任何費用或 API key。
 */
object ShareUtils {

    fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String) {
        context.startActivity(Intent.createChooser(buildSendIntent(context, file, mimeType), chooserTitle))
    }

    /**
     * 「一鍵分享」快速路徑：優先直接開啟指定 App 的分享編輯器（例如 Instagram / TikTok），
     * 若未安裝則自動退回系統分享面板。
     */
    fun shareToApps(
        context: Context,
        file: File,
        mimeType: String,
        targetPackages: List<String>,
        chooserTitle: String
    ) {
        for (pkg in targetPackages) {
            if (!isPackageInstalled(context, pkg)) continue
            val intent = buildSendIntent(context, file, mimeType).apply { setPackage(pkg) }
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        // 指定的 App 都沒安裝或無法處理：退回一般分享面板。
        shareFile(context, file, mimeType, chooserTitle)
    }

    /** 將 Bitmap 存成 PNG 後分享（供「分享快照」使用）。 */
    fun shareBitmap(context: Context, bitmap: Bitmap, fileName: String, chooserTitle: String) {
        runCatching {
            val file = saveBitmap(context, bitmap, fileName)
            shareFile(context, file, "image/png", chooserTitle)
        }
    }

    /** 將 Bitmap 存成 PNG 至快取後，直接分享到指定 App（一鍵快照分享）。 */
    fun shareBitmapToApps(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        targetPackages: List<String>,
        chooserTitle: String
    ) {
        runCatching {
            val file = saveBitmap(context, bitmap, fileName)
            shareToApps(context, file, "image/png", targetPackages, chooserTitle)
        }
    }

    fun shareText(context: Context, text: String, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(packageName) != null

    private fun buildSendIntent(context: Context, file: File, mimeType: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file
    }
}
