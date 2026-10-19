package com.startinsnow.healthtracker.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** App 鎖：用手機既有的指紋 / 臉部 / 螢幕鎖定 PIN，App 自己不存任何密碼。 */
object AppLock {
    private const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** 手機有沒有可用的驗證方式（沒設螢幕鎖定就沒有）。 */
    fun available(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AUTH).build(),
        )
    }
}
