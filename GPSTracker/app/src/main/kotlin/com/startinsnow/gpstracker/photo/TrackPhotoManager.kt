package com.startinsnow.gpstracker.photo

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.ZoomState
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.startinsnow.gpstracker.core.model.LocationSample
import com.startinsnow.gpstracker.core.model.PhotoType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 對應規格「76. Photo Engine / TrackPhotoManager」。
 * 使用 CameraX 實機拍照，禁止任何 Fake Photo；照片路徑由呼叫端（Service/Repository）
 * 綁定 GPS 座標、時間、精度後寫入資料庫，本類別只負責拍照與檔案管理。
 */
class TrackPhotoManager(private val context: Context) {

    /** 目前變焦狀態；給 UI 顯示倍率與上下限，並在相機回報後自動更新。 */
    data class CameraZoom(val ratio: Float = 1f, val minRatio: Float = 1f, val maxRatio: Float = 1f)

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var zoomState: androidx.lifecycle.LiveData<ZoomState>? = null
    private var zoomObserver: Observer<ZoomState>? = null

    private val _zoom = MutableStateFlow(CameraZoom())

    /** 夜景設定：預設「自動」（支援時，畫面太暗才自動切成夜景）；使用者可以在拍照畫面關掉。 */
    private val prefs = context.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
    private val _nightAuto = MutableStateFlow(prefs.getBoolean(KEY_NIGHT_AUTO, true))
    val nightAuto: StateFlow<Boolean> = _nightAuto.asStateFlow()

    /** 這支手機的相機是否開放夜景（自動／夜景擴充）給第三方 App；綁定相機後才會知道。 */
    private val _nightAvailable = MutableStateFlow(false)
    val nightAvailable: StateFlow<Boolean> = _nightAvailable.asStateFlow()

    /** 目前實際使用的擴充：AUTO／NIGHT，或 null（一般拍照）。 */
    private val _activeExtension = MutableStateFlow<String?>(null)
    val activeExtension: StateFlow<String?> = _activeExtension.asStateFlow()

    private var boundOwner: LifecycleOwner? = null
    private var boundPreview: PreviewView? = null

    /** 切換夜景自動並用新設定重新綁定相機。 */
    fun setNightAuto(enabled: Boolean, onResult: (Result<Unit>) -> Unit = {}) {
        _nightAuto.value = enabled
        prefs.edit().putBoolean(KEY_NIGHT_AUTO, enabled).apply()
        val owner = boundOwner
        val view = boundPreview
        if (owner != null && view != null) bindCamera(owner, view, onResult)
    }

    /** 變焦狀態流；綁定相機後會持續反映實際倍率，供 UI collectAsState。 */
    val zoom: StateFlow<CameraZoom> = _zoom.asStateFlow()

    /** 相機是否已完成綁定，可以拍照。 */
    val isBound: Boolean get() = imageCapture != null

    /** 設定變焦倍率，超出相機支援範圍時自動夾限。相機尚未綁定時為 no-op。 */
    fun setZoomRatio(ratio: Float) {
        val active = camera ?: return
        val state = active.cameraInfo.zoomState.value ?: return
        active.cameraControl.setZoomRatio(ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio))
    }

    /** 以目前倍率為基準放大 / 縮小（factor > 1 放大，< 1 縮小）。 */
    fun zoomBy(factor: Float) = setZoomRatio(zoomRatio * factor)

    /** 目前變焦倍率。 */
    val zoomRatio: Float get() = _zoom.value.ratio

    private fun observeZoom(lifecycleOwner: LifecycleOwner) {
        // 重新綁定時先移除舊的觀察者，避免重複註冊。
        zoomState?.let { old -> zoomObserver?.let(old::removeObserver) }
        val state = camera?.cameraInfo?.zoomState ?: return
        val observer = Observer<ZoomState> { zs ->
            if (zs != null) _zoom.value = CameraZoom(zs.zoomRatio, zs.minZoomRatio, zs.maxZoomRatio)
        }
        zoomState = state
        zoomObserver = observer
        state.observe(lifecycleOwner, observer)
        state.value?.let { _zoom.value = CameraZoom(it.zoomRatio, it.minZoomRatio, it.maxZoomRatio) }
    }

    /**
     * 綁定相機預覽與拍照 use case。
     * @param onResult 成功 / 失敗都會回呼；失敗時帶有可顯示給使用者的原因，不再靜默失敗。
     */
    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onResult: (Result<Unit>) -> Unit
    ) {
        boundOwner = lifecycleOwner
        boundPreview = previewView
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val base = CameraSelector.DEFAULT_BACK_CAMERA
                // 先偵測夜景擴充；任何失敗（廠商未開放、舊機型）都退回一般拍照，不影響原本功能。
                val extFuture = runCatching { ExtensionsManager.getInstanceAsync(context, provider) }.getOrNull()
                if (extFuture == null) {
                    bindWith(provider, lifecycleOwner, previewView, base, null, onResult)
                    return@addListener
                }
                extFuture.addListener({
                    val manager = runCatching { extFuture.get() }.getOrNull()
                    val mode = manager?.let { m ->
                        listOf(ExtensionMode.AUTO, ExtensionMode.NIGHT)
                            .firstOrNull { runCatching { m.isExtensionAvailable(base, it) }.getOrDefault(false) }
                    }
                    _nightAvailable.value = mode != null
                    val useMode = if (mode != null && _nightAuto.value) mode else null
                    val selector = if (useMode != null && manager != null) manager.getExtensionEnabledCameraSelector(base, useMode) else base
                    // 帶擴充綁定失敗時，退回一般拍照再試一次。
                    val result = bindWith(provider, lifecycleOwner, previewView, selector, useMode, null)
                    if (result.isFailure && useMode != null) {
                        bindWith(provider, lifecycleOwner, previewView, base, null, onResult)
                    } else {
                        onResult(result)
                    }
                }, ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                imageCapture = null
                camera = null
                _zoom.value = CameraZoom()
                onResult(Result.failure(e))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindWith(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        selector: CameraSelector,
        extensionMode: Int?,
        onResult: ((Result<Unit>) -> Unit)?
    ): Result<Unit> {
        val result = runCatching {
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            imageCapture = capture
            observeZoom(lifecycleOwner)
            _activeExtension.value = when (extensionMode) {
                ExtensionMode.AUTO -> "AUTO"
                ExtensionMode.NIGHT -> "NIGHT"
                else -> null
            }
        }.onFailure {
            imageCapture = null
            camera = null
            _zoom.value = CameraZoom()
            _activeExtension.value = null
        }
        onResult?.invoke(result)
        return result
    }

    fun unbind() {
        zoomState?.let { state -> zoomObserver?.let(state::removeObserver) }
        zoomState = null
        zoomObserver = null
        cameraProvider?.unbindAll()
        imageCapture = null
        camera = null
        _zoom.value = CameraZoom()
    }

    /**
     * 拍照並寫入檔案。
     * @param sample 拍照當下的 GPS 樣本；會一併寫入 EXIF GPS（緯度 / 經度 / 高度 / 時間），
     *   讓匯出的照片本身也帶有定位資訊，而不是只有資料庫裡有座標。GPS 尚未取得時傳 null，不可因此崩潰。
     */
    /**
     * @param publish false 時先不寫進系統相簿：開始記錄前拍的暫存照片，若使用者最後放棄記錄，
     *   就不該在相簿裡留下副本；正式歸入軌跡時才呼叫 [publishToGallery]。
     */
    suspend fun capturePhoto(trackId: String, type: PhotoType, sample: LocationSample? = null, publish: Boolean = true): File {
        val file = captureToFile(trackId, type, sample)
        // 另外寫一份到系統相簿（MediaStore）；App 私有目錄的檔案不會被相簿掃到，
        // 使用者會以為「照片沒存下來」。相簿寫入失敗不影響既有檔案與資料庫記錄。
        if (publish) publishToGallery(file)
        return file
    }

    private suspend fun captureToFile(trackId: String, type: PhotoType, sample: LocationSample? = null): File =
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

    /** 把拍好的檔案複製進 MediaStore.Images，讓它出現在手機相簿（Pictures/GPS Tracker）。 */
    suspend fun publishToGallery(file: File) = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_ADDED, now / 1000)
                put(MediaStore.Images.Media.DATE_TAKEN, now)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/GPS Tracker")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = resolver.insert(collection, values)
            if (uri == null) return@runCatching
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        }
        Unit
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
        private const val KEY_NIGHT_AUTO = "night_auto"

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
