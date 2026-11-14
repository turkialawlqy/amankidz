import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

// ضع بيانات Supabase في local.properties:
//   supabase.url=https://xxxx.supabase.co
//   supabase.anonKey=eyJ...
//   parent.siteUrl=kids.example.com   (يظهر على شاشة الربط)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(key: String) = (localProps.getProperty(key) ?: System.getenv(key.replace('.', '_').uppercase()) ?: "")

// مفتاح التوقيع الخاص فيك (يسويه make-keystore.bat ويضيف هذي الأسطر في local.properties):
//   release.storeFile=keystore/aman-release.jks
//   release.storePassword=...
//   release.keyAlias=aman
//   release.keyPassword=...
val releaseStore = prop("release.storeFile")

android {
    namespace = "com.turkialawlqy.aman"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.turkialawlqy.aman"
        minSdk = 24 // Android 7.0
        targetSdk = 35
        versionCode = 64
        versionName = "3.2.0"
        buildConfigField("String", "SUPABASE_URL", "\"${prop("supabase.url")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${prop("supabase.anonKey")}\"")
        buildConfigField("String", "PARENT_SITE_URL", "\"${prop("parent.siteUrl")}\"")
    }

    signingConfigs {
        create("release") {
            if (releaseStore.isNotBlank()) {
                storeFile = rootProject.file(releaseStore)
                storePassword = prop("release.storePassword")
                keyAlias = prop("release.keyAlias").ifBlank { "aman" }
                keyPassword = prop("release.keyPassword").ifBlank { prop("release.storePassword") }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // موقّع بمفتاحك الخاص (مو مفتاح التجربة) — عشان Play Protect ما يحذفه
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        encoding = "utf-8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf("META-INF/README.md", "META-INF/CHANGES", "META-INF/COPYRIGHT", "META-INF/INDEX.LIST")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)

    // استخراج يوتيوب بدون API ولا إعلانات
    implementation(libs.newpipe.extractor)
    implementation(libs.newpipe.nanojson)
    implementation(libs.okhttp)

    // المشغّل
    implementation(libs.exoplayer.core)
    implementation(libs.exoplayer.dash)
    implementation(libs.exoplayer.hls)
    implementation(libs.exoplayer.ui)

    // poToken عبر WebView (منقول من NewPipe)
    implementation(libs.androidx.webkit)
    implementation(libs.rxjava)
    implementation(libs.rxandroid)

    // Supabase (لوحة الأهل + التحديث اللحظي)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)

    // الواجهة
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
}

// بناء نسخة release بدون مفتاح خاص = خطأ واضح (بدل APK غير موقّع أو بمفتاح التجربة)
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { it.project == project && it.name.contains("Release") && (it.name.startsWith("assemble") || it.name.startsWith("bundle") || it.name.startsWith("package")) }
    if (wantsRelease) {
        val f = if (releaseStore.isBlank()) null else rootProject.file(releaseStore)
        if (f == null || !f.exists()) {
            throw GradleException("ما فيه مفتاح توقيع خاص. شغّل android-tv\\make-keystore.bat مرة وحدة (يسوي المفتاح ويضيفه في local.properties) وبعدها ابنِ من جديد.")
        }
    }
}
