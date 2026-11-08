package com.startinsnow.healthtracker.mijia

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build

/**
 * 以「浮動視窗」開啟米家：指定較小的啟動範圍，支援自由視窗（freeform）的系統就會用小視窗開啟；
 * 不支援時系統會忽略範圍，仍以正常方式開啟米家（無障礙讀取不受影響）。
 */
object MijiaLauncher {
    fun openFloating(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(MijiaAccessibilityService.MIJIA_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dm = context.resources.displayMetrics
            val w = (dm.widthPixels * 0.66f).toInt().coerceAtLeast(320)
            val h = (dm.heightPixels * 0.72f).toInt().coerceAtLeast(480)
            val left = ((dm.widthPixels - w) / 2).coerceAtLeast(0)
            val top = (dm.heightPixels * 0.10f).toInt()
            val bounds = Rect(left, top, left + w, top + h)
            val options = ActivityOptions.makeBasic().apply { setLaunchBounds(bounds) }
            runCatching { context.startActivity(intent, options.toBundle()) }.onSuccess { return true }
        }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
}
