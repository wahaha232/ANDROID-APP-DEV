// [Handled & Written by Cloud Agent]
// [LOCAL-AI SIGNATURE: DeepSeek-Coder-V2-Lite-Instruct-IQ4_XS @ 2026-10-25 | ScreenSwitchApp]
package com.screenswitch.app

data class ScreenState(
    val keepAwake: Boolean = false,
    val timeoutSeconds: Int = 30,
    val deviceAdminActive: Boolean = false
)

sealed interface ScreenCommand {
    data class SetKeepAwake(val enabled: Boolean) : ScreenCommand
    data class SetTimeout(val seconds: Int) : ScreenCommand
    data object LockNow : ScreenCommand
    data object RequestDeviceAdmin : ScreenCommand
}

class ScreenController(initialState: ScreenState = ScreenState()) {
    var state: ScreenState
        private set

    init {
        state = initialState.copy(timeoutSeconds = initialState.timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS))
    }

    fun setKeepAwake(enabled: Boolean): List<ScreenCommand> {
        if (state.keepAwake == enabled) return emptyList()
        state = state.copy(keepAwake = enabled)
        return listOf(ScreenCommand.SetKeepAwake(enabled))
    }

    fun setScreenTimeout(seconds: Int): List<ScreenCommand> {
        val clamped = seconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        if (state.timeoutSeconds == clamped) return emptyList()
        state = state.copy(timeoutSeconds = clamped)
        return listOf(ScreenCommand.SetTimeout(clamped))
    }

    fun requestLockScreen(): List<ScreenCommand> {
        return if (state.deviceAdminActive) {
            listOf(ScreenCommand.LockNow)
        } else {
            listOf(ScreenCommand.RequestDeviceAdmin)
        }
    }

    fun setDeviceAdminActive(active: Boolean) {
        state = state.copy(deviceAdminActive = active)
    }

    companion object {
        const val MIN_TIMEOUT_SECONDS = 15
        const val MAX_TIMEOUT_SECONDS = 1800
    }
}
