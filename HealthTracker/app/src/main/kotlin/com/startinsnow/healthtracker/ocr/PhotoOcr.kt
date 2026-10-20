package com.startinsnow.healthtracker.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** 照片上的一個範圍，以整張照片的比例表示（0..1）。 */
data class PhotoCrop(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * 血糖機照片的 OCR 流程：先照原樣辨識；讀不到再把畫面轉灰階、拉高對比後重試。
 * 可只辨識使用者放大框出的範圍（血糖機螢幕在照片裡很小時，框起來辨識準很多）。
 */
object PhotoOcr {
    private const val TARGET_SIDE = 1400
    private const val MAX_UPSCALE = 4f
    private const val CONTRAST = 1.8f

    /** 範圍太小時放大到至少 TARGET_SIDE 像素（最多 4 倍），已夠大就不放大。 */
    internal fun upscaleFactor(w: Int, h: Int): Float = (TARGET_SIDE.toFloat() / max(1, max(w, h))).coerceIn(1f, MAX_UPSCALE)

    /**
     * 畫面上可見範圍對應到照片的比例。
     * 影像以 Fit 置中顯示在 boxW×boxH 的框內，再以框中心為軸縮放 scale 倍、平移 (tx, ty) 像素；
     * 螢幕點 p 對應到未縮放的點 q = (p − 中心 − t) / scale + 中心。
     */
    fun visibleCrop(boxW: Float, boxH: Float, imgW: Int, imgH: Int, scale: Float, tx: Float, ty: Float): PhotoCrop {
        val fit = min(boxW / imgW, boxH / imgH)
        val dw = imgW * fit
        val dh = imgH * fit
        val left0 = (boxW - dw) / 2
        val top0 = (boxH - dh) / 2
        fun qx(p: Float) = (p - boxW / 2 - tx) / scale + boxW / 2
        fun qy(p: Float) = (p - boxH / 2 - ty) / scale + boxH / 2
        fun fx(p: Float) = ((qx(p) - left0) / dw).coerceIn(0f, 1f)
        fun fy(p: Float) = ((qy(p) - top0) / dh).coerceIn(0f, 1f)
        return PhotoCrop(fx(0f), fy(0f), fx(boxW), fy(boxH))
    }

    private fun decodeCrop(file: File, crop: PhotoCrop?): Bitmap? {
        val full = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        if (crop == null) return upscale(full)
        val x = (crop.left * full.width).toInt().coerceIn(0, full.width - 1)
        val y = (crop.top * full.height).toInt().coerceIn(0, full.height - 1)
        val w = ((crop.right - crop.left) * full.width).toInt().coerceIn(1, full.width - x)
        val h = ((crop.bottom - crop.top) * full.height).toInt().coerceIn(1, full.height - y)
        return upscale(Bitmap.createBitmap(full, x, y, max(w, 16).coerceAtMost(full.width - x), max(h, 16).coerceAtMost(full.height - y)))
    }

    private fun upscale(src: Bitmap): Bitmap {
        val f = upscaleFactor(src.width, src.height)
        return if (f <= 1f) src else Bitmap.createScaledBitmap(src, (src.width * f).toInt(), (src.height * f).toInt(), true)
    }

    /** 灰階＋拉高對比，讓液晶數字和底色分得更開。 */
    private fun enhance(src: Bitmap): Bitmap {
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val t = (-0.5f * CONTRAST + 0.5f) * 255f
        val contrast = ColorMatrix(floatArrayOf(CONTRAST, 0f, 0f, 0f, t, 0f, CONTRAST, 0f, 0f, t, 0f, 0f, CONTRAST, 0f, t, 0f, 0f, 0f, 1f, 0f))
        gray.postConcat(contrast)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(gray) })
        return out
    }

    /** 依序嘗試「原樣」「增強對比」，第一個讀到血糖數字的結果就回傳；都沒有回傳空清單。 */
    suspend fun candidates(file: File, crop: PhotoCrop? = null): List<GlucoseCandidate> {
        val base = withContext(Dispatchers.IO) { decodeCrop(file, crop) } ?: return emptyList()
        val variants = listOf(base, withContext(Dispatchers.Default) { enhance(base) })
        for (bmp in variants) {
            val lines = OcrEngine.recognize(bmp)
            if (com.startinsnow.healthtracker.BuildConfig.DEBUG) android.util.Log.d("PhotoOcr", "${bmp.width}x${bmp.height} lines=" + lines.joinToString(" | ") { "'${it.text}'(${it.height})" })
            val found = GlucoseOcrParser.candidates(lines)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }
}
