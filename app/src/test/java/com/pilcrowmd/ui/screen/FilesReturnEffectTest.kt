// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S8-A38: [FilesReturnEffect] as composed, not just its rule: what survives a rotation, what survives
 * process death, and what a pick of the note already open does. The rule itself is in FilesReturnTest.
 *
 * Process death is told apart from a rotation by the ViewModel: after a rotation it still holds the
 * open note, after process death it is empty until the restore lands. [documentGone] stands in for
 * that, and is a plain field so flipping it does not itself recompose the old composition.
 */
@RunWith(RobolectricTestRunner::class)
class FilesReturnEffectTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val x = Uri.parse("content://t/x.md")
    private val y = Uri.parse("content://t/y.md")

    private var open by mutableStateOf<Uri?>(null)
    private var recompose by mutableIntStateOf(0)
    private var documentGone = false
    private var picks: FilesReturnPicks? = null
    private var returns = 0

    private fun show(tester: StateRestorationTester) {
        tester.setContent {
            recompose // read, so bumping it recomposes after a restore
            picks = FilesReturnEffect(if (documentGone) null else open) { returns++ }
        }
        compose.waitForIdle()
    }

    private fun openNote(uri: Uri?) {
        open = uri
        compose.waitForIdle()
    }

    @Test
    fun aRotationKeepsTheNoteOpenedFromMyFolders() {
        val tester = StateRestorationTester(compose)
        show(tester)
        compose.runOnIdle { picks!!.picked(x) }
        openNote(x)

        tester.emulateSavedInstanceStateRestore() // the ViewModel survives: x is still open
        compose.waitForIdle()
        assertEquals("a rotation is not a close", 0, returns)

        openNote(null)
        assertEquals(1, returns)
    }

    @Test
    fun aRestoreAfterProcessDeathDoesNotReopenMyFoldersOverTheNote() {
        val tester = StateRestorationTester(compose)
        show(tester)
        compose.runOnIdle { picks!!.picked(x) }
        openNote(x)

        documentGone = true // the process died: nothing is open until the restore lands
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("the empty ViewModel is not a close of x", 0, returns)

        documentGone = false // the restore loads x
        recompose++
        compose.waitForIdle()
        assertEquals(0, returns)

        openNote(null) // the user closes the restored note: it still came from My folders
        assertEquals(1, returns)
    }

    @Test
    fun pickingTheNoteThatIsAlreadyOpenCountsAsOpenedFromMyFolders() {
        val tester = StateRestorationTester(compose)
        show(tester)
        openNote(x) // opened some other way
        compose.runOnIdle { picks!!.picked(x) } // then picked again in My folders: nothing will change

        openNote(null)
        assertEquals(1, returns)
    }

    @Test
    fun aPickThatNeverOpenedDoesNotCountWhenThatNoteOpensAnotherWay() {
        val tester = StateRestorationTester(compose)
        show(tester)
        compose.runOnIdle { picks!!.picked(y) }
        compose.runOnIdle { picks!!.forget() } // its open was cancelled
        openNote(y) // later from Recent

        openNote(null)
        assertEquals(0, returns)
    }
}
