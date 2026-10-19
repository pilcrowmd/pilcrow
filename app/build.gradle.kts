import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.util.Properties
import javax.inject.Inject

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
        versionCode = 16
        versionName = "1.0.15"
        // M-174: the release-prep date, bumped in the same edit as versionName (docs/PUBLISHING.md
        // step 1). File content, never the build clock or git, so every builder produces the same
        // bytes (F-Droid). ReleasePrepDateTest fails if it is not later than the previous release.
        val releasePrepDate = "2026-10-19"
        LocalDate.parse(releasePrepDate) // a malformed date fails the build
        buildConfigField("String", "RELEASE_PREP_DATE", "\"$releasePrepDate\"")

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
        debug {
            // A test build installs beside the Play app (com.pilcrowmd) instead of replacing it, and
            // shows as "PilcrowMD debug" (src/debug/res). Release keeps the bare id. NEW-110.
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 shrinks, optimises and obfuscates release code. The keep rules it needs, and
            // why, are in proguard-rules.pro. The mapping file lands in the AAB's
            // BUNDLE-METADATA, so Play Vitals stack traces are deobfuscated automatically.
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

    // Markwon + plugins (full plugin set)
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
    // LaTeX math rendering: native via JLatexMath, no WebView
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

    // Coil: async image loading for a note's local pictures (M-93, MarkdownImageLoader). It loads
    // nothing from the network: the app has no INTERNET permission (M-70). Apache-2.0.
    implementation("io.coil-kt:coil:2.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
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

// ---------------------------------------------------------------------------
// Dispatchers.Main test isolation.
//
// `Dispatchers.setMain` is a PROCESS-GLOBAL. The ViewModel barrier suites certified
// in PR #59/#60 call it in @Before and resetMain() in @After. That is safe across
// Gradle forks - forks are separate JVMs - but NOT against concurrency WITHIN one
// JVM. A Compose Recomposer (createComposeRule / createAndroidComposeRule, which
// every Roborazzi screenshot suite uses) keeps using Dispatchers.Main for as long
// as it lives, so sharing a process with a resetMain() teardown throws:
//
//   IllegalStateException: Dispatchers.Main is used concurrently with setting it
//
// Flagged in review as the ONE configuration that would
// break setMain; it broke the build a day later. It is ORDER-DEPENDENT and lands
// on an UNRELATED suite in TEARDOWN, not in an assertion.
//
// THE SPLIT RULE, in one place:
//
//   *** @Category(MainDispatcherSuite::class) -> testMainDispatcherDebug, in its ***
//   *** own JVM. Everything else - including ALL Compose and screenshot tests -  ***
//   *** stays in testDebugUnitTest, which excludes that category.                ***
//
// WHY THE setMain SIDE AND NOT THE COMPOSE SIDE: isolating Compose was tried first
// and rejected on evidence. Roborazzi's verify/record tasks are bound to
// testDebugUnitTest, so moving the screenshot suites out left verifyRoborazziDebug
// running 393 unrelated tests and EXITING 0 WHILE VERIFYING NO GOLDENS - a silent
// hole. Isolating setMain moves 3 classes instead of 8, leaves Roborazzi untouched,
// and preserves the class names cited in the #59/#60 merge-gate record.
//
// Both tasks are in the canonical local gate AND in CI. A task in
// only one of those does not exist.
// ---------------------------------------------------------------------------
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    (this as Test).useJUnit {
        excludeCategories("com.pilcrowmd.testing.MainDispatcherSuite", "com.pilcrowmd.testing.LargeDocumentSuite")
    }
}

// Mutation testing (PIT). Inert unless `-Pmutation` is passed; see app/mutation.gradle.kts.
if (providers.gradleProperty("mutation").isPresent) apply(from = "mutation.gradle.kts")

// Offline Mermaid (M-70): mermaid.min.js fetched at build time, SHA-256 pinned, merged into assets.
// ---------------------------------------------------------------------------
// Offline Mermaid (M-70): the diagram compiler the app bundles, fetched AT BUILD TIME and pinned by
// hash (security lock 5). The script is never committed to the repository; this section is the
// only record of which one ships.
//
// What it does, per variant: download the npm tarball once into the Gradle user home (outside
// build/, so `./gradlew clean` does not force a re-download), check its SHA-256 against the pin
// below, and extract two files into a generated assets directory that AGP merges into the APK:
//
//   package/dist/mermaid.min.js  ->  assets/mermaid/mermaid.min.js
//   package/LICENSE              ->  assets/licenses/MIT-Mermaid.txt
//
// A file whose hash does not match is never used. With no verified copy in the cache and no
// network, the build fails with a message saying so.
//
// HOW TO BUMP: change MERMAID_VERSION and MERMAID_SHA256 together. Get the hash from the tarball
// itself (`curl -sL <url> | shasum -a 256`) and cross-check it against npm's published integrity
// (`npm view mermaid@<version> dist.integrity`, a base64 SHA-512). Then read the new tree's licence
// (and its bundled dependencies' notices in dist/) before shipping it: the app ships it verbatim.
// ---------------------------------------------------------------------------

val mermaidVersion = "11.17.2"
val mermaidUrl = "https://registry.npmjs.org/mermaid/-/mermaid-$mermaidVersion.tgz"

// SHA-256 of the tarball. npm's own integrity for the same file, for cross-checking:
// sha512-V6K3C8EBdEsPFZXSKMJe6ppQOENxuHARr9GvHX4hh47lAbhMRD9qf4oEK7LoaRQxULMa80/qt5gHO73aCleBBg==
val mermaidSha256 = "6ad2f42c3fc26bbf9e45cbb6d11898972573ea52b33a5f4ff51952899f950ffd"

@CacheableTask
abstract class FetchMermaidAssetsTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    /** The verified tarball, shared by every variant and every clean build. Not an output. */
    @get:Internal
    abstract val cachedTarball: RegularFileProperty

    @get:Internal
    abstract val offline: Property<Boolean>

    /** Set by AGP's `addGeneratedSourceDirectory`, which picks the directory under build/generated. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun fetch() {
        val tarball = cachedTarball.get().asFile
        if (!tarball.isFile || sha256Of(tarball) != sha256.get()) download(tarball)

        val renames = mapOf(
            "package/dist/mermaid.min.js" to RelativePath(true, "mermaid", "mermaid.min.js"),
            "package/LICENSE" to RelativePath(true, "licenses", "MIT-Mermaid.txt"),
        )
        fileSystem.sync {
            from(archives.tarTree(archives.gzip(tarball))) {
                include(renames.keys)
                eachFile { relativePath = renames.getValue(path) }
                includeEmptyDirs = false
            }
            into(outputDir)
        }
        renames.values.forEach { target ->
            val extracted = outputDir.file(target.pathString).get().asFile
            check(extracted.isFile && extracted.length() > 0) {
                "mermaid ${version.get()}: ${target.pathString} is missing from the tarball; " +
                    "the package layout changed, so update this section with the bump."
            }
        }
    }

    /** Downloads to a temporary file, and moves it into place only once its hash matches. */
    private fun download(target: File) {
        check(!offline.get()) {
            "mermaid ${version.get()}: no verified copy at $target and Gradle is --offline. " +
                "Run once with network access to fetch ${url.get()}."
        }
        target.parentFile.mkdirs()
        val partial = File.createTempFile(target.name, ".part", target.parentFile)
        try {
            val connection = URI(url.get()).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
        } catch (e: IOException) {
            partial.delete()
            throw GradleException(
                "mermaid ${version.get()}: could not download ${url.get()} and no verified copy is " +
                    "cached at $target. Connect to the network and build again. (${e.message})",
                e,
            )
        }
        val actual = sha256Of(partial)
        if (actual != sha256.get()) {
            partial.delete()
            throw GradleException(
                "mermaid ${version.get()}: SHA-256 MISMATCH for ${url.get()}\n" +
                    "  expected ${sha256.get()}\n  actual   $actual\n" +
                    "The download was discarded. Do not change the pin unless the version changed too.",
            )
        }
        Files.move(
            partial.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        logger.lifecycle("mermaid ${version.get()}: downloaded and verified ($actual)")
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 64 * 1024
    }
}

val mermaidCache = File(gradle.gradleUserHomeDir, "caches/pilcrow-mermaid/mermaid-$mermaidVersion.tgz")
val gradleOffline = gradle.startParameter.isOffline

// One task per variant: AGP gives each generated source directory its own location, so a single
// task shared by variants would have its output directory overwritten by the last variant wired.
androidComponents.onVariants { variant ->
    val taskName = "fetchMermaidAssets" + variant.name.replaceFirstChar { it.uppercase() }
    val fetch = tasks.register(taskName, FetchMermaidAssetsTask::class.java) {
        group = "build"
        description = "Fetches mermaid $mermaidVersion (SHA-256 pinned) into the ${variant.name} assets."
        version.set(mermaidVersion)
        url.set(mermaidUrl)
        sha256.set(mermaidSha256)
        cachedTarball.set(mermaidCache)
        offline.set(gradleOffline)
    }
    variant.sources.assets?.addGeneratedSourceDirectory(fetch, FetchMermaidAssetsTask::outputDir)
}

val testMainDispatcherDebug by tasks.registering(Test::class) {
    group = "verification"
    description =
        "Runs @Category(MainDispatcherSuite) classes in a JVM of their own, so a Dispatchers.setMain " +
            "suite never shares a process with a Compose Recomposer bound to Dispatchers.Main."
    val base = tasks.named<Test>("testDebugUnitTest")
    dependsOn(base)
    testClassesDirs = files({ base.get().testClassesDirs })
    classpath = files({ base.get().classpath })
    useJUnit { includeCategories("com.pilcrowmd.testing.MainDispatcherSuite") }
}

// A hung unit test must fail the gate, not hold it (or a machine) for ever: Gradle stops the test
// JVM at the timeout. A formula build that never finishes cannot be interrupted from inside the
// test, so this is the only thing that ends such a run. The gate's tasks take about 5 minutes on
// CI (`testDebugUnitTest`, 2026-09-28) and about 2 locally; 20 minutes is four times the CI figure,
// so a slow runner never trips it. Scoped by name: a long run that has its own limit, such as a
// nightly fuzz task, is not capped here.
//
// The heap (M-396): on Gradle's 512 MB default the suite held 509 MB after GC on main (measured
// 2026-10-10), so an `OutOfMemoryError` hit whichever large screenshot test the class order put
// last; PR #381 failed CI twice that way in a test it does not touch. 1 GB is twice the measured
// peak; the 3 GB in gradle.properties is the daemon's, not the test JVM's. `UnitTestHeapTest` fails
// if this is lost.
tasks.withType<Test>().matching {
    it.name in setOf("testDebugUnitTest", "testReleaseUnitTest", "testMainDispatcherDebug", "testLargeDocumentDebug")
}.configureEach {
    timeout.set(Duration.ofMinutes(20))
    maxHeapSize = "1g"
}

// ---------------------------------------------------------------------------
// M-179 memory tests, in a JVM with a phone-sized heap.
//
// *** @Category(LargeDocumentSuite::class) -> testLargeDocumentDebug. Excluded from ***
// *** testDebugUnitTest. One fresh JVM per test class.                           ***
//
// THE HEAP: 356 MB = the S24+'s growth limit (268,435,456 bytes, M-179) + about 100 MB that
// Robolectric and Compose hold before a test allocates anything (measured: 97 MB used after
// Robolectric start, 101 MB after composing the reader). So the headroom a test gets is roughly the
// phone's. It is NOT a device simulation: HotSpot's object layout differs from ART's (a parsed
// commonmark tree measured ~9 bytes per source char here), so a pass here is evidence, and the
// phone run in the design note is still the authority.
//
// In the canonical local gate AND in CI, like testMainDispatcherDebug.
// ---------------------------------------------------------------------------
val testLargeDocumentDebug by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs @Category(LargeDocumentSuite) classes (M-179) in JVMs with a phone-sized heap."
    val base = tasks.named<Test>("testDebugUnitTest")
    dependsOn(base)
    testClassesDirs = files({ base.get().testClassesDirs })
    classpath = files({ base.get().classpath })
    useJUnit { includeCategories("com.pilcrowmd.testing.LargeDocumentSuite") }
    maxHeapSize = "356m"
    forkEvery = 1
}
