// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.testing

import android.view.View
import io.github.rosemoe.sora.widget.CodeEditor

private const val TEST_EDITOR_WIDTH_PX = 320
private const val TEST_EDITOR_HEIGHT_PX = 470

/**
 * Gives a Sora [CodeEditor] a real size before a test composes it.
 *
 * `MarkdownEditor`'s factory turns soft wrap on and sets the text on an editor that has not been
 * measured yet, so Sora lays it out with a wrap width of `0 - gutter`, below zero. In Robolectric
 * every character then overflows its row and Sora asks `WordBreakerIcu` for a break point, which
 * hands its `CharSequenceIterator` to the JDK's `RuleBasedBreakIterator`. That iterator never returns
 * for it: `CharSequenceIterator.previous()` stops at index 0 and answers the character there instead
 * of `DONE`, which `handlePrevious` waits for. The task runs on Sora's JVM-wide layout pool, so each
 * unmeasured wrapped editor kills a pool thread for good; after two of them every later editor in the
 * JVM keeps `layoutBusy` true, `isEditable` false and gets no InputConnection. (Android's own
 * `BreakIterator` is ICU, not the JDK class, so this is a unit-test hazard only.)
 *
 * With a positive width a document that fits never reaches the breaker, so sizing the editor first
 * keeps the pool alive for the tests that follow.
 */
fun <T : CodeEditor> T.withRealSize(): T {
    measure(
        View.MeasureSpec.makeMeasureSpec(TEST_EDITOR_WIDTH_PX, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(TEST_EDITOR_HEIGHT_PX, View.MeasureSpec.EXACTLY),
    )
    layout(0, 0, TEST_EDITOR_WIDTH_PX, TEST_EDITOR_HEIGHT_PX)
    return this
}
