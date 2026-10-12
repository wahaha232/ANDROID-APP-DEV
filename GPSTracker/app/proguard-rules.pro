# GPS TRACKER R8 / ProGuard 規則（release 啟用 minify）
# 各函式庫（Room、WorkManager、MapLibre、CameraX、OkHttp、Compose、kotlinx.serialization、Health Connect）
# 都隨 AAR 附帶 consumer rules，這裡只補 App 自己需要的部分。

# 保留行號，讓 crash 堆疊仍可還原；原始檔名換成固定字串。
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# Room：Entity / DAO / TypeConverter 的欄位與類別名稱對應資料庫結構，保持不被改名。
-keep class com.startinsnow.gpstracker.data.db.** { *; }

# WorkManager 依類別名稱以反射建立 Worker（需要 (Context, WorkerParameters) 建構子）。
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# OkHttp 的選用安全提供者在 Android 上不存在，屬正常情況。
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
