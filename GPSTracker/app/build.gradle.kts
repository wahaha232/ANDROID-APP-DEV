import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// git commit 總數（versionCode/versionName 序號來源）。CI 需 fetch-depth: 0 才能取得完整歷史。
// 資料夾不是 git repo（例如單獨複製出來的 GPSTracker）時，原本會回退成 0，建出版號 1000000 的 APK，
// 無法覆蓋安裝較新的版本。因此依序嘗試：gradle.properties 的 gpst.versionSourceDir 指向的 git 目錄 →
// 專案目錄本身；都取不到才回退為 0，並印出警告。
fun gitCommitCountOf(dir: File): Int? = runCatching {
    providers.exec {
        workingDir = dir
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}.getOrNull()

val gitCommitCount: Int = run {
    val configured = (findProperty("gpst.versionSourceDir") as String?)
        ?.let { rootProject.projectDir.resolve(it) }
        ?.takeIf { it.isDirectory }
    val count = configured?.let { gitCommitCountOf(it) } ?: gitCommitCountOf(rootProject.projectDir)
    if (count == null) {
        logger.warn("WARNING: 取不到 git commit 數，versionCode 回退為 1000000；這個 APK 無法覆蓋安裝較新的版本。")
    }
    count ?: 0
}

// 沒有簽署金鑰環境變數時，release 只會產出未簽名 APK（無法安裝）；明確警告，避免誤以為建置成功就能用。
gradle.taskGraph.whenReady {
    val buildsRelease = allTasks.any { it.name.startsWith("assemble") && it.name.endsWith("Release") ||
        it.name.startsWith("bundle") && it.name.endsWith("Release") }
    if (buildsRelease && System.getenv("GPST_KEYSTORE_PATH") == null) {
        logger.warn("WARNING: 未設定 GPST_KEYSTORE_PATH，release 產物沒有簽署（未簽名 APK 無法安裝）。")
    }
}

android {
    namespace = "com.startinsnow.gpstracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.startinsnow.gpstracker"
        minSdk = 26
        targetSdk = 35
        // 版號自動進版：以 git commit 數為序號，每次提交都會遞增，
        // 避免不同建置卻同版號而無法辨識或更新。git 不可用時回退為 0。
        versionCode = 1_000_000 + gitCommitCount
        versionName = "1.0.$gitCommitCount"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // 固定的 debug 簽章：雲端 CI 每次都是全新的機器，沒有金鑰就會產生隨機的 debug 簽章，
        // 導致新版 APK 無法覆蓋安裝舊版。CI 以 Secret 還原金鑰並設定 GPST_DEBUG_KEYSTORE_PATH；
        // 本機沒設定就維持 Android 預設的 debug 金鑰（~/.android/debug.keystore），行為不變。
        System.getenv("GPST_DEBUG_KEYSTORE_PATH")?.let { path ->
            getByName("debug") {
                storeFile = file(path)
                storePassword = System.getenv("GPST_DEBUG_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("GPST_DEBUG_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("GPST_DEBUG_KEY_PASSWORD") ?: "android"
            }
        }
        create("release") {
            val ksPath = System.getenv("GPST_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("GPST_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GPST_KEY_ALIAS")
                keyPassword = System.getenv("GPST_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 縮減與混淆：縮小 APK、增加逆向難度。規則見 proguard-rules.pro。
            // 不啟用 shrinkResources（Compose / MapLibre 資源以動態方式引用，風險大於收益）。
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("GPST_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            // RecordingScreen/HomeScreen 等畫面使用 Material3 的 TopAppBar / TopAppBarDefaults，
            // 這兩個 API 目前仍標記為 @ExperimentalMaterial3Api，需明確 opt-in 才能編譯。
            optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        }
    }

    // Room 遷移測試需要把匯出的 schema JSON 當成 androidTest 的 assets。
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// Room schema 匯出位置：exportSchema = true 時必須提供，否則 Room 無法產生 schema 檔（未來無法寫 Migration）。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    // ---------- Jetpack Compose & Material 3 ----------
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---------- Activity / Lifecycle / Navigation ----------
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // ---------- Coroutines ----------
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ---------- kotlinx.serialization (JSON export / Drive REST payloads) ----------
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ---------- Room (local-first database) ----------
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ---------- DataStore (Settings) ----------
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ---------- WorkManager (batch Drive sync, auto cleanup) ----------
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ---------- CameraX (START/NORMAL/END photo capture) ----------
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // CameraX 的 ProcessCameraProvider 回傳 com.google.common.util.concurrent.ListenableFuture。
    // 該類別由 guava 提供（已透過其他相依間接引入 runtime，但未在編譯期可見），
    // 因此明確加入 guava 讓編譯期也能解析（單一副本，不使用 listenablefuture 空殼版本避免重複類別）。
    implementation("com.google.guava:guava:33.4.0-android")

    // ---------- MapLibre (NO Google Maps) ----------
    implementation("org.maplibre.gl:android-sdk:11.6.1")

    // ---------- Google Sign-In (Drive OAuth only, never for the map/GPS engine) ----------
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // ---------- Health Connect（步數來源：Google Fit / 小米 Zepp Life，皆經 Health Connect 讀取） ----------
    // 1.1.0 需要 compileSdk 36 + AGP 8.9.1；本專案為 compileSdk 35 / AGP 8.6.1，故使用相容的 alpha10。
    implementation("androidx.health.connect:connect-client:1.1.0-alpha10")

    // ---------- OkHttp（Drive REST 上傳與網頁儀表板上傳；程式碼未使用 Retrofit / logging-interceptor，已移除） ----------
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ---------- Unit Test (pure JVM, no device required) ----------
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
