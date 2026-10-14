package com.startinsnow.gpstracker.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.data.prefs.StepSource
import com.startinsnow.gpstracker.service.TrackingStateHolder
import com.startinsnow.gpstracker.sync.HealthConnectStepReader
import com.startinsnow.gpstracker.sync.StepResolver
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 首頁「今日步數」卡片的狀態。 */
sealed interface TodaySteps {
    data object Loading : TodaySteps
    /** 步數來源設為手機感測器：只有記錄期間才會計步，沒有「今日」的整天資料。 */
    data object SensorSource : TodaySteps
    data object Unavailable : TodaySteps
    data object NoPermission : TodaySteps
    /**
     * [primary] 為設定中選定來源的今日步數；[zepp]、[googleFit] 為各來源分開的數字（讀不到為 null）；
     * [total] 為所有來源由 Health Connect 去除重複後的合計（不是三者相加）。
     */
    data class Value(
        val source: StepSource,
        val primary: Long,
        /** 各來源分開的數字；沒有出現該來源（沒寫入 Health Connect）為 null。 */
        val zepp: Long?,
        val googleFit: Long?,
        /** 「手機本身」＝ Health Connect 內建的系統計步來源。 */
        val phone: Long?,
        /** 其他來源（例如 Fitbit）：package 與步數。 */
        val others: List<Pair<String, Long>>,
        val total: Long?
    ) : TodaySteps
    data class Error(val message: String) : TodaySteps
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<GpsTrackerApplication>()

    val recordingState = TrackingStateHolder.state
    val pendingRecovery = TrackingStateHolder.pendingRecovery
    /** null = 還在載入（此時不顯示「還沒有紀錄」的空狀態，避免冷啟動先閃一下）。 */
    val recentTracks = app.trackRepository.observeTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _deletedOnWeb = MutableStateFlow<List<com.startinsnow.gpstracker.data.db.TrackEntity>>(emptyList())
    val deletedOnWeb: StateFlow<List<com.startinsnow.gpstracker.data.db.TrackEntity>> = _deletedOnWeb
    private var lastDeletedCheckMs = 0L

    init {
        // 先前儲存的編輯若因離線等原因還沒同步到記錄網頁，開啟 App 時重試。
        viewModelScope.launch { com.startinsnow.gpstracker.sync.TrackEditsSync.flushAllDirty(app) }
    }

    /** 回到首頁時檢查網站上是否有紀錄被刪除（節流：10 分鐘內不重複查）。只提示，不自動刪除。 */
    fun checkDeletedOnWeb(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastDeletedCheckMs < 10 * 60 * 1000L) return
        lastDeletedCheckMs = now
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { com.startinsnow.gpstracker.sync.WebDashboardSync.findDeletedOnWeb(app) }
            _deletedOnWeb.value = result.getOrDefault(emptyList())
        }
    }

    /** 使用者確認：把網站上已刪除的紀錄也從手機移除（含照片與匯出檔）。 */
    fun removeDeletedOnWebLocally() {
        val targets = _deletedOnWeb.value
        _deletedOnWeb.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            targets.forEach { runCatching { app.trackRepository.deleteTrack(it.trackId) } }
        }
    }

    /** 使用者選擇保留：清掉「上傳過」標記，之後不再為這些紀錄提示。 */
    fun keepDeletedOnWebLocally() {
        val targets = _deletedOnWeb.value
        _deletedOnWeb.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            targets.forEach { runCatching { app.trackRepository.setWebUploadedAt(it.trackId, null) } }
        }
    }

    private val _stepsSyncMessage = MutableStateFlow<String?>(null)
    val stepsSyncMessage: StateFlow<String?> = _stepsSyncMessage

    /** 把每天的步數（各來源分開＋去重合計）上傳到網站，讓網站的「步數統計」有逐日資料。 */
    fun syncStepsToWeb() {
        viewModelScope.launch {
            _stepsSyncMessage.value = app.getString(com.startinsnow.gpstracker.R.string.today_steps_sync_running)
            val result = withContext(Dispatchers.IO) { com.startinsnow.gpstracker.sync.DailyStepsSync.sync(app, force = true) }
            _stepsSyncMessage.value = result.fold(
                onSuccess = {
                    when (it) {
                        com.startinsnow.gpstracker.sync.DailyStepsSync.Outcome.UPLOADED ->
                            app.getString(com.startinsnow.gpstracker.R.string.today_steps_sync_done)
                        com.startinsnow.gpstracker.sync.DailyStepsSync.Outcome.UNCHANGED ->
                            app.getString(com.startinsnow.gpstracker.R.string.today_steps_sync_unchanged)
                        com.startinsnow.gpstracker.sync.DailyStepsSync.Outcome.NOT_CONFIGURED ->
                            app.getString(com.startinsnow.gpstracker.R.string.today_steps_sync_not_configured)
                        com.startinsnow.gpstracker.sync.DailyStepsSync.Outcome.SKIPPED_WIFI_ONLY ->
                            app.getString(com.startinsnow.gpstracker.R.string.today_steps_sync_wifi)
                    }
                },
                onFailure = {
                    app.getString(com.startinsnow.gpstracker.R.string.today_steps_sync_failed, it.message ?: it::class.java.simpleName)
                }
            )
        }
    }

    private val _todaySteps = MutableStateFlow<TodaySteps>(TodaySteps.Loading)
    val todaySteps: StateFlow<TodaySteps> = _todaySteps

    /**
     * 讀取「今天（本地時區 00:00 到現在）」在 Health Connect 的步數：選定的來源為主，
     * 另一個來源若也有資料就一併列出。回到前景與按「重新整理」時呼叫（App 在前景，不需要背景讀取權限）。
     */
    /** [forceDiscover] 為 true（按「重新整理」）時重新掃描來源清單；回到前景時用快取，不重掃。 */
    fun refreshTodaySteps(forceDiscover: Boolean = false) {
        viewModelScope.launch {
            val source = runCatching { app.settingsRepository.settings.first().stepSource }.getOrNull()
                ?: StepSource.GOOGLE_FIT
            _todaySteps.value = withContext(Dispatchers.IO) { loadTodaySteps(source, forceDiscover) }
        }
    }

    private suspend fun loadTodaySteps(source: StepSource, forceDiscover: Boolean): TodaySteps {
        val reader = HealthConnectStepReader(app)
        if (!reader.isAvailable()) return TodaySteps.Unavailable
        if (!reader.hasPermission()) return TodaySteps.NoPermission

        val startMs = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val nowMs = System.currentTimeMillis()
        val resolver = StepResolver(app)
        // 掃描今天實際出現過的所有來源（「手機本身」的 package 有隨機碼，不能寫死名單），再逐一彙總。
        val breakdown = resolver.readOriginBreakdown(startMs, nowMs, forceDiscover)
            .getOrElse { return TodaySteps.Error(it.message ?: it::class.java.simpleName) }
        fun stepsOf(match: (String) -> Boolean): Long? =
            breakdown.filterKeys(match).values.takeIf { it.isNotEmpty() }?.sum()
        val zepp = stepsOf(HealthConnectStepReader::isZepp)
        val googleFit = stepsOf(HealthConnectStepReader::isGoogleFit)
        val phone = stepsOf(HealthConnectStepReader::isPhone)
        // 快取的來源清單包含「以前出現過、今天沒有資料」的 App：只列今天有步數的。
        val others = breakdown.filterKeys {
            !HealthConnectStepReader.isZepp(it) && !HealthConnectStepReader.isGoogleFit(it) && !HealthConnectStepReader.isPhone(it)
        }.filterValues { it > 0L }.toList()
        // 首頁一律以 Google Fit 為主（小米 Zepp Life 的步數會先同步到 Google Fit 再進入 Health Connect）；
        // 沒有 Google Fit 資料時才依設定的來源退回其他來源。
        val shownSource = if (googleFit != null) StepSource.GOOGLE_FIT else source
        val primary = when (shownSource) {
            StepSource.ZEPP_LIFE -> zepp
            StepSource.GOOGLE_FIT -> googleFit
            StepSource.DEVICE_SENSOR -> phone
        } ?: 0L
        val total = resolver.readHealthConnectAll(startMs, nowMs).getOrNull()
        return TodaySteps.Value(shownSource, primary, zepp, googleFit, phone, others, total)
    }

    /** 使用者選擇「不接續」時，把未完成的 Track 收尾（不會刪掉已記錄的資料）。 */
    fun abandonPendingRecovery() {
        val candidate = TrackingStateHolder.pendingRecovery.value ?: return
        // 保險：候選就是目前正在錄的 Track 時，絕不收尾，只收起提示。
        if (TrackingStateHolder.state.value.trackId == candidate.trackId) {
            TrackingStateHolder.setPendingRecovery(null)
            return
        }
        viewModelScope.launch {
            runCatching { app.trackRepository.abandonTrack(candidate.trackId) }
            TrackingStateHolder.setPendingRecovery(null)
        }
    }
}
