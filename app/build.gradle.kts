import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

val downnetVersionCode = rootProject.extra["downnetVersionCode"] as Int
val downnetVersionName = rootProject.extra["downnetVersionName"] as String

// Release signing (BitChord pattern): keystore.properties at the repo root,
// gitignored, store file beside it; CI injects both from secrets. Absent ->
// unsigned release APK instead of a failed build.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use(::load)
}
val hasKeystore = keystoreProps.getProperty("storeFile") != null

// Release builds ship a single arm64 APK (Chaquopy's Python 3.12 runtimes are
// 64-bit only; 32-bit has been dead since 2019). Debug keeps x86_64 for the
// emulator. Pass -Pdownnet.armOnly=true to force arm64 everywhere.
val armOnly = providers.gradleProperty("downnet.armOnly").getOrElse("false").toBoolean()

android {
    namespace = "com.downnet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.downnet"
        minSdk = 29
        targetSdk = 36
        versionCode = downnetVersionCode
        versionName = downnetVersionName

        ndk {
            abiFilters += if (armOnly) listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 minification + rules land with the hardening milestone.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // Extract native libs at install time so nativeLibraryDir contains
            // executable files — ffmpeg/ffprobe/qjs are exec()'d, not dlopen'd,
            // and AGP's default (load straight from the APK) leaves nothing on
            // disk to exec.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin 2.3 removed the android.kotlinOptions DSL in favor of this.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        // Must match the app's Python major.minor; CI provides it via
        // actions/setup-python.
        buildPython("python3")
        pip {
            // yt-dlp's pure-Python deps resolve from PyPI; brotli and
            // pycryptodomex come from Chaquopy's Android wheel repo.
            // curl_cffi has no Android wheels — it's optional for yt-dlp and
            // simply absent.
            install("yt-dlp[default]==2026.11.23")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.13.0")
    // androidx.core 1.19+ merged core-ktx into the main artifact.
    implementation("androidx.core:core:1.19.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
