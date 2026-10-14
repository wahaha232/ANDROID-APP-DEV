package com.startinsnow.gpstracker.movement

import com.startinsnow.gpstracker.core.model.MovementMode.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ModeConstraintTest {

    @Test
    fun `empty selection follows the detector`() {
        assertEquals(CAR, ModeConstraint.resolve(emptySet(), CAR))
    }

    @Test
    fun `single selection always wins`() {
        assertEquals(AIRPLANE, ModeConstraint.resolve(setOf(AIRPLANE), WALKING))
    }

    @Test
    fun `detected mode inside the selection is kept`() {
        assertEquals(WALKING, ModeConstraint.resolve(setOf(WALKING, PUBLIC_TRANSPORT), WALKING))
        assertEquals(PUBLIC_TRANSPORT, ModeConstraint.resolve(setOf(WALKING, PUBLIC_TRANSPORT), PUBLIC_TRANSPORT))
    }

    @Test
    fun `detected mode outside the selection maps to the closest selected speed`() {
        // 公車走走停停被偵測成汽車 → 選了走路 + 大眾運輸，歸到大眾運輸
        assertEquals(PUBLIC_TRANSPORT, ModeConstraint.resolve(setOf(WALKING, PUBLIC_TRANSPORT), CAR))
        // 慢速被偵測成走路，但只選了單車 + 汽車 → 歸到單車
        assertEquals(BICYCLE, ModeConstraint.resolve(setOf(BICYCLE, CAR), WALKING))
    }

    @Test
    fun `stationary falls back to the slowest selected mode`() {
        assertEquals(WALKING, ModeConstraint.resolve(setOf(PUBLIC_TRANSPORT, WALKING), STATIONARY))
    }

    @Test
    fun `walking plus airplane keeps airplane when detected`() {
        assertEquals(AIRPLANE, ModeConstraint.resolve(setOf(WALKING, AIRPLANE), AIRPLANE))
    }
}
