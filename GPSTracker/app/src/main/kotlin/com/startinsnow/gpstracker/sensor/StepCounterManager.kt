package com.startinsnow.gpstracker.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 對應規格「17~18. 本機步數計」。
 * 優先使用 TYPE_STEP_COUNTER（開機以來累積步數，最省電），裝置沒有該感測器時
 * 退而使用 TYPE_STEP_DETECTOR（每偵測到一步觸發一次事件，自行累加）。
 * 完全不整合 Google Fit / Health Connect / 第三方健康平台。
 */
class StepCounterManager(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounterSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val stepDetectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    val isAvailable: Boolean get() = stepCounterSensor != null || stepDetectorSensor != null

    private val math = StepCounterMath()
    private var detectorAccumulated = 0L

    fun reset() {
        math.reset()
        detectorAccumulated = 0L
    }

    fun seedAccumulatedSteps(steps: Long) {
        math.seedAccumulated(steps)
        detectorAccumulated = steps
    }

    /** 目前 Track 已累積步數（不需要有新事件也可查詢）。 */
    fun currentTrackSteps(): Long =
        if (stepCounterSensor != null) math.currentTrackSteps() else detectorAccumulated

    fun observeTrackSteps(): Flow<Long> = callbackFlow {
        val sensor = stepCounterSensor
        if (sensor != null) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val raw = event.values[0].toLong()
                    trySend(math.onRawCount(raw))
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            awaitClose { sensorManager.unregisterListener(listener) }
        } else {
            val detector = stepDetectorSensor
            if (detector != null) {
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        detectorAccumulated += 1
                        trySend(detectorAccumulated)
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensorManager.registerListener(listener, detector, SensorManager.SENSOR_DELAY_NORMAL)
                awaitClose { sensorManager.unregisterListener(listener) }
            } else {
                // 裝置沒有任何步數感測器：不偽造步數，直接關閉這條 Flow。
                close()
            }
        }
    }
}
