package com.startinsnow.gpstracker.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.R
import com.startinsnow.gpstracker.core.model.BatteryMode
import com.startinsnow.gpstracker.core.model.FilteredLocation
import com.startinsnow.gpstracker.core.model.LocationQuality
import com.startinsnow.gpstracker.core.model.LocationSample
import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.PointReliability
import com.startinsnow.gpstracker.core.model.TrackStatus
import com.startinsnow.gpstracker.core.geo.GeoMath
import com.startinsnow.gpstracker.location.AdaptiveSamplingPolicy
import com.startinsnow.gpstracker.location.LocationQualityManager
import com.startinsnow.gpstracker.location.LocationTracker
import com.startinsnow.gpstracker.movement.MovementDetector
import com.startinsnow.gpstracker.movement.MovementSample
import com.startinsnow.gpstracker.sensor.StepCounterManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 背景記錄的核心引擎（規格 56. Background Recording / 73. GPS Engine 的容器）。
 * 把 LocationTracker / MovementDetector / StepCounterManager / TrackRepository 組合起來，
 * 對外只透過 [TrackingStateHolder] 與 Notification 溝通，UI 完全不需要碰 Service 內部細節。
 */
class TrackingForegroundService : LifecycleService() {

    private lateinit var app: GpsTrackerApplication
    private lateinit var locationTracker: LocationTracker
    private lateinit var stepCounterManager: StepCounterManager
    private val movementDetector = MovementDetector()

    private var trackId: String? = null
    private var collectJob: Job? = null
    private var tickerJob: Job? = null

    private var isPaused = false
    private var isAutoPaused = false
    private var stationarySinceElapsedMs: Long? = null

    private var activeDurationMs = 0L
    private var activeSegmentStartElapsedMs = 0L

    private var distanceMeters = 0.0
    private var maxSpeedMps = 0.0
    private var runningSpeedSum = 0.0
    private var runningSpeedCount = 0
    private var lastAggregatedLocation: LocationSample? = null
    private var gpsPointCount = 0
    private var stepCount = 0L
    private var batteryMode = BatteryMode.NORMAL

    override fun onCreate() {
        super.onCreate()
        app = application as GpsTrackerApplication
        locationTracker = LocationTracker(this)
        stepCounterManager = StepCounterManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getStringExtra(EXTRA_RESUME_TRACK_ID))
            ACTION_PAUSE -> pauseRecording(auto = false)
            ACTION_RESUME -> resumeRecording(auto = false)
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    private fun startRecording(resumeTrackId: String?) {
        if (trackId != null) return
        startForeground(NOTIFICATION_ID, buildNotification())

        lifecycleScope.launch {
            batteryMode = BatteryModeResolver.modeFor(BatteryModeResolver.currentBatteryPercent(this@TrackingForegroundService))

            val existingTrack = resumeTrackId?.let { app.trackRepository.getTrack(it) }
            val track = existingTrack ?: run {
                val startLocation = locationTracker.getLastKnownBestLocation()
                app.trackRepository.createTrack(startLocation)
            }
            trackId = track.trackId
            resetAggregation()

            if (existingTrack != null) {
                // Crash Recovery（規格 57）：從資料庫已保存的統計接續，而不是歸零重算。
                distanceMeters = existingTrack.distanceMeters
                maxSpeedMps = existingTrack.maxSpeedMps
                gpsPointCount = existingTrack.gpsPointCount
                if (existingTrack.gpsPointCount > 0) {
                    runningSpeedSum = existingTrack.avgSpeedMps * existingTrack.gpsPointCount
                    runningSpeedCount = existingTrack.gpsPointCount
                }
                activeDurationMs = existingTrack.durationMs
                stepCount = existingTrack.stepCount
                val lastPoint = app.trackRepository.getLastPoint(track.trackId)
                if (lastPoint != null && lastPoint.reliability == "TRUSTED") {
                    lastAggregatedLocation = com.startinsnow.gpstracker.core.model.LocationSample(
                        latitude = lastPoint.latitude,
                        longitude = lastPoint.longitude,
                        timestampMs = lastPoint.timestampMs,
                        elapsedRealtimeMs = lastPoint.elapsedRealtimeMs,
                        speedMps = lastPoint.speedMps,
                        speedAccuracyMps = null,
                        altitudeMeters = lastPoint.altitudeMeters,
                        altitudeAccuracyMeters = null,
                        bearingDegrees = lastPoint.bearingDegrees,
                        bearingAccuracyDegrees = null,
                        accuracyMeters = lastPoint.accuracyMeters,
                        provider = com.startinsnow.gpstracker.core.model.LocationProvider.valueOf(
                            runCatching { lastPoint.provider }.getOrDefault("UNKNOWN")
                        ),
                        trackId = track.trackId
                    )
                }
            }

            activeSegmentStartElapsedMs = SystemClock.elapsedRealtime()
            isPaused = false
            isAutoPaused = false

            TrackingStateHolder.update {
                it.copy(
                    status = TrackStatus.RECORDING,
                    trackId = track.trackId,
                    distanceMeters = distanceMeters,
                    durationMs = currentDurationMs(),
                    stepCount = stepCount,
                    movementMode = MovementMode.UNKNOWN,
                    isAutoPaused = false
                )
            }

            if (existingTrack != null) {
                stepCounterManager.seedAccumulatedSteps(stepCount)
            }
            observeLocations(track.trackId)
            observeSteps()
            startTicker()
        }
    }

    private fun observeLocations(id: String) {
        collectJob?.cancel()
        collectJob = locationTracker.observe(id)
            .onEach { filtered -> handleLocation(filtered) }
            .launchIn(lifecycleScope)
    }

    private fun observeSteps() {
        stepCounterManager.reset()
        stepCounterManager.observeTrackSteps()
            .onEach { steps ->
                stepCount = steps
                TrackingStateHolder.update { it.copy(stepCount = steps) }
            }
            .launchIn(lifecycleScope)
    }

    private suspend fun handleLocation(filtered: FilteredLocation) {
        val id = trackId ?: return
        TrackingStateHolder.updateLastLocation(filtered.sample)

        val quality = filtered.quality
        TrackingStateHolder.update {
            it.copy(
                gpsQuality = quality,
                accuracyMeters = filtered.sample.accuracyMeters,
                accuracyLabel = LocationQualityManager.accuracyLabel(quality, filtered.sample.accuracyMeters, filtered.sample.provider),
                isGpsLost = false,
                altitudeMeters = filtered.sample.altitudeMeters ?: it.altitudeMeters
            )
        }
        app.trackRepository.endOutageIfNeeded(id, filtered.sample.timestampMs)

        if (isPaused) return

        val movementSample = MovementSample(
            timestampMs = filtered.sample.timestampMs,
            speedMps = (filtered.sample.speedMps?.toDouble()) ?: 0.0,
            accuracyMeters = filtered.sample.accuracyMeters,
            altitudeMeters = filtered.sample.altitudeMeters,
            reliability = filtered.reliability
        )
        val movementResult = movementDetector.addSample(movementSample)
        if (movementResult.mode != TrackingStateHolder.state.value.movementMode) {
            app.trackRepository.startMovementSegment(id, movementResult.mode, filtered.sample.timestampMs)
        }
        handleAutoPause(movementResult.mode)

        TrackingStateHolder.update {
            it.copy(
                movementMode = movementResult.mode,
                currentSpeedKmh = GeoMath.msToKmh((filtered.sample.speedMps?.toDouble()) ?: 0.0)
            )
        }

        if (filtered.reliability != PointReliability.TRUSTED) {
            // 低可信度 / 異常點仍寫入資料庫供除錯與 Browser Playback 顯示，但不計入距離/速度聚合。
            app.trackRepository.appendPoint(filtered, movementResult.mode)
            return
        }

        val plan = AdaptiveSamplingPolicy.plan(
            mode = movementResult.mode,
            quality = quality,
            batteryMode = batteryMode,
            stationaryDurationMs = stationarySinceElapsedMs?.let { SystemClock.elapsedRealtime() - it } ?: 0L
        )
        val anchor = lastAggregatedLocation
        val timeSinceMs = anchor?.let { filtered.sample.elapsedRealtimeMs - it.elapsedRealtimeMs } ?: Long.MAX_VALUE
        val distanceSince = anchor?.let {
            GeoMath.distanceMeters(it.latitude, it.longitude, filtered.sample.latitude, filtered.sample.longitude)
        } ?: Double.MAX_VALUE

        val shouldAggregate = anchor == null || timeSinceMs >= plan.minIntervalMs || distanceSince >= plan.minDistanceMeters
        if (!shouldAggregate) return

        if (anchor != null) {
            val segmentDistance = distanceSince
            val deltaSeconds = timeSinceMs / 1000.0
            val impliedSpeed = if (deltaSeconds > 0) segmentDistance / deltaSeconds else 0.0
            val speed = filtered.sample.speedMps?.toDouble() ?: impliedSpeed
            distanceMeters += segmentDistance
            runningSpeedSum += speed
            runningSpeedCount += 1
            if (speed > maxSpeedMps) maxSpeedMps = speed
        }
        lastAggregatedLocation = filtered.sample
        gpsPointCount += 1

        app.trackRepository.appendPoint(filtered, movementResult.mode)
        app.trackRepository.updateLiveStats(
            trackId = id,
            distanceMeters = distanceMeters,
            avgSpeedMps = if (runningSpeedCount > 0) runningSpeedSum / runningSpeedCount else 0.0,
            maxSpeedMps = maxSpeedMps,
            stepCount = stepCount,
            gpsPointCount = gpsPointCount,
            durationMs = currentDurationMs()
        )
        TrackingStateHolder.update {
            it.copy(distanceMeters = distanceMeters, durationMs = currentDurationMs())
        }
        updateNotification()
    }

    private fun handleAutoPause(mode: MovementMode) {
        if (mode == MovementMode.STATIONARY) {
            if (stationarySinceElapsedMs == null) stationarySinceElapsedMs = SystemClock.elapsedRealtime()
        } else {
            stationarySinceElapsedMs = null
            if (isAutoPaused) {
                resumeRecording(auto = true)
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                val id = trackId ?: continue
                val now = SystemClock.elapsedRealtime()

                if (locationTracker.qualityManager.isLost(now) && !TrackingStateHolder.state.value.isGpsLost) {
                    TrackingStateHolder.update {
                        it.copy(gpsQuality = LocationQuality.LOST, isGpsLost = true, accuracyLabel = "🔴 GPS 遺失")
                    }
                    app.trackRepository.startOutageIfNeeded(id, System.currentTimeMillis())
                }

                if (!isPaused && !isAutoPaused &&
                    stationarySinceElapsedMs != null &&
                    (now - stationarySinceElapsedMs!!) > AUTO_PAUSE_THRESHOLD_MS
                ) {
                    pauseRecording(auto = true)
                }

                batteryMode = BatteryModeResolver.modeFor(BatteryModeResolver.currentBatteryPercent(this@TrackingForegroundService))
                TrackingStateHolder.update {
                    it.copy(
                        durationMs = currentDurationMs(),
                        batteryPercent = BatteryModeResolver.currentBatteryPercent(this@TrackingForegroundService)
                    )
                }
                updateNotification()
            }
        }
    }

    private fun pauseRecording(auto: Boolean) {
        if (isPaused) return
        isPaused = true
        isAutoPaused = auto
        activeDurationMs += SystemClock.elapsedRealtime() - activeSegmentStartElapsedMs
        val id = trackId
        if (id != null) {
            lifecycleScope.launch {
                app.trackRepository.setStatus(id, TrackStatus.PAUSED)
                app.trackRepository.closeOpenMovementSegment(id, System.currentTimeMillis())
            }
        }
        TrackingStateHolder.update { it.copy(status = TrackStatus.PAUSED, isAutoPaused = auto) }
        updateNotification()
    }

    private fun resumeRecording(auto: Boolean) {
        if (!isPaused) return
        isPaused = false
        isAutoPaused = false
        activeSegmentStartElapsedMs = SystemClock.elapsedRealtime()
        val id = trackId
        if (id != null) {
            lifecycleScope.launch { app.trackRepository.setStatus(id, TrackStatus.RECORDING) }
        }
        TrackingStateHolder.update { it.copy(status = TrackStatus.RECORDING, isAutoPaused = false) }
        updateNotification()
    }

    private fun stopRecording() {
        val id = trackId ?: return
        val finalSteps = stepCount
        lifecycleScope.launch {
            if (!isPaused) {
                activeDurationMs += SystemClock.elapsedRealtime() - activeSegmentStartElapsedMs
            }
            app.trackRepository.finalizeTrack(id, System.currentTimeMillis(), finalSteps)
            TrackingStateHolder.update { it.copy(status = TrackStatus.FINISHED) }
            collectJob?.cancel()
            tickerJob?.cancel()
            trackId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun currentDurationMs(): Long =
        activeDurationMs + if (!isPaused) SystemClock.elapsedRealtime() - activeSegmentStartElapsedMs else 0L

    private fun resetAggregation() {
        distanceMeters = 0.0
        maxSpeedMps = 0.0
        runningSpeedSum = 0.0
        runningSpeedCount = 0
        lastAggregatedLocation = null
        gpsPointCount = 0
        stepCount = 0L
        activeDurationMs = 0L
        stationarySinceElapsedMs = null
        movementDetector.reset()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, GpsTrackerApplication.RECORDING_CHANNEL_ID)
        .setContentTitle(getString(R.string.notif_recording_title))
        .setContentText(notificationText())
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(0, if (isPaused) "繼續" else "暫停", actionPendingIntent(if (isPaused) ACTION_RESUME else ACTION_PAUSE))
        .addAction(0, "結束", actionPendingIntent(ACTION_STOP))
        .build()

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun notificationText(): String {
        val state = TrackingStateHolder.state.value
        val km = state.distanceMeters / 1000.0
        return "${state.movementMode.emoji} ${"%.2f".format(km)} km｜%s".format(formatDuration(state.durationMs))
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, TrackingForegroundService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onDestroy() {
        super.onDestroy()
        collectJob?.cancel()
        tickerJob?.cancel()
    }

    companion object {
        const val ACTION_START = "com.startinsnow.gpstracker.action.START"
        const val ACTION_PAUSE = "com.startinsnow.gpstracker.action.PAUSE"
        const val ACTION_RESUME = "com.startinsnow.gpstracker.action.RESUME"
        const val ACTION_STOP = "com.startinsnow.gpstracker.action.STOP"

        const val NOTIFICATION_ID = 1001
        const val TICK_INTERVAL_MS = 5_000L
        const val AUTO_PAUSE_THRESHOLD_MS = 60_000L
        const val EXTRA_RESUME_TRACK_ID = "extra_resume_track_id"

        fun start(context: Context) = context.startForegroundServiceCompat(ACTION_START)
        fun startResume(context: Context, trackId: String) =
            context.startForegroundServiceCompat(ACTION_START) { putExtra(EXTRA_RESUME_TRACK_ID, trackId) }
        fun pause(context: Context) = context.startForegroundServiceCompat(ACTION_PAUSE)
        fun resume(context: Context) = context.startForegroundServiceCompat(ACTION_RESUME)
        fun stop(context: Context) = context.startForegroundServiceCompat(ACTION_STOP)

        private fun Context.startForegroundServiceCompat(action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(this, TrackingForegroundService::class.java).apply {
                this.action = action
                extras()
            }
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
        }
    }
}
