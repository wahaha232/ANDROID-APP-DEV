package com.startinsnow.healthtracker.ocr

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** 照片只存在 App 私有目錄（filesDir/photos），不進相簿、不進系統備份。 */
object PhotoStore {
    private fun dir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }

    fun authority(context: Context) = "${context.packageName}.fileprovider"

    /** 建立供相機寫入的空檔案，回傳檔案與可交給相機的 content Uri。 */
    fun newCameraTarget(context: Context): Pair<File, Uri> {
        val f = File(dir(context), "${UUID.randomUUID()}.jpg")
        return f to FileProvider.getUriForFile(context, authority(context), f)
    }

    /** 把使用者從相簿選的照片複製進私有目錄（之後相簿刪除也不影響紀錄）。 */
    fun importFrom(context: Context, source: Uri): File? {
        val f = File(dir(context), "${UUID.randomUUID()}.jpg")
        val ok = context.contentResolver.openInputStream(source)?.use { input ->
            f.outputStream().use { input.copyTo(it) }
            true
        } ?: false
        return if (ok) f else null
    }

    fun uriOf(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    fun delete(path: String?) {
        if (path != null) File(path).delete()
    }
}
