// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/settings/AppInfoActions.kt
package com.wahaha232.weatherforecast.presentation.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 側邊選單「App 資訊」區塊的實際行為：評價我／反饋意見／分享／隱私政策／版本號。
 * 全部透過標準 Android Intent 交給使用者確認後的系統元件處理（Play商店、Email、分享清單），
 * 不在背景自動送出任何內容。
 */
object AppInfoActions {

    fun openPlayStoreListing(context: Context) {
        val packageName = context.packageName
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: ActivityNotFoundException) {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }

    fun sendFeedbackEmail(context: Context) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf("support@example.com"))
            putExtra(Intent.EXTRA_SUBJECT, "天氣預報 App 意見回饋 (v${appVersionName(context)})")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "裝置上找不到可用的郵件應用程式", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareApp(context: Context) {
        val packageName = context.packageName
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "我在用「天氣預報」App，推薦給你！\nhttps://play.google.com/store/apps/details?id=$packageName"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "分享給朋友").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * 隱私政策目前以應用內建靜態文字呈現（見 PrivacyPolicyText），
     * 因為本專案沒有實際對外託管的隱私政策網址。正式上架前請將內容替換為
     * 已審閱過、並託管在你自己網域上的正式隱私權政策，並改為開啟該網址。
     */
    fun openPrivacyPolicy(context: Context) {
        Toast.makeText(context, "隱私政策內容請見「隱私政策」頁面（App 內建文字）", Toast.LENGTH_LONG).show()
    }

    fun appVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    } catch (e: Exception) {
        "1.0.0"
    }
}
