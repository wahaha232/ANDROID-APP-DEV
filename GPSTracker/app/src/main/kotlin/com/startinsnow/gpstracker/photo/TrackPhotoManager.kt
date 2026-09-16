package com.startinsnow.gpstracker.photo

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.startinsnow.gpstracker.core.model.PhotoType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 對應規格「76. Photo Engine / TrackPhotoManager」。
 * 使用 CameraX 實機拍照，禁止任何 Fake Photo；照片路徑由呼叫端（Service/Repository）
 * 綁定 GPS 座標、時間、精度後寫入資料庫，本類別只負責拍照與檔案管理。
 */
class TrackPhotoManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView, onReady: (Boolean) -> Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                onReady(true)
            } catch (e: Exception) {
                onReady(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        imageCapture = null
    }

    suspend fun capturePhoto(trackId: String, type: PhotoType): File = suspendCancellableCoroutine { cont ->
        val capture = imageCapture
        if (capture == null) {
            cont.resumeWithException(IllegalStateException("Camera not bound/ready"))
            return@suspendCancellableCoroutine
        }
        val dir = photoDir(context, trackId).apply { mkdirs() }
        val file = File(dir, fileNameFor(type))
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (cont.isActive) cont.resume(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            }
        )
    }

    companion object {
        fun photoDir(context: Context, trackId: String): File =
            File(context.getExternalFilesDir("photos"), trackId)

        fun fileNameFor(type: PhotoType): String {
            val now = Date()
            return when (type) {
                PhotoType.START -> "START_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)}.jpg"
                PhotoType.END -> "END_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)}.jpg"
                PhotoType.NORMAL -> "IMG_${SimpleDateFormat("HHmmss", Locale.US).format(now)}_${System.currentTimeMillis() % 1000}.jpg"
            }
        }
    }
}
