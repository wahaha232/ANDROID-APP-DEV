package com.startinsnow.gpstracker.service

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 3D 飛越錄影的狀態（Service 寫、UI 讀）。與 [TrackingStateHolder] 相同模式：
 * 單例 StateFlow，避免 UI 直接綁定 MediaProjection / MediaRecorder。
 */
object FlyoverRecorderState {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _lastFile = MutableStateFlow<File?>(null)
    val lastFile: StateFlow<File?> = _lastFile.asStateFlow()

    fun setRecording(value: Boolean) {
        _isRecording.value = value
    }

    fun setLastFile(file: File?) {
        _lastFile.value = file
    }
}
