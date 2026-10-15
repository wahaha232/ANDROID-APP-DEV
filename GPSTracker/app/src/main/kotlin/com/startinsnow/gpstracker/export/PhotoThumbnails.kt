package com.startinsnow.gpstracker.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 上傳到網頁儀表板的照片縮圖。網頁是公開的，所以：
 * - 縮小到長邊最多 [MAX_EDGE] 像素、JPEG 品質 [QUALITY]，檔案約 100～200 KB；
 * - 重新編碼會丟掉原圖的 EXIF（拍攝位置、裝置型號等），只保留「依方向轉正」的影像；
 * - 只有使用者在設定裡開啟「上傳照片縮圖」時才會用到（預設關閉）。
 */
object PhotoThumbnails {
    const val MAX_EDGE = 960
    const val QUALITY = 70
    const val MAX_PHOTOS_PER_TRACK = 40

    /** 縮到長邊 maxEdge 以內（不放大），保持比例；回傳 (寬, 高)。純函式。 */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longest = max(width, height)
        if (longest <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** BitmapFactory 的 inSampleSize（2 的次方）：解碼時就先縮小，避免把大張原圖整個載入記憶體。純函式。 */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        var sample = 1
        var longest = max(width, height)
        while (longest / 2 >= maxEdge) { sample *= 2; longest /= 2 }
        return sample
    }

    /** 縮圖在 repo 裡的相對路徑（相對於資料夾）。只含安全字元，網站端也會再次驗證。純函式。 */
    fun relativePath(trackId: String, photoId: String): String =
        "photos/" + safe(trackId) + "/" + safe(photoId) + ".jpg"

    private fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "x" }

    /** EXIF 方向 → 需要順時針旋轉的角度。純函式。 */
    fun rotationDegrees(exifOrientation: Int): Int = when (exifOrientation) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }

    /** 產生縮圖 JPEG（預設給網頁上傳；3D 飛越的小圓形照片用較小的 [maxEdge]）；檔案不存在、無法解碼或任何錯誤都回傳 null（呼叫端就不上傳這張）。 */
    fun makeJpeg(filePath: String, maxEdge: Int = MAX_EDGE, quality: Int = QUALITY): ByteArray? = runCatching {
        val file = File(filePath)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) }
        var bmp: Bitmap = BitmapFactory.decodeFile(filePath, opts) ?: return null
        val (tw, th) = targetSize(bmp.width, bmp.height, maxEdge)
        if (tw != bmp.width || th != bmp.height) bmp = Bitmap.createScaledBitmap(bmp, tw, th, true)
        val rotation = rotationDegrees(ExifInterface(filePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        if (rotation != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }.getOrNull()
}
