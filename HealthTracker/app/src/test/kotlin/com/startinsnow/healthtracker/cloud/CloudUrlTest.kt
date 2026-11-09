package com.startinsnow.healthtracker.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用者貼上 /rest/v1 等端點時，必須自動收斂成專案根網址，避免 /rest/v1/rest/v1 造成 404。 */
class CloudUrlTest {
    @Test fun stripsApiSuffixesAndPaths() {
        assertEquals("https://abc.supabase.co", normalizeSupabaseUrl("https://abc.supabase.co/rest/v1"))
        assertEquals("https://abc.supabase.co", normalizeSupabaseUrl("https://abc.supabase.co/rest/v1/"))
        assertEquals("https://abc.supabase.co", normalizeSupabaseUrl("https://abc.supabase.co/auth/v1"))
        assertEquals("https://abc.supabase.co", normalizeSupabaseUrl("https://abc.supabase.co/storage/v1"))
        assertEquals("https://abc.supabase.co", normalizeSupabaseUrl("https://abc.supabase.co/rest/v1/health_records"))
        assertEquals("https://abc.supabase.co", normalizeSupabaseUrl("https://abc.supabase.co/"))
    }

    @Test fun onlyRealSupabaseHostsAreAccepted() {
        assertTrue(isSupabaseUrl("https://abc.supabase.co"))
        assertTrue(isSupabaseUrl("https://abc-123.supabase.co"))
        assertFalse(isSupabaseUrl("https://abc.supabase.co.evil.com")) // 釣魚：含有 .supabase.co 但不是 supabase 的網域
        assertFalse(isSupabaseUrl("https://evil.com/abc.supabase.co"))
        assertFalse(isSupabaseUrl("https://abc.supabase.co@evil.com"))
        assertFalse(isSupabaseUrl("https://user@abc.supabase.co"))
        assertFalse(isSupabaseUrl("http://abc.supabase.co"))
        assertFalse(isSupabaseUrl("https://supabase.co"))
        assertFalse(isSupabaseUrl("https://.supabase.co"))
        assertFalse(isSupabaseUrl("abc.supabase.co"))
    }

    @Test fun phishingHostSurvivesNormalizationButIsRejected() {
        val n = normalizeSupabaseUrl("https://abc.supabase.co.evil.com/rest/v1")
        assertFalse(isSupabaseUrl(n))
    }

    private fun jwt(role: String): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"HS256"}""".toByteArray()) + "." +
            enc.encodeToString("""{"iss":"supabase","role":"$role","exp":2000000000}""".toByteArray()) + ".sig"
    }

    @Test fun serviceRoleAndSecretKeysAreRejected() {
        assertNull(anonKeyProblem(jwt("anon")))
        assertNotNull(anonKeyProblem(jwt("service_role")))
        assertNotNull(anonKeyProblem(jwt("authenticated")))
        assertNotNull(anonKeyProblem("sb_secret_" + "x".repeat(30)))
        assertNull(anonKeyProblem("sb_publishable_" + "x".repeat(30)))
        assertNull(anonKeyProblem("not-a-jwt-but-long-enough-key-string"))
    }

    @Test fun keepsPlainBaseAndTrimsWhitespace() {
        assertEquals("https://abc.supabase.co", normalizeSupabaseUrl(" https://abc.supabase.co "))
    }
}
