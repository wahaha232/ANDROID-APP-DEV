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
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    /** 將 Bitmap 存成 PNG 後分享（供「分享快照」使用）。 */
    fun shareBitmap(context: Context, bitmap: Bitmap, fileName: String, chooserTitle: String) {
        runCatching {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            shareFile(context, file, "image/png", chooserTitle)
        }
    }

    fun shareText(context: Context, text: String, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}
