package com.startinsnow.gpstracker.movement

import com.startinsnow.gpstracker.core.model.MovementMode
import kotlin.math.abs
import kotlin.math.ln

/**
 * 記錄前使用者可複選移動方式（例如走路 + 大眾運輸）。偵測器照常依速度判斷，
 * 再把結果收斂到使用者選的範圍內：
 *  - 沒選（空集合）：自動，直接用偵測結果。
 *  - 只選一種：整趟都記成那一種（原本的手動模式）。
 *  - 選多種：偵測結果在範圍內就用它；不在範圍內，就取「典型速度最接近」的那一種。
 */
object ModeConstraint {

    /** 各移動方式的典型速度（km/h），只用來把偵測結果對應到最接近的已選方式。 */
    private fun typicalKmh(mode: MovementMode): Double = when (mode) {
        MovementMode.WALKING -> 5.0
        MovementMode.RUNNING -> 10.0
        MovementMode.BICYCLE -> 18.0
        MovementMode.PUBLIC_TRANSPORT -> 30.0
        MovementMode.MOTORCYCLE -> 45.0
        MovementMode.CAR -> 60.0
        MovementMode.AIRPLANE -> 700.0
        MovementMode.STATIONARY, MovementMode.UNKNOWN -> 1.0
    }

    fun resolve(allowed: Set<MovementMode>, detected: MovementMode): MovementMode {
        if (allowed.isEmpty()) return detected
        if (allowed.size == 1) return allowed.first()
        // 靜止／未知時沒有速度可比：停等中的人通常還在最慢的那種方式上（等車、走路）。
        if (detected == MovementMode.STATIONARY || detected == MovementMode.UNKNOWN) {
            return allowed.minBy { typicalKmh(it) }
        }
        if (detected in allowed) return detected
        // 用對數比例比較，因為速度是倍數關係（5 與 30 的差距比 55 與 60 的差距大得多）。
        val target = ln(typicalKmh(detected))
        return allowed.minBy { abs(ln(typicalKmh(it)) - target) }
    }
}
