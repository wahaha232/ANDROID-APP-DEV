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
            // 液晶七段數字一般文字辨識讀不出來：以辨識到的最大字（通常是數字的一部分）為線索，只看螢幕數字那一小塊
            // 位置線索可能來自增強對比後的影像，但數字一律從原影像（未增強）讀：增強後的灰階分布會讓七段判讀失準
            val seg = withContext(Dispatchers.Default) { sevenSegment(base, lines) }
            val found = (listOfNotNull(seg) + GlucoseOcrParser.candidates(lines)).distinctBy { it.value }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /**
     * 液晶七段數字：以辨識到的最大字（通常是最後一位數字）當線索，只框出那一排數字來讀，不看整張照片。
     * 數字高度用線索框高度的幾種比例各試一次；左側範圍也試兩種寬度（數字最多 3 位）。讀到合理血糖值就回傳。
     */
    private fun sevenSegment(bmp: Bitmap, lines: List<OcrLine>): GlucoseCandidate? {
        val anchors = lines.filter { it.bottom > it.top && it.height >= bmp.height * 0.04 }.sortedByDescending { it.height }.take(3)
        for (a in anchors) {
            val h = a.bottom - a.top
            for (leftMul in listOf(2.6, 3.0)) {
                val x0 = (a.left - leftMul * h).toInt().coerceIn(0, bmp.width - 2)
                val y0 = (a.top - 0.08 * h).toInt().coerceIn(0, bmp.height - 2)
                val x1 = (a.right + 0.3 * h).toInt().coerceIn(x0 + 1, bmp.width) // 文字框常比實際數字偏左，右側要多留
                val y1 = (a.bottom + 0.08 * h).toInt().coerceIn(y0 + 1, bmp.height)
                val gray = grayRegion(bmp, x0, y0, x1, y1)
                debugDump(bmp, gray, x1 - x0, y1 - y0, h, "a${a.left}_${a.top}_${a.right}_${a.bottom}_m$leftMul")
                for (hintMul in listOf(0.88, 0.84, 0.92)) { // 文字框比實際數字高約 12%
                    val raw = SevenSegmentReader.read(gray, x1 - x0, y1 - y0, (h * hintMul).toInt())
                    if (com.startinsnow.healthtracker.BuildConfig.DEBUG) android.util.Log.d("PhotoOcr", "seg7 anchor=[${a.left},${a.top},${a.right},${a.bottom}] roi=[$x0,$y0,$x1,$y1] hint=${(h * hintMul).toInt()} -> $raw")
                    val v = SevenSegmentReader.toGlucose(raw) ?: continue
                    return GlucoseCandidate(v, 2.0, "七段數字")
                }
            }
        }
        // 沒有可用的線索（例如使用者已把數字放大框好）：整個影像當成數字區
        val gray = grayRegion(bmp, 0, 0, bmp.width, bmp.height)
        val v = SevenSegmentReader.toGlucose(SevenSegmentReader.read(gray, bmp.width, bmp.height)) ?: return null
        return GlucoseCandidate(v, 2.0, "七段數字")
    }

    /** 除錯版才用：把送去讀的區塊存成檔案，方便拉回電腦分析。 */
    private fun debugDump(bmp: Bitmap, gray: IntArray, w: Int, h: Int, hint: Int, name: String) {
        if (!com.startinsnow.healthtracker.BuildConfig.DEBUG) return
        try {
            val dir = java.io.File(com.startinsnow.healthtracker.HealthTrackerApp.appContext.filesDir, "dbg").apply { mkdirs() }
            val bb = java.nio.ByteBuffer.allocate(12 + gray.size).putInt(w).putInt(h).putInt(hint)
            gray.forEach { bb.put(it.toByte()) }
            java.io.File(dir, "$name.bin").writeBytes(bb.array())
        } catch (_: Exception) {
        }
    }

    private fun grayRegion(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        val w = x1 - x0
        val h = y1 - y0
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)
        return IntArray(w * h) { i -> val c = px[i]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }
    }
}
