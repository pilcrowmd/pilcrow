// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

// ---------------------------------------------------------------------------
// Mutation testing (PIT) — applied ONLY when `-Pmutation` is set (see app/build.gradle.kts), so the
// normal gate never sees this file, its configuration or its task.
//
// Run:
//   ./gradlew :app:pitestDebug -Pmutation \
//       -PpitTargetClasses='com.pilcrowmd.viewmodel.DocumentSlot*' \
//       -PpitTargetTests='com.pilcrowmd.viewmodel.DocumentSlotTest'
//
// Optional: -PpitReportName=<dir under app/build/reports/pitest/> -PpitThreads=<n>
//           -PpitMutators=<PIT mutator group or list, default STRONGER>
//           -PpitExcludedMethods=<method glob[,glob]> (to focus on some methods of a large class)
//           -PpitExcludedTests=<test class glob[,glob]> -PpitVerbosity=VERBOSE
//
// WHY THE COMMAND-LINE TOOL AND NOT A GRADLE PLUGIN: the PIT Gradle plugins either do not support
// Android at all (info.solidsoft.pitest) or hook AGP internals that move between AGP versions. PIT's
// own command-line entry point only needs a classpath, and the Robolectric-ready classpath already
// exists: it is exactly the one AGP hands `testDebugUnitTest` (compiled classes, R.jar, merged
// resources and the generated test_config.properties Robolectric reads). So the task below borrows
// that classpath and nothing else.
// ---------------------------------------------------------------------------

val pitVersion = "1.30.0"

val pitest: Configuration = configurations.create("pitest")
dependencies.add("pitest", "org.pitest:pitest-command-line:$pitVersion")

fun prop(name: String): String? = providers.gradleProperty(name).orNull

tasks.register("pitestDebug", JavaExec::class.java) {
    group = "verification"
    description = "PIT mutation testing against the debug unit-test classpath (requires -Pmutation)."

    val unitTest = tasks.named("testDebugUnitTest", Test::class.java)
    // Build everything the unit tests need, WITHOUT running them: `provider {}` hands Gradle the
    // file collections' own build dependencies. (`unitTest.map {}` would also carry the test task
    // itself as a producer, so every mutation run would first run the whole unit suite.)
    dependsOn(provider { unitTest.get().classpath }, provider { unitTest.get().testClassesDirs })

    classpath = pitest
    mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")

    val targetClasses = prop("pitTargetClasses")
    val targetTests = prop("pitTargetTests")
    val reportName = prop("pitReportName") ?: "default"
    val threads = prop("pitThreads") ?: "4"
    val mutators = prop("pitMutators") ?: "STRONGER"
    val excludedMethods = prop("pitExcludedMethods")
    val excludedTests = prop("pitExcludedTests")
    val reportDir = layout.buildDirectory.dir("reports/pitest/$reportName")

    doFirst {
        requireNotNull(targetClasses) { "-PpitTargetClasses=<glob[,glob]> is required" }
        requireNotNull(targetTests) { "-PpitTargetTests=<glob[,glob]> is required" }
        // AGP hands the unit tests the app's classes as a JAR (runtime_app_classes_jar). PIT only
        // mutates classes found in DIRECTORIES — it treats jars as libraries — so the jar is swapped
        // for the class directories it was bundled from, and the Kotlin one is named mutable.
        val kotlinClasses = layout.buildDirectory.dir("tmp/kotlin-classes/debug").get().asFile
        val javacClasses = layout.buildDirectory
            .dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes").get().asFile
        val cpFiles = unitTest.get().classpath.files.filter { it.exists() }.flatMap {
            if (it.path.contains("runtime_app_classes_jar")) listOf(kotlinClasses, javacClasses) else listOf(it)
        }.filter { it.exists() }
        check(kotlinClasses in cpFiles) { "app classes not on the PIT classpath: $kotlinClasses" }
        val cp = cpFiles.joinToString(",") { it.absolutePath }
        args(
            "--classPath", cp,
            "--useClasspathJar", "true",
            "--mutableCodePaths", kotlinClasses.absolutePath,
            "--sourceDirs", file("src/main/java").absolutePath,
            "--reportDir", reportDir.get().asFile.absolutePath,
            "--targetClasses", targetClasses,
            "--targetTests", targetTests,
            "--mutators", mutators,
            "--threads", threads,
            "--outputFormats", "XML,HTML,CSV",
            "--timestampedReports", "false",
            "--exportLineCoverage", "true",
            // Robolectric pays several seconds of sandbox start-up in the first test of a minion.
            "--timeoutConst", "20000",
            // The minions get the unit-test JVM's own arguments. Without AGP's
            // --add-opens=java.base/java.io, Robolectric cannot open a ParcelFileDescriptor and
            // every save test fails before a single mutant is tried.
            "--jvmArgs", (unitTest.get().allJvmArgs + listOf("-Xmx2g", "-XX:+UseParallelGC")).joinToString(","),
            // Lines calling these are not mutated. NOT kotlin.jvm.internal: Kotlin compiles `==` to
            // Intrinsics.areEqual, so avoiding it silently drops every line with an equality check.
            "--avoidCallsTo", "android.util.Log,java.util.logging,org.slf4j",
            "--verbosity", prop("pitVerbosity") ?: "DEFAULT",
        )
        if (excludedMethods != null) args("--excludedMethods", excludedMethods)
        if (excludedTests != null) args("--excludedTestClasses", excludedTests)
    }
}
