// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.rosemoe.sora.widget.CodeEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the app's TOP stability defect: **Save-As via the SAF picker crashed the app on return**
 * with `IllegalStateException: The specified child already has a parent`.
 *
 * ## Why this test exists and what it pins
 *
 * `MarkdownEditor` reuses a HOISTED [CodeEditor] (`codeEditorInstance`) so the **undo stack survives
 * mode toggles** — that is a deliberate design decision, not an accident, and
 * the fix must not trade it away. But `AndroidView`'s factory hands its result straight to
 * `AndroidViewHolder`, which calls `addView(...)`. When the composition is torn down and rebuilt —
 * which is exactly what returning from the SAF `ACTION_CREATE_DOCUMENT` picker does — the factory
 * runs again with the SAME hoisted instance while that instance is STILL ATTACHED to the previous
 * holder. `ViewGroup.addViewInner` then throws and the process dies.
 *
 * Provenance was settled against Play Console (2026-08-24): an exact frame-for-frame match including
 * line numbers, cluster `51756ead17b727a9731a2f8baf99cff3`, present in versionCode 2 (1.0.1) as well
 * as 4 (1.0.3) — so it long predates the load-path threading work.
 *
 * ## The assertion that must not be weakened
 *
 * It is not enough that recomposition stops crashing: recreating the editor would also stop the
 * crash, **and would silently drop the user's undo history**. So this test pins BOTH halves — the
 * re-attach succeeds AND the very same instance is reused. A future "fix" that swaps in a fresh
 * `CodeEditor` fails [reattachReusesTheSameHoistedInstance] even though the crash is gone.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownEditorReattachTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun hoistedEditor(): CodeEditor = CodeEditor(ApplicationProvider.getApplicationContext())

    /**
     * RED without the fix. **The precondition matters and was established by experiment, not
     * assumed:** a plain `if (show) { ... }` toggle does NOT reproduce this — Compose detaches the
     * View on dispose, so that path is clean and a test built on it passes against the broken code.
     * What actually throws is the factory running while the hoisted instance is STILL PARENTED, and
     * a keyed swap produces exactly that (the new node is created before the old one is released),
     * which is the recomposition shape the SAF picker return drives.
     */
    @Test
    fun reattachSucceedsWhenTheNodeIsRecreatedByRecomposition() {
        val hoisted = hoistedEditor()
        var generation by mutableStateOf(0)

        compose.setContent {
            key(generation) {
                MarkdownEditor(
                    content = "line one\nline two\n",
                    onContentChange = {},
                    codeEditorInstance = hoisted,
                )
            }
        }
        compose.waitForIdle()

        generation = 1 // node recreated - throws IllegalStateException without detach-before-attach
        compose.waitForIdle()

        assertNotNull("editor must be attached after the node was recreated", hoisted.parent)
    }

    /**
     * The invariant stated directly: the factory must tolerate an instance that is still parented,
     * because after an abandoned composition nothing will have detached it.
     */
    @Test
    fun factoryDetachesAnAlreadyParentedEditor() {
        val hoisted = hoistedEditor()
        val stale = FrameLayout(ApplicationProvider.getApplicationContext())
        stale.addView(hoisted)
        assertSame("precondition: editor starts parented to a stale holder", stale, hoisted.parent)

        compose.setContent {
            MarkdownEditor(
                content = "alpha\n",
                onContentChange = {},
                codeEditorInstance = hoisted,
            )
        }
        compose.waitForIdle()

        assertNotNull("editor attached to the live holder", hoisted.parent)
        assertNotSame("editor must have been moved off the stale parent", stale, hoisted.parent)
        assertEquals("stale holder must no longer hold it", 0, stale.childCount)
    }

    /**
     * The undo stack lives on the CodeEditor instance, so "reuse the instance" IS "keep the undo
     * stack". Recreating the editor would make the crash disappear and lose the user's history.
     */
    @Test
    fun reattachReusesTheSameHoistedInstance() {
        val hoisted = hoistedEditor()
        var generation by mutableStateOf(0)

        compose.setContent {
            key(generation) {
                MarkdownEditor(
                    content = "alpha\nbeta\n",
                    onContentChange = {},
                    codeEditorInstance = hoisted,
                )
            }
        }
        compose.waitForIdle()
        generation = 1
        compose.waitForIdle()

        val holder = hoisted.parent
        assertNotNull("editor re-attached after recreation", holder)
        assertSame(
            "the hoisted CodeEditor instance must be REUSED - a fresh editor would drop the undo stack",
            hoisted,
            (holder as ViewGroup).getChildAt(0),
        )
    }
}
