package com.startinsnow.gpstracker.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.service.TrackingStateHolder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<GpsTrackerApplication>()

    val recordingState = TrackingStateHolder.state
    val pendingRecovery = TrackingStateHolder.pendingRecovery
    val recentTracks = app.trackRepository.observeTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 使用者選擇「不接續」時，把未完成的 Track 收尾（不會刪掉已記錄的資料）。 */
    fun abandonPendingRecovery() {
        val candidate = TrackingStateHolder.pendingRecovery.value ?: return
        viewModelScope.launch {
            runCatching { app.trackRepository.abandonTrack(candidate.trackId) }
            TrackingStateHolder.setPendingRecovery(null)
        }
    }
}
