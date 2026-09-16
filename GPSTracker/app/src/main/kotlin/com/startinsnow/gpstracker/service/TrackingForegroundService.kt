package com.startinsnow.gpstracker.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
import com.startinsnow.gpstracker.data.prefs.GpsMode
import com.startinsnow.gpstracker.location.AdaptiveSamplingPolicy
import com.startinsnow.gpstracker.location.LocationQualityManager
import com.startinsnow.gpstracker.location.LocationTracker
import com.startinsnow.gpstracker.movement.MovementDetector
import com.startinsnow.gpstracker.movement.MovementSample
import com.startinsnow.gpstracker.sensor.StepCounterManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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

    /** 防止重複建立 Track（MainActivity 的 Crash Recovery 與 UI 的開始記錄可能同時送 ACTION_START）。 */
    private var isStarting = false

    private var isPaused = false
    private var isAutoPaused = false
    private var autoPauseEnabled = true
    private var stationarySinceElapsedMs: Long? = null

    private var activeDurationMs = 0L
    private var activeSegmentStartElapsedMs = 0L
    private var recordingStartedElapsedMs = 0L

    private var distanceMeters = 0.0
    private var maxSpeedMps = 0.0
    private var runningSpeedSum = 0.0
    private var runningSpeedCount = 0
    private var lastAggregatedLocation: LocationSample? = null
    private var lastMovementSample: LocationSample? = null
    private var gpsPointCount = 0
    private var stepCount = 0L
    private var batteryMode = BatteryMode.NORMAL

    /** 目前是否有一個尚未結束的 GPS 中斷區間（避免每個點都查一次資料庫）。 */
    private var hasOpenOutage = false

    /** 上一次把即時統計寫回資料庫的時間（節流：不再每個 GPS 點都寫一次）。 */
    private var lastStatsSnapshotElapsedMs = 0L

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
            null -> {
                // 系統/程序重啟時可能沒有 intent：不自行續錄（交由 Crash Recovery UI 決定），
                // 只要確認沒有進行中的 Track 就結束 Service，避免空轉的殭屍 Service 與不一致狀態。
                if (trackId == null && !isStarting) {
                    TrackingStateHolder.update { it.copy(status = TrackStatus.FINISHED, isStarting = false) }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRecording(resumeTrackId: String?) {
        if (trackId != null || isStarting) return
        isStarting = true
        TrackingStateHolder.update { it.copy(isStarting = true) }

        lifecycleScope.launch {
            var foregroundStarted = false
            try {
                val settings = app.settingsRepository.settings.first()
                autoPauseEnabled = settings.autoPauseEnabled
                val highAccuracyGps = settings.gpsMode == GpsMode.HIGH_ACCURACY
                batteryMode = BatteryModeResolver.modeFor(
                    BatteryModeResolver.currentBatteryPercent(this@TrackingForegroundService)
                )

                // 一定要在 startForegroundService 的 5 秒限制內進入前景，否則系統會直接讓 App 崩潰。
                startForegroundCompat()
                foregroundStarted = true

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
                    // 重要：續錄時必須先 seed 再開始觀察步數，且不能在中間呼叫 reset()，
                    // 否則先前累積的步數會被歸零（規格 57/83）。
                    stepCounterManager.seedAccumulatedSteps(stepCount)
                } else {
                    stepCounterManager.reset()
                    stepCount = 0L
                }

                activeSegmentStartElapsedMs = SystemClock.elapsedRealtime()
                recordingStartedElapsedMs = activeSegmentStartElapsedMs
                locationTracker.qualityManager.startTracking(activeSegmentStartElapsedMs)
                isPaused = false
                isAutoPaused = false
                isStarting = false

                TrackingStateHolder.update {
                    it.copy(
                        status = TrackStatus.RECORDING,
                        trackId = track.trackId,
                        distanceMeters = distanceMeters,
                        durationMs = currentDurationMs(),
                        stepCount = stepCount,
                        movementMode = MovementMode.UNKNOWN,
                        isAutoPaused = false,
                        isStarting = false,
                        isStepSensorAvailable = stepCounterManager.isAvailable,
                        hasFix = false,
                        pointCount = gpsPointCount,
                        isGpsLost = true,
                        gpsQuality = LocationQuality.LOST,
                        accuracyLabel = "🔴 尚未收到定位",
                        message = null
                    )
                }

                observeLocations(track.trackId, highAccuracyGps)
                observeSteps()
                startTicker()
                observeSettings()
            } catch (cancellation: CancellationException) {
                isStarting = false
                throw cancellation
            } catch (t: Throwable) {
                // 任何例外（定位權限不足、前景服務被系統拒絕、資料庫寫入失敗…）都必須回報給 UI，
                // 不能讓 coroutine 例外直接沿用預設處理器殺掉整個 App。
                isStarting = false
                trackId = null
                collectJob?.cancel()
                tickerJob?.cancel()
                TrackingStateHolder.update { it.copy(status = TrackStatus.FINISHED, isStarting = false) }
                TrackingStateHolder.postMessage("無法開始記錄：${t.message ?: t::class.java.simpleName}")
                if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun observeLocations(id: String, highAccuracyGps: Boolean) {
        collectJob?.cancel()
        // 「高精度 GPS 模式」設定真的有作用：開啟時提高 GNSS 更新頻率（較耗電），
        // 關閉時使用預設的省電頻率，並由 AdaptiveSamplingPolicy 決定實際落地/統計的節奏。
        val gpsIntervalMs = if (highAccuracyGps) HIGH_ACCURACY_GPS_INTERVAL_MS else LocationTracker.GPS_MIN_TIME_MS
        val gpsDistanceM = if (highAccuracyGps) 0f else LocationTracker.GPS_MIN_DISTANCE_M
        collectJob = locationTracker.observe(
            trackId = id,
            gpsMinTimeMs = gpsIntervalMs,
            gpsMinDistanceM = gpsDistanceM
        )
            .onEach { filtered -> handleLocation(filtered) }
            .catch { e ->
                // 定位來源錯誤（例如權限被撤銷）不可讓例外往外炸，否則 App 會直接崩潰。
                TrackingStateHolder.postMessage("定位更新失敗：${e.message ?: e::class.java.simpleName}")
            }
            .launchIn(lifecycleScope)
    }

    /** 讓「自動暫停」等設定在記錄期間被修改時也能立即生效。 */
    private fun observeSettings() {
        app.settingsRepository.settings
            .onEach { autoPauseEnabled = it.autoPauseEnabled }
            .catch { /* 讀取設定失敗不影響記錄 */ }
            .launchIn(lifecycleScope)
    }

    private fun observeSteps() {
        // 不可在此呼叫 reset()：Crash Recovery 剛 seed 進去的步數會被歸零。
        // 新 Track 的歸零統一在 startRecording() 內處理。
        stepCounterManager.observeTrackSteps()
            .catch { /* 沒有步數感測器時不影響 GPS 記錄，也不偽造步數 */ }
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
                hasFix = true,
                altitudeMeters = filtered.sample.altitudeMeters ?: it.altitudeMeters
            )
        }
        // 只在真的有開啟中的中斷區間時才寫資料庫（避免每個點都查一次 DB）。
        if (hasOpenOutage) {
            app.trackRepository.endOutageIfNeeded(id, filtered.sample.timestampMs)
            hasOpenOutage = false
        }

        if (isPaused) {
            lastMovementSample = filtered.sample
            return
        }

        // GPS 不是每個點都會回報 speed（室內/Network/剛定位常見 null）。
        // 若直接當成 0 會讓 MovementDetector 一路判定靜止並觸發自動暫停，
        // 因此這裡用「與上一個樣本的位移 / 時間」推算的速度當後備值。
        val previousSample = lastMovementSample
        val impliedSpeedMps = previousSample?.let { prev ->
            val deltaSeconds = (filtered.sample.elapsedRealtimeMs - prev.elapsedRealtimeMs) / 1000.0
            if (deltaSeconds > 0) {
                GeoMath.distanceMeters(prev.latitude, prev.longitude, filtered.sample.latitude, filtered.sample.longitude) / deltaSeconds
            } else 0.0
        } ?: 0.0
        lastMovementSample = filtered.sample
        val reportedSpeedMps = filtered.sample.speedMps?.toDouble()
        val effectiveSpeedMps = reportedSpeedMps ?: impliedSpeedMps

        val movementSample = MovementSample(
            timestampMs = filtered.sample.timestampMs,
            speedMps = effectiveSpeedMps,
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
                currentSpeedKmh = GeoMath.msToKmh(effectiveSpeedMps)
            )
        }

        if (filtered.reliability != PointReliability.TRUSTED) {
            // 低可信度 / 異常點仍寫入資料庫供除錯與 Browser Playback 顯示，但不計入距離/速度聚合。
            app.trackRepository.appendPoint(filtered, movementResult.mode)
            TrackingStateHolder.update { it.copy(pointCount = it.pointCount + 1) }
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
        if (!shouldAggregate) {
            // 取樣策略擋掉的點仍然要落地（Crash Recovery / Playback 完整度），但不計入統計。
            app.trackRepository.appendPoint(filtered, movementResult.mode)
            TrackingStateHolder.update { it.copy(pointCount = it.pointCount + 1) }
            return
        }

        if (anchor != null) {
            val segmentDistance = distanceSince
            val deltaSeconds = timeSinceMs / 1000.0
            val impliedSegmentSpeed = if (deltaSeconds > 0) segmentDistance / deltaSeconds else 0.0
            val speed = reportedSpeedMps ?: impliedSegmentSpeed
            distanceMeters += segmentDistance
            runningSpeedSum += speed
            runningSpeedCount += 1
            if (speed > maxSpeedMps) maxSpeedMps = speed
        }
        lastAggregatedLocation = filtered.sample
        gpsPointCount += 1

        app.trackRepository.appendPoint(filtered, movementResult.mode)

        // 即時統計快照節流：不再每個 GPS 點都做一次 read + update transaction。
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastStatsSnapshotElapsedMs >= STATS_SNAPSHOT_INTERVAL_MS) {
            lastStatsSnapshotElapsedMs = nowElapsed
            app.trackRepository.updateLiveStats(
                trackId = id,
                distanceMeters = distanceMeters,
                avgSpeedMps = if (runningSpeedCount > 0) runningSpeedSum / runningSpeedCount else 0.0,
                maxSpeedMps = maxSpeedMps,
                stepCount = stepCount,
                gpsPointCount = TrackingStateHolder.state.value.pointCount + 1,
                durationMs = currentDurationMs()
            )
        }
        TrackingStateHolder.update {
            it.copy(
                distanceMeters = distanceMeters,
                durationMs = currentDurationMs(),
                pointCount = it.pointCount + 1
            )
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
                    if (!hasOpenOutage) {
                        app.trackRepository.startOutageIfNeeded(id, System.currentTimeMillis())
                        hasOpenOutage = true
                    }
                    if (!locationTracker.qualityManager.hasFix) {
                        // 從頭到尾都沒收到定位：明確告訴使用者原因，而不是讓畫面一直停在 0 km。
                        TrackingStateHolder.postMessage(
                            if (locationTracker.isAnyProviderEnabled()) {
                                "尚未收到 GPS 定位，請移動到室外或空曠處"
                            } else {
                                "裝置定位功能未開啟，請開啟定位後再記錄"
                            }
                        )
                    }
                }

                if (autoPauseEnabled && !isPaused && !isAutoPaused &&
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
        if (isPaused || trackId == null) return
        isPaused = true
        isAutoPaused = auto
        activeDurationMs += SystemClock.elapsedRealtime() - activeSegmentStartElapsedMs
        val id = trackId
        if (id != null) {
            lifecycleScope.launch {
                runCatching {
                    app.trackRepository.setStatus(id, TrackStatus.PAUSED)
                    app.trackRepository.closeOpenMovementSegment(id, System.currentTimeMillis())
                }
            }
        }
        TrackingStateHolder.update { it.copy(status = TrackStatus.PAUSED, isAutoPaused = auto) }
        updateNotification()
    }

    private fun resumeRecording(auto: Boolean) {
        if (!isPaused || trackId == null) return
        isPaused = false
        isAutoPaused = false
        activeSegmentStartElapsedMs = SystemClock.elapsedRealtime()
        val id = trackId
        if (id != null) {
            lifecycleScope.launch { runCatching { app.trackRepository.setStatus(id, TrackStatus.RECORDING) } }
        }
        TrackingStateHolder.update { it.copy(status = TrackStatus.RECORDING, isAutoPaused = false) }
        updateNotification()
    }

    private fun stopRecording() {
        val id = trackId
        if (id == null) {
            // 沒有進行中的 Track 也要讓 Service 收乾淨，避免殘留前景服務。
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val finalSteps = stepCount
        if (!isPaused) {
            activeDurationMs += SystemClock.elapsedRealtime() - activeSegmentStartElapsedMs
        }
        val finalActiveDurationMs = activeDurationMs
        val finalDistanceMeters = distanceMeters
        val finalMaxSpeedMps = maxSpeedMps
        val finalAvgSpeedMps = if (runningSpeedCount > 0) runningSpeedSum / runningSpeedCount else 0.0
        val finalPointCount = TrackingStateHolder.state.value.pointCount

        collectJob?.cancel()
        tickerJob?.cancel()
        trackId = null
        isPaused = false
        isAutoPaused = false

        // 先同步更新 UI 狀態，讓使用者按下「結束」後畫面立刻回到非記錄中，
        // 不會還停留在舊的距離/時間（TrackStatusHolder 也要一併清乾淨）。
        TrackingStateHolder.reset()

        lifecycleScope.launch {
            runCatching {
                if (hasOpenOutage) {
                    app.trackRepository.endOutageIfNeeded(id, System.currentTimeMillis())
                    hasOpenOutage = false
                }
                app.trackRepository.finalizeTrack(
                    trackId = id,
                    endTimestampMs = System.currentTimeMillis(),
                    stepCount = finalSteps,
                    activeDurationMs = finalActiveDurationMs,
                    snapshotDistanceMeters = finalDistanceMeters,
                    snapshotAvgSpeedMps = finalAvgSpeedMps,
                    snapshotMaxSpeedMps = finalMaxSpeedMps,
                    snapshotPointCount = finalPointCount
                )
            }.onFailure {
                TrackingStateHolder.postMessage("結束記錄時發生錯誤：${it.message ?: it::class.java.simpleName}")
            }
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
        lastMovementSample = null
        gpsPointCount = 0
        hasOpenOutage = false
        lastStatsSnapshotElapsedMs = 0L
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
        .setContentIntent(contentPendingIntent())
        .addAction(0, if (isPaused) "繼續" else "暫停", actionPendingIntent(if (isPaused) ACTION_RESUME else ACTION_PAUSE))
        .addAction(0, "結束", actionPendingIntent(ACTION_STOP))
        .build()

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, com.startinsnow.gpstracker.ui.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

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

        /** 即時統計寫回資料庫的最小間隔，避免每個 GPS 點都產生一次 DB transaction。 */
        const val STATS_SNAPSHOT_INTERVAL_MS = 10_000L

        /** 高精度 GPS 模式下的 GNSS 更新間隔（比預設的省電模式更頻繁、更耗電）。 */
        const val HIGH_ACCURACY_GPS_INTERVAL_MS = 1_000L

        fun start(context: Context) = context.startServiceCompat(ACTION_START, foreground = true)
        fun startResume(context: Context, trackId: String) =
            context.startServiceCompat(ACTION_START, foreground = true) { putExtra(EXTRA_RESUME_TRACK_ID, trackId) }
        fun pause(context: Context) = context.startServiceCompat(ACTION_PAUSE)
        fun resume(context: Context) = context.startServiceCompat(ACTION_RESUME)
        fun stop(context: Context) = context.startServiceCompat(ACTION_STOP)

        /**
         * 只有 ACTION_START 需要 startForegroundService()（之後 Service 必須在 5 秒內進入前景）。
         * PAUSE / RESUME / STOP 都只是對「已經在前景的 Service」下指令，用 startService() 即可，
         * 避免 Service 還沒進入前景就被要求停止時觸發系統的前景服務規定。
         */
        private fun Context.startServiceCompat(
            action: String,
            foreground: Boolean = false,
            extras: Intent.() -> Unit = {}
        ) {
            val intent = Intent(this, TrackingForegroundService::class.java).apply {
                this.action = action
                extras()
            }
            runCatching {
                if (foreground) {
                    androidx.core.content.ContextCompat.startForegroundService(this, intent)
                } else {
                    startService(intent)
                }
            }.onFailure {
                TrackingStateHolder.postMessage("無法啟動記錄服務：${it.message ?: it::class.java.simpleName}")
            }
        }
    }
}
