// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.rosemoe.sora.widget.CodeEditor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards **M-94**: right-to-left rows in the editor were anchored to the LEFT.
 *
 * ## The defect, and why the fix is one argument
 *
 * Sora already does the bidi work — character ordering in an Arabic line was always correct. What
 * was wrong is where the row is *anchored*. Sora 0.24.5 supports right-aligned RTL rows, but the
 * one-argument `setWordwrap(true)` overload leaves that support **off**.
 *
 * Read out of the shipped `editor-0.24.5-runtime.jar` bytecode rather than from the library's
 * documentation, because the argument names are not in the signature and getting the middle one
 * wrong would change word breaking:
 *
 * ```
 * setWordwrap(v)       -> setWordwrap(v, true)          // antiWordBreaking defaults TRUE
 * setWordwrap(v, a)    -> setWordwrap(v, a, false)      // rtlDisplaySupport defaults FALSE
 * setWordwrap(v, a, r) -> wordwrap = v; antiWordBreaking = a; wordwrapRtlDisplaySupport = r
 * ```
 *
 * So the old call was exactly `setWordwrap(true, true, false)`, and the fix flips **only** the
 * third argument. That is the whole change.
 *
 * ## Why this test asserts three things and not one
 *
 * Asserting only that RTL support is on would pass for `setWordwrap(true, false, true)` — which
 * also silently turns anti-word-breaking OFF and would change how every LTR line wraps. The
 * contributor's claim is that nothing but RTL anchoring changes, so the two unchanged flags are
 * pinned here **as the claim**, not as decoration. Each of the three fails on its own if the call
 * is written wrongly.
 *
 * ## Credit
 *
 * Diagnosed and fixed by **yshalsager** (Youssif Shaaban Alsager), who wrote and tested the change
 * and gave written permission on the public thread for it to be applied without merging his pull
 * request. See pull request #3 on the public repository.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownEditorRtlAlignmentTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun hoistedEditor(): CodeEditor = CodeEditor(ApplicationProvider.getApplicationContext())

    /**
     * RED before the fix: `wordwrapRtlDisplaySupport` is `false` on a freshly configured editor,
     * because the one-argument overload passes `false` for it. **Watched failing before the fix
     * landed** — reverting `Editor.kt`'s call to `setWordwrap(true)` fails this on the first
     * assertion, which is the only reason the test is worth keeping.
     */
    @Test
    fun editorEnablesRightAlignedRtlRows() {
        val hoisted = hoistedEditor()
        assertFalse(
            "precondition: a bare CodeEditor starts with RTL row alignment OFF, so a pass here " +
                "would mean the assertion below is measuring the default rather than the fix",
            hoisted.isWordwrapRtlDisplaySupport,
        )

        compose.setContent {
            MarkdownEditor(
                content = "مرحبا بالعالم\nhello world\n",
                onContentChange = {},
                codeEditorInstance = hoisted,
            )
        }
        compose.waitForIdle()

        assertTrue(
            "RTL rows must be anchored to the right edge (M-94)",
            hoisted.isWordwrapRtlDisplaySupport,
        )
    }

    /**
     * The other half of the contributor's claim: soft wrap and anti-word-breaking are **unchanged**
     * by the fix. Writing `setWordwrap(true, false, true)` would satisfy the test above and break
     * this one.
     */
    @Test
    fun rtlAlignmentDoesNotChangeSoftWrapOrWordBreaking() {
        val hoisted = hoistedEditor()

        compose.setContent {
            MarkdownEditor(
                content = "a fairly long line of latin text that will need to soft wrap somewhere\n",
                onContentChange = {},
                codeEditorInstance = hoisted,
            )
        }
        compose.waitForIdle()

        assertTrue("soft wrap must stay on - no horizontal scroll", hoisted.isWordwrap)
        assertTrue(
            "anti-word-breaking must stay on: it is the default the one-argument overload passed, " +
                "so turning it off here would be a second, unannounced change to LTR wrapping",
            hoisted.isAntiWordBreaking,
        )
    }
}
