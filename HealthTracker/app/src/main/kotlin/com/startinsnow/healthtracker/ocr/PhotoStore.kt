package com.startinsnow.healthtracker.ocr

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

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

    /** 把從雲端下載的照片存進私有目錄（檔名固定為紀錄 id，重複下載會覆蓋同一個檔）。 */
    fun saveBytes(context: Context, recordId: String, bytes: ByteArray): File? = try {
        File(dir(context), "cloud-$recordId.jpg").also { it.writeBytes(bytes) }
    } catch (e: java.io.IOException) {
        null
    }

    fun uriOf(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** 縮到長邊不超過 maxSide 的尺寸（不放大），保持長寬比。 */
    internal fun scaledSize(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        val longest = max(w, h)
        if (longest <= maxSide) return w to h
        val f = maxSide.toDouble() / longest
        return max(1, (w * f).roundToInt()) to max(1, (h * f).roundToInt())
    }

    /**
     * 相機原圖動輒 4～10 MB：拍照或選圖後立刻縮到長邊 maxSide、JPEG 品質 quality，並依 EXIF 轉正後覆寫原檔
     * （方向已烤進像素，所以丟掉 EXIF 也不會顛倒）。OCR 與日後上傳都用這份縮小的檔案。
     * 解碼失敗時保留原檔不動。回傳是否有改寫。
     */
    fun compressInPlace(file: File, maxSide: Int = 1800, quality: Int = 85): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val degrees = when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val (tw, th) = scaledSize(decoded.width, decoded.height, maxSide)
        var bmp: Bitmap = if (tw != decoded.width || th != decoded.height) Bitmap.createScaledBitmap(decoded, tw, th, true) else decoded
        if (degrees != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return if (tmp.length() > 0) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
            true
        } else {
            tmp.delete()
            false
        }
    }

    private val exifFormat = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** 把 EXIF 時間字串（yyyy:MM:dd HH:mm:ss，可帶 +08:00 這類時差）轉成毫秒；格式不對回傳 null。 */
    internal fun parseExifTime(text: String?, offset: String?, zone: ZoneId): Long? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val local = try { LocalDateTime.parse(t, exifFormat) } catch (e: Exception) { return null }
        val z: ZoneId = offset?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { ZoneOffset.of(it) }.getOrNull() } ?: zone
        return local.atZone(z).toInstant().toEpochMilli()
    }

    /** 讀照片的拍攝時間（EXIF）。一定要在 compressInPlace 之前呼叫，縮圖後 EXIF 會被丟掉。沒有就回傳 null。 */
    fun captureTime(file: File, zone: ZoneId = ZoneId.systemDefault()): Long? = try {
        val e = ExifInterface(file.absolutePath)
        parseExifTime(e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), e.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL), zone)
            ?: parseExifTime(e.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED), e.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED), zone)
            ?: parseExifTime(e.getAttribute(ExifInterface.TAG_DATETIME), e.getAttribute(ExifInterface.TAG_OFFSET_TIME), zone)
    } catch (e: Exception) { null }

    fun delete(path: String?) {
        if (path != null) File(path).delete()
    }
}
