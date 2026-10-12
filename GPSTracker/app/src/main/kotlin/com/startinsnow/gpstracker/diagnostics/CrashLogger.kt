package com.startinsnow.gpstracker.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本機當機紀錄：不依賴任何外部服務、不上傳任何資料。
 * App 當機時把堆疊寫成文字檔（只留最近 [KEEP] 份），使用者可以在「設定 → 診斷」查看筆數、分享或清除。
 * release 版有 R8 混淆，堆疊裡的類別名稱是縮寫；需要搭配該版本的 mapping.txt 還原。
 */
object CrashLogger {

    private const val DIR = "crash-logs"
    private const val KEEP = 5

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun dir(context: Context) = File(context.filesDir, DIR)

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            // minSdk 26：longVersionCode 需要 API 28，這裡只是顯示用，用舊的 versionCode 即可。
            @Suppress("DEPRECATION")
            "${info.versionName} (${info.versionCode})"
        }.getOrDefault("?")
        val device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val report = formatReport(version, device, thread.name, trace, now)

        val folder = dir(context).apply { mkdirs() }
        File(folder, "crash_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))}.txt").writeText(report)
        // 只留最近 KEEP 份。
        folder.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    /** 報告格式（純文字，不含任何個人資料，只有版本、機型與堆疊）。獨立成函式方便單元測試。 */
    fun formatReport(appVersion: String, device: String, threadName: String, stackTrace: String, timeMs: Long): String =
        buildString {
            appendLine("GPS TRACKER crash report")
            appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))}")
            appendLine("app: $appVersion")
            appendLine("device: $device")
            appendLine("thread: $threadName")
            appendLine()
            append(stackTrace)
        }

    fun count(context: Context): Int = dir(context).listFiles()?.size ?: 0

    private fun latest(context: Context): File? = dir(context).listFiles()?.maxByOrNull { it.name }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** 用系統分享面板送出最新一份報告（使用者自己決定要傳給誰）。 */
    fun shareLatest(context: Context) {
        val text = latest(context)?.readText() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
