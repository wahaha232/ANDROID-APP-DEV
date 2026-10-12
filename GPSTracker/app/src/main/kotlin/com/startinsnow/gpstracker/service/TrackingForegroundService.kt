package com.startinsnow.gpstracker.service

import com.startinsnow.gpstracker.location.GpsPowerPolicy
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
import com.startinsnow.gpstracker.export.ExportTrackData
import com.startinsnow.gpstracker.location.AdaptiveSamplingPolicy
import com.startinsnow.gpstracker.location.LocationQualityManager
import com.startinsnow.gpstracker.location.LocationTracker
import com.startinsnow.gpstracker.movement.MovementDetector
import com.startinsnow.gpstracker.movement.MovementSample
import com.startinsnow.gpstracker.sensor.StepCounterManager
import com.startinsnow.gpstracker.sync.ResolvedSteps
import com.startinsnow.gpstracker.sync.StepResolver
import com.startinsnow.gpstracker.sync.StepSourceTag
import com.startinsnow.gpstracker.sync.WebDashboardSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** 這次記錄設定的 GPS 基本請求間隔（省電模式只會在它之上拉長）。 */
    private var baseGpsIntervalMs = LocationTracker.GPS_MIN_TIME_MS

    /** 手動指定的移動方式（記錄前選擇）；null = 自動。 */
    private var manualMode: MovementMode? = null

    /** 仍在寫入資料庫／上傳的收尾協程數：期間重複的「結束」必須忽略，且 Service 不可提前 stopSelf()。 */
    private var finalizingCount = 0

    private var isPaused = false
    private var isAutoPaused = false
    private var autoPauseEnabled = true
    private var stationarySinceElapsedMs: Long? = null

    private var activeDurationMs = 0L
    private var activeSegmentStartElapsedMs = 0L

    private var distanceMeters = 0.0
    private var maxSpeedMps = 0.0
    private var runningSpeedSum = 0.0
    private var runningSpeedCount = 0
    private var lastAggregatedLocation: LocationSample? = null
    private var lastMovementSample: LocationSample? = null

    /** 目前開啟中的 Movement 區段模式；暫停/恢復/重設時清為 null，確保恢復後會重新開一段。 */
    private var lastSegmentMode: MovementMode? = null
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
            ACTION_START -> startRecording(
                intent.getStringExtra(EXTRA_RESUME_TRACK_ID),
                intent.getStringExtra(EXTRA_MANUAL_MODE)
            )
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

    private fun startRecording(resumeTrackId: String?, manualModeName: String? = null) {
        if (trackId != null || isStarting) return
        // 使用者在開始前手動指定的移動方式；null 代表「自動」（由移動速度判斷）。
        manualMode = manualModeName?.let { name -> runCatching { MovementMode.valueOf(name) }.getOrNull() }
        isStarting = true
        TrackingStateHolder.update { it.copy(isStarting = true) }

        // 必須在 startForegroundService() 的 5 秒限制內呼叫 startForeground()，否則系統會直接讓 App 崩潰。
        // 因此在啟動協程、讀取 DataStore 之前就先同步進入前景服務，不把時限押在 I/O 上。
        try {
            startForegroundCompat()
        } catch (t: Throwable) {
            isStarting = false
            TrackingStateHolder.update { it.copy(status = TrackStatus.FINISHED, isStarting = false) }
            TrackingStateHolder.postMessage(getString(R.string.service_001, t.message ?: t::class.java.simpleName))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        lifecycleScope.launch {
            try {
                val settings = app.settingsRepository.settings.first()
                autoPauseEnabled = settings.autoPauseEnabled
                val highAccuracyGps = settings.gpsMode == GpsMode.HIGH_ACCURACY
                batteryMode = BatteryModeResolver.modeFor(
                    BatteryModeResolver.currentBatteryPercent(this@TrackingForegroundService)
                )

                val existingTrack = resumeTrackId?.let { app.trackRepository.getTrack(it) }
                val track = existingTrack ?: run {
                    val startLocation = locationTracker.getLastKnownBestLocation()
                    app.trackRepository.createTrack(startLocation)
                }
                trackId = track.trackId
                // 已有進行中的記錄，就不可能還有「未完成記錄」要提示。
                TrackingStateHolder.setPendingRecovery(null)
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
                    // 不用資料庫的最後一個點當聚合 anchor（lastAggregatedLocation 維持 null）：
                    // 程序被殺到使用者按「接續」之間可能已經移動了很遠（甚至重開機、elapsedRealtime 重置），
                    // 若以舊點為基準，第一個新點會把整段空白直線計入距離。新的第一個可信點會成為新的 anchor。
                    // 重要：續錄時必須先 seed 再開始觀察步數，且不能在中間呼叫 reset()，
                    // 否則先前累積的步數會被歸零（規格 57/83）。
                    stepCounterManager.seedAccumulatedSteps(stepCount)
                } else {
                    stepCounterManager.reset()
                    stepCount = 0L
                }

                activeSegmentStartElapsedMs = SystemClock.elapsedRealtime()
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
                        accuracyLabel = getString(R.string.service_002),
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
                TrackingStateHolder.postMessage(getString(R.string.service_003, t.message ?: t::class.java.simpleName))
                // 前景服務已在 startRecording() 開頭同步啟動，這裡要一併關閉，避免殘留。
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun observeLocations(id: String, highAccuracyGps: Boolean) {
        collectJob?.cancel()
        // 「高精度 GPS 模式」設定真的有作用：開啟時提高 GNSS 更新頻率（較耗電），
        // 關閉時使用預設的省電頻率，並由 AdaptiveSamplingPolicy 決定實際落地/統計的節奏。
        val gpsIntervalMs = if (highAccuracyGps) HIGH_ACCURACY_GPS_INTERVAL_MS else LocationTracker.GPS_MIN_TIME_MS
        baseGpsIntervalMs = gpsIntervalMs
        val gpsDistanceM = if (highAccuracyGps) 0f else LocationTracker.GPS_MIN_DISTANCE_M
        collectJob = locationTracker.observe(
            trackId = id,
            gpsMinTimeMs = gpsIntervalMs,
            gpsMinDistanceM = gpsDistanceM
        )
            .onEach { filtered ->
                // 單一點處理失敗（例如資料庫暫時寫入錯誤）不可讓整條定位 Flow 終止，
                // 否則通知仍顯示錄製中，之後的點卻全部遺失。
                try {
                    handleLocation(filtered)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    TrackingStateHolder.postMessage(getString(R.string.service_004, t.message ?: t::class.java.simpleName))
                }
            }
            .catch { e ->
                // 定位來源錯誤（例如權限被撤銷）不可讓例外往外炸，否則 App 會直接崩潰。
                TrackingStateHolder.postMessage(getString(R.string.service_005, e.message ?: e::class.java.simpleName))
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

        // 即使暫停中也要持續做 Movement 判斷：否則自動暫停後 movementMode 會永遠凍結在
        // STATIONARY，handleAutoPause() 不會被呼叫，resumeRecording(auto = true) 便永遠不可達。
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
        // 先處理自動暫停/恢復：若因移動而自動恢復，isPaused 會在這裡被清為 false，
        // 之後才會正確建立新的交通模式區段並記錄這個點。
        handleAutoPause(movementResult.mode)
        // 手動指定移動方式時，記錄用的模式（區段、點位、畫面）以手動為準；
        // 靜止／自動暫停仍用偵測器的結果，避免手動模式下原地不動也一直累積。
        val recordedMode = manualMode ?: movementResult.mode

        // 靜止一陣子後進入省電模式：拉長 GPS 請求間隔、不再寫入雜訊範圍內的低可信度點（見 GpsPowerPolicy）。
        val stationaryMs = stationarySinceElapsedMs?.let { SystemClock.elapsedRealtime() - it } ?: 0L
        locationTracker.setGpsIntervalOverride(
            GpsPowerPolicy.gpsIntervalMs(baseGpsIntervalMs, stationaryMs).takeIf { it != baseGpsIntervalMs }
        )
        // 用獨立的最後區段模式判斷（而非 UI 的 movementMode）：暫停期間 UI 的 movementMode 仍會更新，
        // 若拿它比較，恢復後若模式剛好相同就不會建立新區段，導致該段時間從模式統計中消失。
        if (!isPaused && lastSegmentMode != recordedMode) {
            app.trackRepository.startMovementSegment(id, recordedMode, filtered.sample.timestampMs)
            lastSegmentMode = recordedMode
        }

        TrackingStateHolder.update {
            it.copy(
                movementMode = recordedMode,
                currentSpeedKmh = GeoMath.msToKmh(effectiveSpeedMps)
            )
        }

        // 暫停中（手動或自動）只更新即時顯示，不落地點位、不累積距離/速度。
        if (isPaused) return

        if (filtered.reliability != PointReliability.TRUSTED) {
            // 低可信度 / 異常點仍寫入資料庫供除錯與 Browser Playback 顯示，但不計入距離/速度聚合；
            // 只有「靜止一陣子後落在雜訊範圍內」的點不寫（原地不動時每秒一個，只會灌大資料庫）。
            if (GpsPowerPolicy.shouldPersistPoint(filtered.reliability, stationaryMs)) {
                app.trackRepository.appendPoint(filtered, recordedMode)
                TrackingStateHolder.update { it.copy(pointCount = it.pointCount + 1) }
            }
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

        // minDistanceMeters = 0（靜止模式）代表「不靠距離觸發」，只看時間間隔；
        // 否則 distanceSince >= 0 會恆真，讓時間門檻在靜止時完全失效。
        val distanceGatePassed = plan.minDistanceMeters > 0.0 && distanceSince >= plan.minDistanceMeters
        val shouldAggregate = anchor == null || timeSinceMs >= plan.minIntervalMs || distanceGatePassed
        if (!shouldAggregate) {
            // 取樣策略擋掉的點仍然要落地（Crash Recovery / Playback 完整度），但不計入統計。
            app.trackRepository.appendPoint(filtered, recordedMode)
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

        app.trackRepository.appendPoint(filtered, recordedMode)

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
                // 單次失敗（例如寫入 GPS 中斷區間時資料庫出錯）不可讓計時器死掉，也不可讓例外殺掉整個 App。
                try {
                    tickOnce()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    TrackingStateHolder.postMessage(getString(R.string.service_006, t.message ?: t::class.java.simpleName))
                }
            }
        }
    }

    private suspend fun tickOnce() {
        val id = trackId ?: return
        val now = SystemClock.elapsedRealtime()

        if (locationTracker.qualityManager.isLost(now) && !TrackingStateHolder.state.value.isGpsLost) {
            TrackingStateHolder.update {
                it.copy(gpsQuality = LocationQuality.LOST, isGpsLost = true, accuracyLabel = getString(R.string.service_007))
            }
            if (!hasOpenOutage) {
                app.trackRepository.startOutageIfNeeded(id, System.currentTimeMillis())
                hasOpenOutage = true
            }
            if (!locationTracker.qualityManager.hasFix) {
                // 從頭到尾都沒收到定位：明確告訴使用者原因，而不是讓畫面一直停在 0 km。
                TrackingStateHolder.postMessage(
                    if (locationTracker.isAnyProviderEnabled()) {
                        getString(R.string.service_008)
                    } else {
                        getString(R.string.service_009)
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
        // 暫停期間的位移不應計入距離：清掉聚合 anchor，恢復後重新開始一段距離計算，
        // 避免「暫停→移動很遠→恢復」時把整段跳躍距離累加進來。
        lastAggregatedLocation = null
        // 清掉暫停期間累積的靜止計時，否則剛恢復就可能被自動暫停立刻又暫停（manual resume 被吃掉）。
        stationarySinceElapsedMs = null
        // 強制恢復後重新開一段 Movement 區段（暫停時已把上一段關閉）。
        lastSegmentMode = null
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
            // 上一筆還在收尾（寫入資料庫／上傳）時，重複按「結束」必須忽略：
            // 這時 stopSelf() 會取消收尾協程，讓該筆軌跡永遠停在「錄製中」變成幽靈記錄。
            if (finalizingCount > 0 || isStarting) return
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
        // 同步取走並清掉「GPS 中斷」旗標：收尾期間若立刻開始新記錄，不會和新記錄的狀態互相踩到。
        val hadOpenOutage = hasOpenOutage
        hasOpenOutage = false

        collectJob?.cancel()
        tickerJob?.cancel()
        trackId = null
        isPaused = false
        isAutoPaused = false
        finalizingCount++

        // 先同步更新 UI 狀態，讓使用者按下「結束」後畫面立刻回到非記錄中，
        // 不會還停留在舊的距離/時間（TrackStatusHolder 也要一併清乾淨）。
        TrackingStateHolder.reset()

        lifecycleScope.launch {
            try {
                // 寫入最終資料絕不可被取消（例如 Service 被停掉），否則軌跡會永遠停在「錄製中」。
                withContext(NonCancellable) {
                    runCatching {
                        if (hadOpenOutage) {
                            app.trackRepository.endOutageIfNeeded(id, System.currentTimeMillis())
                        }
                        val endTimestampMs = System.currentTimeMillis()
                        val resolvedSteps = resolveFinalStepCount(id, finalSteps, endTimestampMs)
                        app.trackRepository.finalizeTrack(
                            trackId = id,
                            endTimestampMs = endTimestampMs,
                            stepCount = resolvedSteps.steps,
                            stepSource = resolvedSteps.sourceTag,
                            activeDurationMs = finalActiveDurationMs,
                            snapshotDistanceMeters = finalDistanceMeters,
                            snapshotAvgSpeedMps = finalAvgSpeedMps,
                            snapshotMaxSpeedMps = finalMaxSpeedMps,
                            snapshotPointCount = finalPointCount
                        )
                        // Zepp Life 等來源常比結束錄製晚才同步到 Health Connect：延後補讀，讀到就更新這筆步數。
                        if (resolvedSteps.sourceTag != StepSourceTag.SENSOR) StepResyncWorker.schedule(applicationContext, id)
                    }.onFailure {
                        TrackingStateHolder.postMessage(getString(R.string.service_010, it.message ?: it::class.java.simpleName))
                        // 最後防線：至少把它從「錄製中」收尾，避免留下永遠無法結束的幽靈記錄。
                        runCatching { app.trackRepository.abandonTrack(id) }
                    }
                }
            } finally {
                finalizingCount--
            }
            // 記錄結束後自動上傳到網頁儀表板（設定有開啟且有 token 才做）。
            runCatching { uploadToWebDashboard(id) }
                .onFailure { TrackingStateHolder.postMessage(getString(R.string.detail_030, it.message ?: it::class.java.simpleName)) }
            // 收尾期間若已開始新的記錄（或還有別的收尾在跑），Service 要留給它們，不可在這裡停掉。
            if (trackId == null && !isStarting && finalizingCount == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * 記錄結束後上傳到 GitHub Pages 網頁儀表板。失敗不影響本機資料，
     * 只透過 TrackingStateHolder 回報訊息（本機優先，雲端為加分項）。
     */
    private suspend fun uploadToWebDashboard(trackId: String) {
        WebDashboardSync.uploadIfConfigured(app, trackId)
            .onSuccess { outcome ->
                when (outcome) {
                    WebDashboardSync.Outcome.SKIPPED_WIFI_ONLY -> TrackingStateHolder.postMessage(getString(R.string.service_011))
                    WebDashboardSync.Outcome.UPLOADED -> TrackingStateHolder.postMessage(getString(R.string.detail_029))
                    WebDashboardSync.Outcome.NOT_CONFIGURED, WebDashboardSync.Outcome.SKIPPED_NOT_ON_WEB -> Unit
                }
            }
            .onFailure {
                TrackingStateHolder.postMessage(getString(R.string.detail_030, it.message ?: it::class.java.simpleName))
            }
    }

    private fun currentDurationMs(): Long =
        activeDurationMs + if (!isPaused) SystemClock.elapsedRealtime() - activeSegmentStartElapsedMs else 0L

    /**
     * 決定要寫入資料庫的步數與來源：依設定來源（Google Fit 或小米 Zepp Life）經 Health Connect 讀取該趟步數
     * （aggregate，依時間比例切分），讀取失敗／未授權／尚無資料時退回裝置感測器累積的步數並標記為 SENSOR_FALLBACK
     * （之後由 StepResyncWorker 或詳情頁「重新同步步數」補讀）。不再把所有來源相加，避免重複計算。
     */
    private suspend fun resolveFinalStepCount(trackId: String, sensorSteps: Long, endMs: Long): ResolvedSteps {
        val startMs = app.trackRepository.getTrack(trackId)?.startTimeMs
            ?: return ResolvedSteps(sensorSteps, StepSourceTag.SENSOR)
        // 一律以 Google Fit 為主（與設定頁選的來源無關）；讀不到才用手機感測器並稍後補讀。
        return StepResolver(this).resolve(startMs, endMs, sensorSteps)
    }

    private fun resetAggregation() {
        distanceMeters = 0.0
        maxSpeedMps = 0.0
        runningSpeedSum = 0.0
        runningSpeedCount = 0
        lastAggregatedLocation = null
        lastMovementSample = null
        lastSegmentMode = null
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
        .addAction(0, if (isPaused) getString(R.string.recording_007) else getString(R.string.detail_039), actionPendingIntent(if (isPaused) ACTION_RESUME else ACTION_PAUSE))
        .addAction(0, getString(R.string.recording_008), actionPendingIntent(ACTION_STOP))
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
        return getString(R.string.service_012, state.movementMode.emoji, "%.2f".format(km)).format(formatDuration(state.durationMs))
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
        const val EXTRA_MANUAL_MODE = "extra_manual_mode"

        /** 即時統計寫回資料庫的最小間隔，避免每個 GPS 點都產生一次 DB transaction。 */
        const val STATS_SNAPSHOT_INTERVAL_MS = 10_000L

        /** 高精度 GPS 模式下的 GNSS 更新間隔（比預設的省電模式更頻繁、更耗電）。 */
        const val HIGH_ACCURACY_GPS_INTERVAL_MS = 1_000L

        /** [manualMode] 為 null 代表「自動」（依移動速度判斷移動方式）。 */
        fun start(context: Context, manualMode: MovementMode? = null) =
            context.startServiceCompat(ACTION_START, foreground = true) {
                manualMode?.let { putExtra(EXTRA_MANUAL_MODE, it.name) }
            }
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
                TrackingStateHolder.postMessage(getString(R.string.service_013, it.message ?: it::class.java.simpleName))
            }
        }
    }
}
