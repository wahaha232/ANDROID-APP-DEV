package com.startinsnow.healthtracker.importer

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.startinsnow.healthtracker.data.repository.AddOutcome
import com.startinsnow.healthtracker.data.repository.AttachOutcome
import com.startinsnow.healthtracker.data.repository.HealthRecordRepository
import com.startinsnow.healthtracker.ocr.PhotoOcr
import com.startinsnow.healthtracker.ocr.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BatchResult(
    val saved: Int = 0,
    /** 同一時間已有紀錄但沒有照片，這次把照片補上去。 */
    val attached: Int = 0,
    /** 已有相同數值、時間相近的紀錄（重複匯入同一批照片時會出現）。 */
    val duplicate: Int = 0,
    /** 照片裡認不出血糖數值。 */
    val noValue: Int = 0,
    /** 照片沒有拍攝時間，無法歸到正確日期，所以不寫入。 */
    val noTime: Int = 0,
    val failed: Int = 0,
) {
    val total get() = saved + attached + duplicate + noValue + noTime + failed

    fun summary(): String = buildString {
        append("共 $total 張：新增 $saved 筆")
        if (attached > 0) append("、補上照片 $attached 筆")
        if (duplicate > 0) append("、重複略過 $duplicate")
        if (noValue > 0) append("、認不出數值 $noValue")
        if (noTime > 0) append("、無拍攝時間 $noTime")
        if (failed > 0) append("、讀取失敗 $failed")
    }
}

/** 批次匯入血糖機照片：逐張 OCR，數值取最可信的一個，測量時間取照片的拍攝時間，先存在手機。 */
class PhotoBatchImporter(private val context: Context, private val records: HealthRecordRepository) {

    suspend fun run(uris: List<Uri>, onProgress: (done: Int, total: Int) -> Unit): BatchResult {
        var r = BatchResult()
        uris.forEachIndexed { i, uri ->
            onProgress(i, uris.size)
            r = try {
                one(uri, r)
            } catch (e: Exception) {
                r.copy(failed = r.failed + 1)
            }
        }
        onProgress(uris.size, uris.size)
        return r
    }

    private suspend fun one(uri: Uri, r: BatchResult): BatchResult {
        val file = withContext(Dispatchers.IO) { PhotoStore.importFrom(context, uri) } ?: return r.copy(failed = r.failed + 1)
        var keep = false
        try {
            val now = System.currentTimeMillis()
            // 縮圖會丟掉 EXIF，拍攝時間要先讀
            val taken = withContext(Dispatchers.IO) { PhotoStore.captureTime(file) ?: mediaStoreTime(uri) }
            if (taken == null || taken > now) return r.copy(noTime = r.noTime + 1)
            withContext(Dispatchers.IO) { PhotoStore.compressInPlace(file) }
            // 同一時間已有紀錄但沒照片：直接把照片補上去（數值以紀錄為準，不重新辨識）
            when (records.attachPhotoIfNearby(taken, file.absolutePath, now)) {
                AttachOutcome.ATTACHED -> { keep = true; return r.copy(attached = r.attached + 1) }
                AttachOutcome.HAS_PHOTO -> return r.copy(duplicate = r.duplicate + 1)
                AttachOutcome.NONE -> {}
            }
            // 與新增紀錄用同一套辨識（含液晶七段數字讀取）。自動模式只收「讀到七段數字」或「數字旁有 mg/dL」的結果，
            // 避免把標籤、單據上的數字當成血糖。
            val best = PhotoOcr.candidates(file).firstOrNull { it.lineText == "七段數字" || it.score > 1.05 }
            val value = best?.value ?: return r.copy(noValue = r.noValue + 1)
            return when (records.addFromPhoto(value, taken, file.absolutePath, now)) {
                AddOutcome.ADDED -> { keep = true; r.copy(saved = r.saved + 1) }
                AddOutcome.DUPLICATE -> r.copy(duplicate = r.duplicate + 1)
                AddOutcome.INVALID -> r.copy(noValue = r.noValue + 1)
            }
        } finally {
            if (!keep) file.delete()
        }
    }

    /** 沒有 EXIF 時，退而求其次用系統媒體庫記的拍攝／修改時間。 */
    private fun mediaStoreTime(uri: Uri): Long? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED), null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val taken = if (c.isNull(0)) 0L else c.getLong(0)
            val modified = if (c.isNull(1)) 0L else c.getLong(1) * 1000
            (if (taken > 0) taken else modified).takeIf { it > 0 }
        }
    }.getOrNull()
}
