import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("kapt")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    id("io.github.takahirom.roborazzi")
    id("com.jaredsburrows.license")
}

// Release signing secrets — sourced from local.properties (gitignored) or the
// environment (CI). Never hardcoded, never committed. With no keystore configured
// the release type stays unsigned, so debug builds and CI are unaffected; only
// bundleRelease/assembleRelease need it.
val releaseSigning = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) FileInputStream(propsFile).use { load(it) }
}
fun releaseSecret(key: String): String? =
    releaseSigning.getProperty(key) ?: System.getenv(key)
val hasReleaseKeystore = releaseSecret("RELEASE_STORE_FILE") != null

android {
    namespace = "com.pilcrowmd"
    compileSdk = 36
    // Pinned so release native-symbol extraction (debugSymbolLevel below) is reproducible.
    // r27c LTS — preinstalled on GitHub ubuntu runners; AGP auto-installs it elsewhere.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.pilcrowmd"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(releaseSecret("RELEASE_STORE_FILE")!!)
                storePassword = releaseSecret("RELEASE_STORE_PASSWORD")
                keyAlias = releaseSecret("RELEASE_KEY_ALIAS")
                keyPassword = releaseSecret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 runs in NO-OP mode (proguard-rules.pro: -dontshrink -dontobfuscate -dontoptimize):
            // code is byte-identical to minify-off, but R8 emits the mapping file into the AAB's
            // BUNDLE-METADATA so Play Vitals stack traces stay symbolicated and the Console
            // "no deobfuscation file" warning goes away. Real shrinking is a deliberate,
            // separately-UAT'd future change — do not flip these flags casually.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Package native symbol tables for the AndroidX .so libs into the AAB so Play can
            // symbolicate native crashes (Console warning on the 1.0.1 AAB). Needs a local NDK.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            // Do not embed the git revision (META-INF/version-control-info.textproto), so
            // the APK content is independent of the checkout state — required for
            // reproducible builds. (Per-build-type DSL on this AGP; release is the only
            // variant that embeds it.)
            vcsInfo {
                include = false
            }
            // Upload key (Play App Signing re-signs with the app key). Null when no
            // keystore is configured, so non-release builds never require it.
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the debug-only crash-injection hook (impossible in release).
        buildConfig = true
    }

    // Strip the Google-encrypted dependency-metadata signing block from release artifacts.
    // F-Droid rejects APKs that carry it (opaque, only Google can read it), and Play does
    // not require it for uploads.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        // Robolectric + Roborazzi need real Android resources/assets on the unit-test classpath
        // (the renderer inflates real TextViews/RecyclerView and bundled OFL fonts).
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Gated Compose compiler metrics/reports (H4 recomposition profiling).
// Inert on normal builds; enable with `-PcomposeMetrics=true` to emit stability,
// skippability, and restartability reports under app/build/compose_{metrics,reports}.
composeCompiler {
    if (project.findProperty("composeMetrics") == "true") {
        metricsDestination = layout.buildDirectory.dir("compose_metrics")
        reportsDestination = layout.buildDirectory.dir("compose_reports")
    }
}

dependencies {
    // Compose BOM (pulls all Compose, Lifecycle, Activity, etc.)
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity + Lifecycle
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")

    // Branded launch splash (AndroidX core SplashScreen API; back-ports the API 31 splash to API 26+).
    implementation("androidx.core:core-splashscreen:1.0.1")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Markwon + plugins (full §3 plugin set)
    implementation("io.noties.markwon:core:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation("io.noties.markwon:ext-tables:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation("io.noties.markwon:ext-strikethrough:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation("io.noties.markwon:ext-tasklist:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation("io.noties.markwon:linkify:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation("io.noties.markwon:syntax-highlight:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation("io.noties.markwon:html:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation("io.noties.markwon:recycler:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    // LaTeX math rendering: native via JLatexMath, no WebView (§3)
    implementation("io.noties.markwon:ext-latex:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    // RecyclerView host for block-level rendering (referenced directly in PreviewRecycler)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Prism4j for per-language syntax highlighting
    implementation("io.noties:prism4j:2.0.0") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    kapt("io.noties:prism4j-bundler:2.0.0") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }

    // NOTE: do NOT add org.commonmark:* here — Markwon 4.6.2 bundles
    // com.atlassian.commonmark:0.13.0 (package org.commonmark.*) transitively.
    // Adding the newer org.commonmark artifact puts two conflicting commonmark
    // libraries on the classpath (it broke the recycler build).

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.0")

    // Kotlin stdlib
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.10")

    // Memory-leak detection (debug-only; auto-installs via ContentProvider, no app code).
    // Guards the editor leak-risk surface: hoisted EditText holding an Activity
    // Context, AndroidView mount/unmount, and the config-change concern.
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    // Sora Editor (native code editor library, LGPL-2.1)
    // BOM + core editor + TextMate language support for syntax highlighting
    implementation(platform("io.github.rosemoe:editor-bom:0.24.5"))
    implementation("io.github.rosemoe:editor")
    implementation("io.github.rosemoe:language-textmate")

    // Coil — async image loading for the opt-in Mermaid cloud renderer.
    // Used only when the user enables "Render Mermaid via cloud"; loads the PNG
    // returned by mermaid.ink into a native ImageView. Apache-2.0.
    implementation("io.coil-kt:coil:2.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test:rules:1.5.0")
    // Robolectric 4.16.x: improved GraphicsMode.NATIVE rendering for Compose (req. by Roborazzi).
    testImplementation("org.robolectric:robolectric:4.16.1")

    // Mocking for unit tests (atomic-save test failure injection).
    testImplementation("io.mockk:mockk:1.13.11")

    // Coroutines test utilities (MarkdownViewModel line-ending test dispatcher).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.0")

    // Visual-regression screenshot testing (golden images) — runs on Robolectric, no device.
    // Record:  ./gradlew recordRoborazziDebug
    // Verify:  ./gradlew verifyRoborazziDebug   (Quality Gate)
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.63.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.63.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ---------------------------------------------------------------------------
// Static analysis & formatting — `./gradlew ktlintCheck detekt`
// ktlint owns formatting; detekt owns code smells / complexity. Compose-specific
// exemptions live in .editorconfig (ktlint) and config/detekt/detekt.yml (detekt).
// ---------------------------------------------------------------------------

ktlint {
    android.set(true)
    ignoreFailures.set(false)
    filter {
        // Never lint generated sources (Prism4j bundler, build outputs).
        exclude { it.file.path.contains("${layout.buildDirectory.get()}") }
        exclude { it.file.path.contains("/build/") }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
    parallel = true
    // Analyse the main + test Kotlin sources only; generated code stays out.
    source.setFrom(
        files(
            "src/main/java",
            "src/test/java",
            "src/androidTest/java",
        ),
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
    exclude("**/build/**")
}
tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "21"
    exclude("**/build/**")
}

// ---------------------------------------------------------------------------
// License inventory — `./gradlew :app:licenseReleaseReport`
// Tooling for distribution license review only. Reports land in
// app/build/reports/licenses/. JSON is copied into assets (bundled in APK);
// HTML report is explicitly kept out (copyHtmlReportToAssets = false).
// Task dependencies (mergeDebugAssets → licenseDebugReport, etc.) ensure
// JSON is copied BEFORE assets merge, guaranteeing it's bundled.
// ---------------------------------------------------------------------------
licenseReport {
    generateCsvReport = true
    generateJsonReport = true
    generateHtmlReport = false
    copyHtmlReportToAssets = false
    copyJsonReportToAssets = true
}

// 09.1 — Ensure license JSON is copied to assets BEFORE assets merge runs.
// This guarantees the JSON is bundled into both debug and release APKs.
tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
    dependsOn("licenseDebugReport")
}
tasks.matching { it.name == "mergeReleaseAssets" }.configureEach {
    dependsOn("licenseReleaseReport")
}

// ---------------------------------------------------------------------------
// Marketing screenshots — `./gradlew :app:marketingScreenshots -Dpilcrow.marketing=true`
//
// Captures Play Store assets into the repo-level screenshots/marketing/ directory (an ASSET dir,
// NOT a Roborazzi golden). GATE-NEUTRAL by construction: MarketingScreenshotTest cases self-skip
// (assumeTrue on `pilcrow.marketing`) under the normal gate, so verifyRoborazziDebug / CI never run
// or verify them and the 96 regression goldens are untouched. This block only reconfigures the
// unit-test task when marketing mode is explicitly requested — flipping Roborazzi into record
// (write) mode and FILTERING to the *Marketing* classes so the golden suite never re-records.
// ---------------------------------------------------------------------------
val marketingRequested = providers.systemProperty("pilcrow.marketing").orNull == "true"
if (marketingRequested) {
    // configureEach is lazy — applies once AGP creates the unit-test task (named<> would race it).
    tasks.withType<Test>().configureEach {
        if (name == "testDebugUnitTest") {
            systemProperty("pilcrow.marketing", "true")
            systemProperty("roborazzi.test.record", "true")
            filter { includeTestsMatching("*Marketing*") }
        }
    }
}
tasks.register("marketingScreenshots") {
    group = "verification"
    description = "Capture Play Store marketing screenshots into screenshots/marketing/ " +
        "(run with -Dpilcrow.marketing=true; excluded from the CI gate)."
    // Fail fast rather than silently triggering a full (golden-overwriting) unit-test run.
    doFirst {
        require(marketingRequested) {
            "Run with -Dpilcrow.marketing=true, e.g. ./gradlew :app:marketingScreenshots -Dpilcrow.marketing=true"
        }
    }
    if (marketingRequested) dependsOn("testDebugUnitTest")
}
