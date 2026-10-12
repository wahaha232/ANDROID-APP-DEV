package com.startinsnow.gpstracker.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoggerTest {
    @Test
    fun reportContainsVersionDeviceThreadAndTheStackTrace() {
        val report = CrashLogger.formatReport("1.0.99 (1000099)", "Xiaomi 2311DRK48G / Android 15 (API 35)", "main", "java.lang.IllegalStateException: boom\n\tat a.b.C.d(C.kt:1)", 0L)
        assertTrue(report.contains("app: 1.0.99 (1000099)"))
        assertTrue(report.contains("device: Xiaomi 2311DRK48G / Android 15 (API 35)"))
        assertTrue(report.contains("thread: main"))
        assertTrue(report.contains("IllegalStateException: boom"))
    }
}
