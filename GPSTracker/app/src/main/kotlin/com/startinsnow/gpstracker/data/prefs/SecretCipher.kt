package com.startinsnow.gpstracker.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 的 AES-GCM 金鑰保護敏感設定（目前是 GitHub Token），不再以明文存進 DataStore。
 *
 * 金鑰存在硬體／系統 Keystore 內、不可匯出；DataStore 檔案被讀走（root、除錯備份）也無法還原明文。
 * 儲存格式：`enc:v1:` + Base64(12 bytes IV + 密文)。
 *
 * - 沒有 `enc:v1:` 前綴的值視為舊版明文，原樣回傳（之後由 [SettingsRepository.migrateLegacySecrets] 轉成密文）。
 * - 解密失敗（例如金鑰因還原備份／清除安全性資料而消失）回傳空字串：要求使用者重新輸入，而不是當機。
 */
object SecretCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "gpstracker_secret_key_v1"
    private const val PREFIX = "enc:v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!isEncrypted(stored)) return stored
        return runCatching {
            val bytes = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES))
            )
            String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
