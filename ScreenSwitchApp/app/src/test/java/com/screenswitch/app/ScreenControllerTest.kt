// [Handled & Written by Cloud Agent]
package com.screenswitch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenControllerTest {

    @Test
    fun defaultStateIsNormalWithThirtySecondTimeout() {
        val controller = ScreenController()

        assertFalse(controller.state.keepAwake)
        assertEquals(30, controller.state.timeoutSeconds)
        assertFalse(controller.state.deviceAdminActive)
    }

    @Test
    fun enablingKeepAwakeEmitsCommandAndUpdatesState() {
        val controller = ScreenController()

        val commands = controller.setKeepAwake(true)

        assertEquals(listOf(ScreenCommand.SetKeepAwake(true)), commands)
        assertTrue(controller.state.keepAwake)
    }

    @Test
    fun repeatingKeepAwakeValueEmitsNoCommand() {
        val controller = ScreenController()

        controller.setKeepAwake(true)

        assertTrue(controller.setKeepAwake(true).isEmpty())
        assertTrue(controller.state.keepAwake)
    }

    @Test
    fun disablingKeepAwakeEmitsCommandAndUpdatesState() {
        val controller = ScreenController(ScreenState(keepAwake = true))

        val commands = controller.setKeepAwake(false)

        assertEquals(listOf(ScreenCommand.SetKeepAwake(false)), commands)
        assertFalse(controller.state.keepAwake)
        assertTrue(controller.setKeepAwake(false).isEmpty())
    }

    @Test
    fun validTimeoutEmitsCommandAndUpdatesState() {
        val controller = ScreenController()

        val commands = controller.setScreenTimeout(60)

        assertEquals(listOf(ScreenCommand.SetTimeout(60)), commands)
        assertEquals(60, controller.state.timeoutSeconds)
        assertTrue(controller.setScreenTimeout(60).isEmpty())
    }

    @Test
    fun timeoutBelowMinimumIsClampedToFifteenSeconds() {
        val controller = ScreenController()

        val commands = controller.setScreenTimeout(5)

        assertEquals(listOf(ScreenCommand.SetTimeout(15)), commands)
        assertEquals(15, controller.state.timeoutSeconds)
    }

    @Test
    fun timeoutAboveMaximumIsClampedToThirtyMinutes() {
        val controller = ScreenController()

        val commands = controller.setScreenTimeout(5000)

        assertEquals(listOf(ScreenCommand.SetTimeout(1800)), commands)
        assertEquals(1800, controller.state.timeoutSeconds)
    }

    @Test
    fun initialTimoutIsClamped() {
        val controller = ScreenController(ScreenState(timeoutSeconds = 1))

        assertEquals(15, controller.state.timeoutSeconds)
    }

    @Test
    fun lockRequestWithoutAdminAsksForDeviceAdmin() {
        val controller = ScreenController()

        assertEquals(listOf(ScreenCommand.RequestDeviceAdmin), controller.requestLockScreen())
    }

    @Test
    fun lockRequestWithAdminLocksImmediately() {
        val controller = ScreenController()
        controller.setDeviceAdminActive(true)

        assertEquals(listOf(ScreenCommand.LockNow), controller.requestLockScreen())
    }

    @Test
    fun deviceAdminStateCanBeUpdatedWhenDisabled() {
        val controller = ScreenController(ScreenState(deviceAdminActive = true))

        controller.setDeviceAdminActive(false)

        assertFalse(controller.state.deviceAdminActive)
        assertEquals(listOf(ScreenCommand.RequestDeviceAdmin), controller.requestLockScreen())
    }
}
