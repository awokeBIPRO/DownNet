// Toolchain pins follow BitChord's proven combination (same machine class and
// CI): Gradle 8.11.1 wrapper + AGP 8.10.1 + Kotlin 2.3.20, plus the R8
// override below.
buildscript {
    repositories {
        google()
    }
    dependencies {
        // AGP 8.10.x debug-dexing miscompiles large Compose functions (register
        // allocation past v255) and cannot read Kotlin 2.3 @Metadata; both are
        // fixed in newer R8. Gradle conflict resolution picks the higher
        // version, so this classpath is the whole mechanism. Removable once
        // AGP itself bundles >= 8.11.32.
        classpath("com.android.tools:r8:8.13.23")
    }
}

// Single source of truth for the app version. The in-app updater, the release
// workflow and the update manifest all read from here — no "v" in the name;
// that prefix belongs to the git tag alone.
extra["downnetVersionCode"] = 1
extra["downnetVersionName"] = "0.1.0"

plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("com.chaquo.python") version "17.2.0" apply false
}
