// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.screenshot

import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.pilcrowmd.domain.folders.Ago
import com.pilcrowmd.domain.folders.FolderEntry
import com.pilcrowmd.domain.folders.Mark
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.ui.components.FilesScreen
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.viewmodel.FileRowUi
import com.pilcrowmd.viewmodel.FolderRowUi
import com.pilcrowmd.viewmodel.ListingUi
import com.pilcrowmd.viewmodel.NotesFoldersUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The states of the Files screen the goldens show (M-162b), shared with FilesScreenTest. */
object FilesSample {
    val work: Uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AWork%2FNotes")
    val home: Uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AHome%2FNotes")

    /** Two folders named Notes (P15 suffixes), Work ticked with 4 marked notes. */
    val loaded = NotesFoldersUiState(
        folders = listOf(
            FolderRowUi(work, "Notes", "Work", available = true, selected = true, markCount = 4),
            FolderRowUi(home, "Notes", "Home", available = true, selected = false),
        ),
        listing = ListingUi.Loaded(
            root = FolderEntry("primary:Work/Notes", "Notes", emptyList(), emptyList()),
            expanded = setOf("projects"),
            rows = listOf(
                FileRowUi.Folder("projects", "projects", depth = 0, expanded = true, markCount = 1),
                FileRowUi.Note("plan", "plan.md", depth = 1, mark = Mark.UPDATED, ago = Ago.Hours(1)),
                FileRowUi.Folder("archive", "archive", depth = 0, expanded = false, markCount = 2),
                FileRowUi.Note("index", "index.md", depth = 0, mark = null, ago = null),
                FileRowUi.Note("note2", "Note 2.md", depth = 0, mark = Mark.NEW, ago = Ago.Minutes(12)),
                FileRowUi.Note("note10", "Note 10.md", depth = 0, mark = null, ago = null),
            ),
        ),
        message = null,
    )

    /** The note the reader shows in [loaded]: it keeps the grey highlight. */
    const val OPEN_NOTE = "index"

    val empty = NotesFoldersUiState(emptyList(), ListingUi.NoFolders, message = null)

    val unavailable = NotesFoldersUiState(
        folders = listOf(FolderRowUi(work, "Notes", null, available = false, selected = true)),
        listing = ListingUi.Unavailable,
        message = null,
    )
}

/** M-162b: golden images of the Files screen, Dark and Light (spec section 3b, section 6 Android). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class FilesScreenshotTest(private val themeMode: ThemeMode) {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Text on a flat background: the cross-OS antialiasing tolerance the GitHub teaser goldens use.
    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.05f),
    )

    @Test
    fun golden() = capture("files", FilesSample.loaded, FilesSample.OPEN_NOTE)

    @Test
    fun goldenEmpty() = capture("files_empty", FilesSample.empty)

    @Test
    fun goldenUnavailable() = capture("files_unavailable", FilesSample.unavailable)

    private fun capture(name: String, state: NotesFoldersUiState, openNoteId: String? = null) {
        val colors = if (themeMode == ThemeMode.DARK) DarkColorScheme else LightColorScheme
        val theme = if (themeMode == ThemeMode.DARK) "dark" else "light"
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides colors) {
                FilesScreen(state = state, openNoteId = openNoteId)
            }
        }
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/${name}_$theme.png",
            roborazziOptions = roborazziOptions,
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<ThemeMode> = listOf(ThemeMode.DARK, ThemeMode.LIGHT)
    }
}
