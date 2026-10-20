package com.startinsnow.healthtracker.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.startinsnow.healthtracker.ocr.PhotoCrop
import com.startinsnow.healthtracker.ocr.PhotoOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 縮放照片、框出血糖機螢幕再辨識：雙指縮放、單指拖曳，讓數字佔滿畫面後按「辨識此範圍」。
 * 畫面上看到的範圍就是送去辨識的範圍。
 */
@Composable
fun PhotoZoomDialog(path: String, onCancel: () -> Unit, onConfirm: (PhotoCrop) -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(path, 1600) }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    var boxW by remember { mutableFloatStateOf(0f) }
    var boxH by remember { mutableFloatStateOf(0f) }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Text(
                "雙指縮放、拖曳照片，讓血糖機螢幕的數字佔滿畫面，再按「辨識此範圍」。",
                color = Color.White, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .onSizeChanged { boxW = it.width.toFloat(); boxH = it.height.toFloat() }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            tx += pan.x
                            ty += pan.y
                        }
                    },
            ) {
                bitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "血糖機照片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = tx, translationY = ty),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                TextButton(onClick = { scale = 1f; tx = 0f; ty = 0f }) { Text("還原") }
                TextButton(onClick = onCancel) { Text("取消") }
                Button(enabled = bitmap != null, onClick = {
                    val b = bitmap ?: return@Button
                    onConfirm(PhotoOcr.visibleCrop(boxW, boxH, b.width, b.height, scale, tx, ty))
                }) { Text("辨識此範圍") }
            }
        }
    }
}
