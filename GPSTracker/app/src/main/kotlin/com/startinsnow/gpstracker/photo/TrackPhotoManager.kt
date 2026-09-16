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
import com.startinsnow.gpstracker.core.model.LocationSample
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

    /** 相機是否已完成綁定，可以拍照。 */
    val isBound: Boolean get() = imageCapture != null

    /**
     * 綁定相機預覽與拍照 use case。
     * @param onResult 成功 / 失敗都會回呼；失敗時帶有可顯示給使用者的原因，不再靜默失敗。
     */
    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onResult: (Result<Unit>) -> Unit
    ) {
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
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                imageCapture = null
                onResult(Result.failure(e))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        imageCapture = null
    }

    /**
     * 拍照並寫入檔案。
     * @param sample 拍照當下的 GPS 樣本；會一併寫入 EXIF GPS（緯度 / 經度 / 高度 / 時間），
     *   讓匯出的照片本身也帶有定位資訊，而不是只有資料庫裡有座標。GPS 尚未取得時傳 null，不可因此崩潰。
     */
    suspend fun capturePhoto(trackId: String, type: PhotoType, sample: LocationSample? = null): File =
        suspendCancellableCoroutine { cont ->
            val capture = imageCapture
            if (capture == null) {
                cont.resumeWithException(IllegalStateException("相機尚未準備完成，請稍候再試"))
                return@suspendCancellableCoroutine
            }
            val dir = photoDir(context, trackId).apply { mkdirs() }
            val file = File(dir, fileNameFor(type))
            val metadata = ImageCapture.Metadata().apply {
                location = sample?.toAndroidLocation()
            }
            val outputOptions = ImageCapture.OutputFileOptions.Builder(file)
                .setMetadata(metadata)
                .build()
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

    private fun LocationSample.toAndroidLocation(): android.location.Location =
        android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
            latitude = this@toAndroidLocation.latitude
            longitude = this@toAndroidLocation.longitude
            accuracy = this@toAndroidLocation.accuracyMeters
            time = this@toAndroidLocation.timestampMs
            elapsedRealtimeNanos = this@toAndroidLocation.elapsedRealtimeMs * 1_000_000L
            this@toAndroidLocation.altitudeMeters?.let { altitude = it }
            this@toAndroidLocation.speedMps?.let { speed = it }
            this@toAndroidLocation.bearingDegrees?.let { bearing = it }
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
