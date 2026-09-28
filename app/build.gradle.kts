import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

/**
 * Loads `<project-root>/.env` at config-time and exposes its contents to
 * the app as `BuildConfig.<KEY>` fields. This is the standard Android idiom
 * for "secret stays out of source control but is bundled into the APK at
 * build time" (`.env` is git-ignored).
 *
 * Currently injects:
 *   - GROQ_API_KEY  : Bearer token for the Groq Chat Completions API
 *   - GROQ_MODEL    : Model id (defaults to llama-3.1-8b-instant)
 *
 * Falls back to empty strings if `.env` is missing — the app then runs in
 * "AI disabled" mode and the dashboard shows a friendly notice instead of
 * crashing.
 */
val envProps = Properties()
val envFile = rootProject.file(".env")
if (envFile.exists()) {
    FileInputStream(envFile).use { envProps.load(it) }
}
fun envOrEmpty(key: String, default: String = ""): String {
    val v = (envProps.getProperty(key) ?: "").trim()
    return v.ifEmpty { default }
}

android {
    namespace = "com.riad.bio_marker"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.riad.bio_marker"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // --- Secrets piped from .env at the project root ---
        buildConfigField(
            "String", "GROQ_API_KEY",
            "\"" + envOrEmpty("GROQ_API_KEY") + "\""
        )
        buildConfigField(
            "String", "GROQ_MODEL",
            "\"" + envOrEmpty("GROQ_MODEL", "llama-3.3-70b-versatile") + "\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    androidResources {
        // Don't compress these so they can be memory-mapped from the APK at
        // runtime (TFLite needs an uncompressed asset for `openFd`/mmap).
        noCompress += listOf("tflite", "json")
    }
    packaging {
        // Required for 16 KB page-size compatibility on Android 15+:
        // ship native libs uncompressed and page-aligned in the APK.
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tensorflow.lite)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // On-device translator (Bangla ↔ English) and language detector for the
    // in-app "🌐 Translate" buttons. Both ship as downloadable model files
    // (~30 MB per direction) so they work fully offline after first use.
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}