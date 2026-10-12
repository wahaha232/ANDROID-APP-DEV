package com.startinsnow.gpstracker.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只測不需要 Android Keystore 的部分（純 JVM）：前綴判斷、舊版明文相容、遮蔽輸出。 */
class SecretCipherTest {

    @Test
    fun `legacy plaintext is returned as is`() {
        assertEquals("github_pat_legacy", SecretCipher.decrypt("github_pat_legacy"))
    }

    @Test
    fun `empty stays empty`() {
        assertEquals("", SecretCipher.decrypt(""))
        assertEquals("", SecretCipher.encrypt(""))
    }

    @Test
    fun `isEncrypted only recognises the versioned prefix`() {
        assertTrue(SecretCipher.isEncrypted("enc:v1:AAAA"))
        assertFalse(SecretCipher.isEncrypted("github_pat_legacy"))
        assertFalse(SecretCipher.isEncrypted(""))
    }

    @Test
    fun `AppSettings toString never prints the token`() {
        val text = AppSettings(githubToken = "github_pat_very_secret").toString()
        assertFalse(text.contains("very_secret"))
        assertTrue(text.contains("githubToken="))
    }

    @Test
    fun `AppSettings toString marks an empty token as empty`() {
        assertTrue(AppSettings(githubToken = "").toString().contains("githubToken=<empty>"))
    }
}
