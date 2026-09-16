package com.startinsnow.gpstracker.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.data.db.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryFilter(
    val mode: MovementMode? = null,
    val onlyWithPhotos: Boolean = false
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<GpsTrackerApplication>()

    private val _filter = MutableStateFlow(HistoryFilter())
    val filter: StateFlow<HistoryFilter> = _filter

    val tracks: StateFlow<List<TrackEntity>> = combine(app.trackRepository.observeTracks(), _filter) { all, filter ->
        all.filter { track ->
            (filter.mode == null || track.dominantMode == filter.mode) &&
                (!filter.onlyWithPhotos || track.photoCount > 0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setModeFilter(mode: MovementMode?) {
        _filter.value = _filter.value.copy(mode = mode)
    }

    fun setOnlyWithPhotos(value: Boolean) {
        _filter.value = _filter.value.copy(onlyWithPhotos = value)
    }

    fun deleteTrack(trackId: String) {
        viewModelScope.launch { app.trackRepository.deleteTrack(trackId) }
    }
}
