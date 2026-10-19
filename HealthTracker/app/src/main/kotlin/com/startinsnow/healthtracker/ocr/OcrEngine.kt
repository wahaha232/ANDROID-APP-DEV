package com.startinsnow.healthtracker.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 裝置端 OCR（ML Kit 內建模型，照片不離開手機）。 */
object OcrEngine {
    suspend fun recognize(context: Context, uri: Uri): List<OcrLine> {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val lines = result.textBlocks.flatMap { it.lines }.map { l ->
                            OcrLine(l.text, l.boundingBox?.height() ?: 0)
                        }
                        cont.resume(lines)
                    }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
        }
    }
}
