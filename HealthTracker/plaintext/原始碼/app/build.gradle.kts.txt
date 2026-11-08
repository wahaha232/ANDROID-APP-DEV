import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// 雲端設定放在不進版控的 local.properties：supabase.url=https://xxxx.supabase.co、supabase.anonKey=...
// （anon key 本來就是給用戶端用的公開金鑰，資料安全靠 RLS；但仍不進版控。絕不要放 service_role 金鑰。）
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
// 版號自動進版：versionCode 以 git commit 總數（加 gradle.properties 的 ht.versionBase 補差）為序號，
// 每次提交都會遞增；工作目錄有未提交的修改時 versionName 尾端加「+」，一眼看出這個 APK 含未提交的修改。
// 搬到新 repo 時（commit 數歸零），把 ht.versionBase 設成「舊最終版號 − 新 repo 的 commit 數」，版號才不會倒退。
fun gitOutput(vararg args: String): String? = runCatching {
    providers.exec {
        workingDir = rootProject.projectDir
        commandLine("git", *args)
    }.standardOutput.asText.get().trim()
}.getOrNull()

val versionBase = (findProperty("ht.versionBase") as String?)?.toIntOrNull() ?: 0
val gitCommitCount: Int = (gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0) + versionBase
val gitDirty: Boolean = !gitOutput("status", "--porcelain", "--", ".").isNullOrBlank()

fun localProp(name: String) = localProps.getProperty(name, "").trim().replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.startinsnow.healthtracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.startinsnow.healthtracker"
        minSdk = 26
        targetSdk = 35
        versionCode = gitCommitCount.coerceAtLeast(1)
        versionName = "1.0.$gitCommitCount" + if (gitDirty) "+" else ""

        buildConfigField("String", "SUPABASE_URL", "\"${localProp("supabase.url")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localProp("supabase.anonKey")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 健康資料屬敏感個資：不允許雲端自動備份到使用者 Google 帳號以外的位置（見 AndroidManifest）。
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Room schema 匯出：日後寫 Migration 需要。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 本機優先資料庫
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 個人設定
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 裝置端 OCR（內建模型，不需 Google Play 服務、照片不上傳）
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // 體重／體脂來源：Health Connect（版本同 GPSTracker；1.1.0 需要 compileSdk 36 + AGP 8.9.1）
    implementation("androidx.health.connect:connect-client:1.1.0-alpha10")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // 雲端同步：Supabase REST（不用 SDK，只靠 OkHttp + JSON）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
