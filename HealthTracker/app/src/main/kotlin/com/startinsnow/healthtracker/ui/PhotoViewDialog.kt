package com.startinsnow.healthtracker.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 放大檢視紀錄上保存的原始照片（血糖機畫面），供回頭核對數值。 */
@Composable
fun PhotoViewDialog(path: String, onClose: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(path, 1600) }
    }
    AlertDialog(
        onDismissRequest = onClose,
        text = {
            val bmp = bitmap
            if (bmp == null) {
                Text("載入照片…")
            } else {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "血糖機照片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("關閉") } },
    )
}
