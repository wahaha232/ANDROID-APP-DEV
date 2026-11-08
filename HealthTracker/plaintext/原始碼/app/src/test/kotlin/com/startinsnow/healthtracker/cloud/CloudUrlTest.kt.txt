package com.startinsnow.healthtracker.cloud

import org.junit.Assert.assertEquals
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

    @Test fun keepsPlainBaseAndTrimsWhitespace() {
        assertEquals("https://abc.supabase.co", normalizeSupabaseUrl(" https://abc.supabase.co "))
    }
}
