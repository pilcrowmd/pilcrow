plugins {
    id("com.android.application") version "8.7.3" apply false
    kotlin("android") version "2.3.10" apply false
    kotlin("plugin.compose") version "2.3.10" apply false
    // Static analysis & formatting (Quality Gate: ./gradlew ktlintCheck detekt)
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    // Visual-regression (golden-image) screenshot testing — Robolectric-backed.
    id("io.github.takahirom.roborazzi") version "1.63.0" apply false
    // License inventory/reporting (third-party dependency licenses for distribution review).
    id("com.jaredsburrows.license") version "0.9.8" apply false
}
