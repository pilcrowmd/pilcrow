// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pilcrowmd.ui.screen.MainScreen
import com.pilcrowmd.viewmodel.MarkdownViewModel

/** Bounded minimum the branded splash stays up so its lines→¶ morph plays. */
private const val SPLASH_MIN_DISPLAY_MS = 900L

/**
 * Pilcrow main activity.
 * Single-activity Compose app. No Hilt — manual ViewModel construction (simplicity-first).
 *
 * Dependency injection: contentResolver → LocalFileRepository → MarkdownViewModel, LocalStorageManager
 * Essential safeguards and hard constraints:
 * - No data loss: LocalFileRepository uses atomic write via sync()
 * - Content fidelity: UTF-8 round-trip without normalization
 * - Render never crashes: MarkwonRenderer handles unsupported syntax gracefully
 * - Design fidelity: All colors from token layer, no hardcoded hex
 */
class MainActivity : ComponentActivity() {
    // The most recent intent-provided file URI, held as Compose *state* so the app
    // reacts both at launch (cold) and while already running (warm). The earlier version stored
    // a plain field read once by a LaunchedEffect(Unit), so a file opened while running never
    // loaded. The composable observes this state and loads on each change.
    private val _intentFileUri = mutableStateOf<Uri?>(null)
    val intentFileUri: State<Uri?> get() = _intentFileUri

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the system splash before super.onCreate so the OS draws the branded launch frame
        // (Theme.Pilcrow.Splash) and then hands back to Theme.Pilcrow. Presentation chrome only —
        // no save-path contact.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hold the splash for a brief, BOUNDED minimum so the lines→¶ morph actually plays before it
        // dismisses. On a fast device first-frame-ready is near-instant, so without this the animation
        // was imperceptible ("too quick / unfinished"). This is a fixed *upper bound*, not an
        // indefinite gate: the condition becomes false after SPLASH_MIN_DISPLAY_MS regardless of app
        // state, so the splash can never hang.
        val splashStart = SystemClock.uptimeMillis()
        splashScreen.setKeepOnScreenCondition {
            SystemClock.uptimeMillis() - splashStart < SPLASH_MIN_DISPLAY_MS
        }
        // Whether this process was launched to open a specific file (vs a plain launcher start).
        // Gates the stranded-WAL-slot dialog: auto-pop only on a launcher start, never on a file open.
        val launchedToOpenFile = isFileOpenIntent(intent)
        setContent {
            PilcrowApp(
                context = this,
                intentUri = intentFileUri,
                onIntentConsumed = ::consumeIntentFileUri,
                launchedToOpenFile = launchedToOpenFile,
            )
        }

        // Handle intent-opened file (ACTION_VIEW from file manager, ACTION_SEND from share sheet).
        handleIntentFile(intent)
    }

    /** True if [intent] launched the app to open a specific file (ACTION_VIEW data / ACTION_SEND stream). */
    private fun isFileOpenIntent(intent: Intent?): Boolean = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data != null
        Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) != null
        else -> false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // keep getIntent() current for the running Activity
        // Handle intent when app is already running
        // (e.g., user taps a .md file while Pilcrow is open).
        handleIntentFile(intent)
    }

    /**
     * Extract URI from intent (ACTION_VIEW or ACTION_SEND), take persistent permission,
     * and store for the app to load.
     */
    private fun handleIntentFile(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data // File manager "Open with"
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) // Share sheet
            else -> null
        }

        if (uri != null) {
            // Request persistent permission so app can re-open file later.
            takePermission(uri)
            // Publish for PilcrowApp to observe and load.
            _intentFileUri.value = uri
        }
    }

    /**
     * Take persistent read permission for the given URI so it remains accessible after restart.
     * Some content providers don't support this; fail gracefully.
     */
    private fun takePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: Exception) {
            // Some file managers don't support persistent permissions; fail gracefully.
            // File still loads in this session but won't be re-openable after app restart.
            Log.w("MainActivity", "Could not take persistent permission for $uri", e)
        }
    }

    /** Clear the intent URI once handled, so it isn't re-loaded on the next recomposition. */
    fun consumeIntentFileUri() {
        _intentFileUri.value = null
    }
}

/**
 * Root composable for Pilcrow app.
 * Constructs ViewModel with repository and storage implementations.
 * Wires up MainScreen with full state management and SAF integration.
 * Handles intent-opened files via PilcrowApp initialization.
 */
@Composable
fun PilcrowApp(
    context: android.content.Context,
    intentUri: androidx.compose.runtime.State<android.net.Uri?>,
    onIntentConsumed: () -> Unit,
    launchedToOpenFile: Boolean = false,
) {
    MaterialTheme {
        // Obtain the composition root (AppContainer) from the Application instance.
        // applicationContext is always available and never destroyed until process death,
        // so this is safe for retained ViewModels (Safeguard: ViewModel never holds
        // an Activity reference, only applicationContext-derived dependencies).
        val appContext = context.applicationContext
        val container = (appContext as PilcrowApplication).container

        // Obtain the ViewModel from the factory, which injects dependencies from the container.
        // viewModel() retains the instance across recomposition and Activity recreation
        // (device rotation, etc.), so open file/mode/scroll survive.
        val viewModel: MarkdownViewModel = viewModel(
            factory = MarkdownViewModel.provideFactory(container),
        )

        // Evaluate stranded WAL slots once per process start. Detection always
        // runs; the dialog auto-pops only on a launcher start with slots — never on a file open.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            viewModel.evaluateStrandedSlotsOnStart(openingFileFromIntent = launchedToOpenFile)
        }

        // Intent-opened files (cold start + warm intent while running).
        // The Activity owns intentUri as Compose state and passes it down. The app observes
        // it without needing to downcast the context to MainActivity.
        androidx.compose.runtime.LaunchedEffect(intentUri.value) {
            if (intentUri.value != null) {
                val uri = intentUri.value!!
                // NOTE: takePermission and openFromIntent on the ViewModel are the
                // public interface: takePermission persists the SAF read/write grant
                // for the URI so the file stays accessible across restarts, and
                // openFromIntent guards against losing unsaved edits in the current
                // file (Safeguard 1).
                viewModel.takePermission(uri)
                viewModel.openFromIntent(uri)
                onIntentConsumed()
            }
        }

        // Main screen: toolbar + welcome/preview/editor content.
        // Thread the MarkwonRenderer to MainScreen so it doesn't construct one in the UI layer.
        MainScreen(
            viewModel = viewModel,
            context = context,
            renderer = container.markwonRenderer,
        )
    }
}
