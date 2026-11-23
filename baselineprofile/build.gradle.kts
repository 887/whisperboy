import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// cold-start-perf F.1 / F.2 — sibling test module that records the
// cold-boot path of `:app` via MacrobenchmarkRule + the AndroidX
// BaselineProfile plugin. Produces app/src/main/baseline-prof.txt,
// which `:app` consumes via the `baselineProfile(project(":baselineprofile"))`
// dependency in app/build.gradle.kts.
//
// Required device: an attached AVD or rooted physical device.
// `:app:generateBaselineProfile` will fail in headless CI without one;
// scripts/build-release-apk.sh accepts --skip-baseline to skip generation
// and reuse the last-committed baseline-prof.txt.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.eight87.whisperboy.baselineprofile"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    targetProjectPath = ":app"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

baselineProfile {
    // Generate against any connected device/AVD. Managed devices (Gradle
    // Managed Devices) would be the alternative — declined for now because
    // the user runs the release script against the same headless AVD that
    // hosts the UI smoke tests, and spinning a second managed AVD just to
    // record a profile doubles the wall-clock.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.espresso.core)
}
