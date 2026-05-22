import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
  // cold-start-perf F.1 — apply the Baseline Profile plugin so the
  // `:app:generateBaselineProfile` task is wired and the consumed profile
  // is packaged into the release APK. The sibling `:baselineprofile`
  // module produces the rules; this side consumes them.
  alias(libs.plugins.androidx.baselineprofile)
  // oss-licenses A.2 — Licensee plugin. Generates per-variant `artifacts.json`
  // under `app/build/reports/licensee/android<Variant>/`. The Copy task wired
  // below stages that JSON into `src/main/assets/licenses/` so LicensesScreen
  // can read it via AssetManager at runtime.
  alias(libs.plugins.licensee)
}

// Mirror of tonearmboy's About-screen build-metadata capture. `git rev-parse`
// runs at configuration time so the result is baked into BuildConfig; the date
// is captured at the same time. Both fall back to sentinels when run from a
// tarball without git history.
val gitShortSha: String = runCatching {
  val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
    .redirectErrorStream(true)
    .start()
  proc.waitFor()
  proc.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
}.getOrDefault("unknown")

val buildDateUtc: String = DateTimeFormatter.ISO_LOCAL_DATE
  .format(LocalDate.now(ZoneOffset.UTC))

android {
    namespace = "com.eight87.whisperboy"
    compileSdk = 36
    base {
        archivesName.set("whisperboy")
    }
    defaultConfig {
        applicationId = "com.eight87.whisperboy"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GIT_SHA", "\"$gitShortSha\"")
        buildConfigField("String", "BUILD_DATE", "\"$buildDateUtc\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    // Robolectric — JVM-only Android-shadow runner for unit tests.
    //   * `isIncludeAndroidResources = true` packages merged resources +
    //     `AndroidManifest.xml` + the `assets/` tree onto the JVM test
    //     classpath, which is what makes `LocalContext.current.assets.open(...)`
    //     resolve under Robolectric (the LicensesScreen's `assets/licenses/`
    //     inventory + future cover-art asset reads land via this).
    //   * `usePreciseLog` keeps Robolectric's "ran shadow X at line Y" trace
    //     on so test failures are diagnosable without re-running.
    // Test classes pick the SDK level with `@Config(sdk = [34])`.
    testOptions {
      unitTests.isIncludeAndroidResources = true
      unitTests.all {
        it.systemProperty("robolectric.usePreciseLog", "true")
      }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// oss-licenses A.3 — declare allowed SPDX ids for shipped deps. v1 ships
// reporting-only (no failOnDisallowed); the catalog test in app/src/test
// is what fails loud if an unrecognised SPDX sneaks in. EPL-1.0 (junit) is
// test-scope only, so it never enters the resolved release classpath that
// Licensee inspects.
licensee {
    allow("Apache-2.0")
    allow("MIT")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
}

// oss-licenses A.4 — copy the per-variant Licensee `artifacts.json` into
// `src/main/assets/licenses/` so LicensesScreen can read it via
// AssetManager at runtime. Wired as a dependency of the variant's
// mergeAssets task so the asset is always self-consistent with the
// build's resolved classpath.
val licenseeReportDir = layout.buildDirectory.dir("reports/licensee")
val licensesAssetDir = layout.projectDirectory.dir("src/main/assets/licenses")

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val copyTask = tasks.register<Copy>("copyLicensesAssetFor$variantName") {
            dependsOn("licenseeAndroid$variantName")
            from(licenseeReportDir.map { it.dir("android$variantName") }) {
                include("artifacts.json")
            }
            into(licensesAssetDir)
        }
        afterEvaluate {
            tasks.named("merge${variantName}Assets").configure {
                dependsOn(copyTask)
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.process)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // material-icons-extended — needed for non-core glyphs (MenuBook, GridView, ViewList, MoreVert,
  // Folder, etc.) used across the library / settings / player chrome. Not transitive in material3
  // 1.4.0+ (m3-expressive.md gotcha #2).
  implementation(libs.androidx.compose.material.icons.extended)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Robolectric — JVM-side Android shadows + AndroidX test runner shims. Lets
  // any `Context`/`AssetManager`/`Resources`/`SharedPreferences`-dependent test
  // (and the Compose `createComposeRule()` harness) run under
  // `:app:testDebugUnitTest` without an emulator. Compose UI tests reuse the
  // already-versioned `androidx.compose.ui:ui-test-junit4` from the BOM plus
  // `ui-test-manifest` for the host harness.
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core.ktx)
  testImplementation(libs.androidx.test.ext.junit.ktx)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.compose.ui.test.manifest)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Media3 — ExoPlayer + MediaSession + MediaLibraryService for Android Auto (Phase N).
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.media3.ui)
  // Phase N — `kotlinx.coroutines.guava.future { ... }` so the MediaLibrarySession callback
  // can express its async lookups as suspend functions and return them as the
  // `ListenableFuture<LibraryResult<...>>` Media3 expects.
  implementation(libs.kotlinx.coroutines.guava)

  // SAF — DocumentFile wrapper for the picked-tree URIs (Phase C wraps this in CachedDocumentFile).
  implementation(libs.androidx.documentfile)

  // Palette — extract a dominant swatch from the cover bitmap for the F.6 player background
  // gradient. Apache-2.0, decode happens off-main (Dispatchers.IO in PaletteTint.kt).
  implementation(libs.androidx.palette)

  // Coil — cover-art tile loading (cover-art.md Phase A.5). Local files only; no
  // coil-network-okhttp dep — network image search is cover-art.md Phase B work.
  implementation(libs.coil.compose)
  // cover-art Phase B — Coil's OkHttp network fetcher, so the staggered-grid thumbnails
  // (HTTP URLs from DuckDuckGo) load via Coil. Without this fetcher Coil-3 fails any
  // non-file `model`.
  implementation(libs.coil.network.okhttp)

  // DataStore Preferences — persisted (treeUri → FolderType) mapping in Phase C, settings in Phase K.
  implementation(libs.androidx.datastore.preferences)

  // Room — Phase D's library cache (BookEntity / ChapterEntity / BookmarkEntity).
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  testImplementation(libs.androidx.room.testing)

  // cover-art Phase B — user-initiated DuckDuckGo image search for per-book cover art.
  // Retrofit + OkHttp + kotlinx-serialization power the two-step `vqd` protocol (see
  // docs/plans/cover-art.md); androidx.paging threads the `next` cursor into
  // `LazyVerticalStaggeredGrid` via paging-compose's `LazyPagingItems`.
  implementation(libs.retrofit)
  implementation(libs.retrofit.converter.kotlinx.serialization)
  implementation(libs.retrofit.converter.scalars)
  implementation(libs.okhttp)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.paging.runtime)
  implementation(libs.androidx.paging.compose)

  // main.md Phase M — AndroidX Glance for the home-screen widget. RemoteViews
  // generation under the hood; we keep the composable-style API. The Material3
  // bridge gives us `GlanceTheme` which lifts colours from the host.
  implementation(libs.androidx.glance.appwidget)
  implementation(libs.androidx.glance.material3)

  // cold-start-perf F.3 — profileinstaller picks up the baseline-prof.txt
  // packaged into the APK and hands it to ART at app install time, which is
  // the mechanism that gives us the ~25–35% cold-start win.
  implementation(libs.androidx.profileinstaller)

  // cold-start-perf F.1 — declare the sibling test module as the producer
  // of the baseline profile this app consumes.
  "baselineProfile"(project(":baselineprofile"))
}
