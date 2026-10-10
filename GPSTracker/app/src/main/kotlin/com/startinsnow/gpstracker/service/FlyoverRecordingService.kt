package com.startinsnow.gpstracker.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 使用 MediaProjection 把畫面（3D 飛越）錄成 MP4。全程 0 元、使用系統內建的
 * MediaProjection + MediaRecorder，不需任何第三方或付費 SDK。
 *
 * Android 14（API 34）起，MediaProjection 必須綁在一個 foregroundServiceType="mediaProjection" 的前景服務上，
 * 且需在取得 MediaProjection 之前先 startForeground，因此錄影邏輯放在這個 Service 內。
 */
class FlyoverRecordingService : Service() {

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return
        }
        try {
            startForegroundCompat()
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = manager.getMediaProjection(resultCode, data)
            projection = proj

            val metrics = resources.displayMetrics
            // H.264 需要偶數尺寸。
            val width = (metrics.widthPixels / 2) * 2
            val height = (metrics.heightPixels / 2) * 2
            val dpi = metrics.densityDpi

            val dir = getExternalFilesDir("flyovers") ?: filesDir
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "flyover_${System.currentTimeMillis()}.mp4")
            outputFile = file

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(width, height)
            rec.setVideoFrameRate(VIDEO_FRAME_RATE)
            rec.setVideoEncodingBitRate(VIDEO_BITRATE)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()

            virtualDisplay = proj.createVirtualDisplay(
                "flyover",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface,
                null,
                null
            )
            recorder = rec
            rec.start()
            FlyoverRecorderState.setLastFile(null)
            FlyoverRecorderState.setRecording(true)
        } catch (t: Throwable) {
            FlyoverRecorderState.setRecording(false)
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleStop() {
        runCatching { recorder?.stop() }
        val file = outputFile
        FlyoverRecorderState.setLastFile(if (file != null && file.exists() && file.length() > 0) file else null)
        FlyoverRecorderState.setRecording(false)
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        runCatching { virtualDisplay?.release() }
        runCatching { recorder?.release() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        recorder = null
        projection = null
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "3D 飛越錄影", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在錄製 3D 飛越…")
            .setContentText("錄影中，完成後可分享到社群")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        cleanup()
        FlyoverRecorderState.setRecording(false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.startinsnow.gpstracker.action.FLYOVER_RECORD_START"
        const val ACTION_STOP = "com.startinsnow.gpstracker.action.FLYOVER_RECORD_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "gps_tracker_flyover"
        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_BITRATE = 6_000_000

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, FlyoverRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FlyoverRecordingService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }
    }
}
