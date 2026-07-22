// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.di

/**
 * App-level metadata abstraction.
 * Provides read-only access to app properties (version, etc.) from the system,
 * decoupling the UI layer from PackageManager calls.
 *
 * Rationale: Composables must be passive — no system API calls inside @Composable
 * or LaunchedEffect bodies. PackageManager.getPackageInfo lives here, called once at
 * AppContainer init time.
 */
interface AppInfo {
    /**
     * App version name (e.g., "1.0.0"), read from the APK manifest.
     * Safe to call multiple times; value is computed once and cached by the implementation.
     */
    val versionName: String
}

/**
 * Default implementation: reads versionName from PackageManager at construction time.
 * Built lazily by DefaultAppContainer, so the PackageManager call happens once per process.
 */
class DefaultAppInfo(private val context: android.content.Context) : AppInfo {
    // runCatching (matching the original MainScreen idiom) → graceful "" fallback if the
    // PackageManager read fails, without a generic try/catch (avoids new suppressed detekt debt).
    override val versionName: String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
}
