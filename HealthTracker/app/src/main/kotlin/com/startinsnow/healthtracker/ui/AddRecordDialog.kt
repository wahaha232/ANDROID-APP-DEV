package com.startinsnow.healthtracker.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.repository.MeasureTime
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.repository.HealthRecordRepository
import com.startinsnow.healthtracker.ocr.GlucoseCandidate
import com.startinsnow.healthtracker.ocr.GlucoseOcrParser
import com.startinsnow.healthtracker.ocr.OcrEngine
import com.startinsnow.healthtracker.ocr.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface OcrState {
    data object Idle : OcrState
    data object Running : OcrState
    data class Done(val candidates: List<GlucoseCandidate>) : OcrState
    data class Failed(val message: String) : OcrState
}

private fun formatValue(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddRecordDialog(
    records: HealthRecordRepository,
    existing: HealthRecord? = null, // 有值＝修改這筆紀錄（類型、來源、照片不變）
    initialType: RecordType? = null, // 從總覽「記錄血糖」進來時預選血糖
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    var type by remember { mutableStateOf(existing?.type ?: initialType ?: RecordType.WEIGHT) }
    var v by remember { mutableStateOf(existing?.let { formatValue(it.value) } ?: "") }
    var v2 by remember { mutableStateOf(existing?.value2?.let { formatValue(it) } ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var measuredAt by remember { mutableLongStateOf(existing?.measuredAt ?: System.currentTimeMillis()) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    // 目前掛在這筆紀錄上的照片（存檔後才算數；取消或換類型就刪掉）。
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var ocr by remember { mutableStateOf<OcrState>(OcrState.Idle) }

    fun discardPhoto() {
        PhotoStore.delete(photoPath)
        photoPath = null
        ocr = OcrState.Idle
    }

    fun recognize(file: File) {
        PhotoStore.delete(photoPath)
        photoPath = file.absolutePath
        ocr = OcrState.Running
        scope.launch {
            ocr = try {
                withContext(Dispatchers.IO) { PhotoStore.compressInPlace(file) } // 先縮圖：省空間、上傳快，OCR 也更快
                val lines = OcrEngine.recognize(context, PhotoStore.uriOf(context, file))
                val candidates = GlucoseOcrParser.candidates(lines)
                candidates.firstOrNull()?.let { v = formatValue(it.value) }
                OcrState.Done(candidates)
            } catch (e: Exception) {
                OcrState.Failed("辨識失敗，請對照照片手動輸入")
            }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingCameraPath
        pendingCameraPath = null
        if (path != null) {
            val f = File(path)
            if (ok && f.length() > 0) recognize(f) else f.delete()
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val f = withContext(Dispatchers.IO) { PhotoStore.importFrom(context, uri) }
                if (f != null) recognize(f) else ocr = OcrState.Failed("無法讀取這張照片")
            }
        }
    }

    AlertDialog(
        onDismissRequest = { discardPhoto(); onDismiss() },
        title = { Text(if (existing == null) "新增紀錄" else "修改紀錄") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            enabled = existing == null,
                            onClick = {
                                if (t != type) {
                                    if (t != RecordType.BLOOD_GLUCOSE) discardPhoto()
                                    type = t
                                    error = null
                                }
                            },
                            label = { Text(t.label) },
                        )
                    }
                }

                if (type == RecordType.BLOOD_GLUCOSE && existing == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val (file, uri) = PhotoStore.newCameraTarget(context)
                            pendingCameraPath = file.absolutePath
                            takePicture.launch(uri)
                        }) { Text("拍照辨識") }
                        OutlinedButton(onClick = {
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text("選照片") }
                    }
                    photoPath?.let { PhotoPreview(it) }
                    when (val s = ocr) {
                        OcrState.Idle -> {}
                        OcrState.Running -> Text("辨識中…")
                        is OcrState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                        is OcrState.Done -> {
                            if (s.candidates.isEmpty()) {
                                Text("沒有辨識到血糖數字，請對照照片手動輸入")
                            } else {
                                Text("請對照照片確認數值（可點選其他候選）", style = MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    s.candidates.take(4).forEach { c ->
                                        FilterChip(
                                            selected = v == formatValue(c.value),
                                            onClick = { v = formatValue(c.value) },
                                            label = { Text(formatValue(c.value)) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (existing?.photoPath != null && File(existing.photoPath).isFile) PhotoPreview(existing.photoPath)

                // 測量時間：預設現在；補登或照片時間不同時可改日期與時分
                Text(
                    "測量時間　" + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(measuredAt).atZone(zone)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDate = true }) { Text("改日期") }
                    OutlinedButton(onClick = { showTime = true }) { Text("改時間") }
                }

                OutlinedTextField(
                    value = v, onValueChange = { v = it },
                    label = { Text(if (type == RecordType.BLOOD_PRESSURE) "收縮壓" else "${type.label}（${type.unit}）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (type == RecordType.BLOOD_PRESSURE) {
                    OutlinedTextField(
                        value = v2, onValueChange = { v2 = it }, label = { Text("舒張壓") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("備註（選填）") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val usePhoto = existing == null && type == RecordType.BLOOD_GLUCOSE && photoPath != null
                    val msg = if (existing != null) {
                        records.update(existing.id, v.toDoubleOrNull(), v2.toDoubleOrNull(), measuredAt, note)
                    } else {
                        records.addManual(
                            type, v.toDoubleOrNull(), v2.toDoubleOrNull(), measuredAt, note,
                            source = if (usePhoto) RecordSource.OCR else RecordSource.MANUAL,
                            photoPath = if (usePhoto) photoPath else null,
                        )
                    }
                    if (msg == null) {
                        photoPath = null // 已交給紀錄保管，不能被 discardPhoto 刪掉
                        onSaved()
                    } else {
                        error = msg
                    }
                }
            }) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = { discardPhoto(); onDismiss() }) { Text("取消") } },
    )

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = MeasureTime.utcMidnightOfLocalDate(measuredAt, zone))
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { measuredAt = MeasureTime.withDate(measuredAt, it, zone) }
                    showDate = false
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("取消") } },
        ) { DatePicker(state = state) }
    }

    if (showTime) {
        val parts = Instant.ofEpochMilli(measuredAt).atZone(zone)
        val state = rememberTimePickerState(initialHour = parts.hour, initialMinute = parts.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    measuredAt = MeasureTime.withTime(measuredAt, state.hour, state.minute, zone)
                    showTime = false
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun PhotoPreview(path: String) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(path, 900) }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "血糖機照片",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        )
    }
}

/** 依長邊縮小解碼，避免整張相機原圖進記憶體。 */
internal fun decodeThumbnail(path: String, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return rotateByExif(path, bmp)
}

private fun rotateByExif(path: String, bmp: Bitmap): Bitmap {
    val degrees = when (androidx.exifinterface.media.ExifInterface(path)
        .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return bmp
    val m = android.graphics.Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}
