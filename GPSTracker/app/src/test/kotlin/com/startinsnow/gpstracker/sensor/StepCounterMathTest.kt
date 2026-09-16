package com.startinsnow.gpstracker.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

class StepCounterMathTest {

    @Test
    fun `first reading establishes baseline with zero steps`() {
        val math = StepCounterMath()
        assertEquals(0L, math.onRawCount(10_000L))
    }

    @Test
    fun `normal accumulation matches spec example - 10000 to 10842 is 842 steps`() {
        val math = StepCounterMath()
        math.onRawCount(10_000L)
        val steps = math.onRawCount(10_842L)
        assertEquals(842L, steps)
    }

    @Test
    fun `sensor reset from reboot does not produce negative or huge step counts`() {
        val math = StepCounterMath()
        math.onRawCount(10_000L)
        math.onRawCount(10_300L) // 300 steps so far
        // Device rebooted, sensor restarted counting from a small value.
        val stepsAfterReset = math.onRawCount(500L)
        assertEquals(300L, stepsAfterReset) // no forward progress yet after reset, but no negative jump
        assertEquals(true, stepsAfterReset >= 0L)

        val stepsLater = math.onRawCount(700L)
        assertEquals(500L, stepsLater) // 300 (pre-reset) + 200 (post-reset)
    }

    @Test
    fun `multiple resets keep accumulating correctly`() {
        val math = StepCounterMath()
        math.onRawCount(0L)
        math.onRawCount(1000L)
        math.onRawCount(50L) // reset #1, accumulated 1000, new baseline 50
        math.onRawCount(200L) // 1000 + (200 - 50) = 1150
        val steps = math.onRawCount(10L) // reset #2, accumulated 1000 + (200 - 50) = 1150, new baseline 10
        assertEquals(1150L, steps)
    }

    @Test
    fun `reset clears internal state`() {
        val math = StepCounterMath()
        math.onRawCount(10_000L)
        math.onRawCount(10_500L)
        math.reset()
        assertEquals(0L, math.onRawCount(999L))
    }

    @Test
    fun `currentTrackSteps reflects last computed value without new sample`() {
        val math = StepCounterMath()
        math.onRawCount(10_000L)
        math.onRawCount(10_120L)
        assertEquals(120L, math.currentTrackSteps())
    }
}
