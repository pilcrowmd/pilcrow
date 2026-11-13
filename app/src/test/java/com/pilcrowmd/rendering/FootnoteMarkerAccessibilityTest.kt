// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.Footnotes
import com.pilcrowmd.domain.markdown.ReaderWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-76, TalkBack: each footnote marker in a prose block is its own "Footnote N, button" node, and
 * activating it does what a finger tap does: it asks the reader's handler for the note.
 *
 * Driven through the real [ProseBlockEntry] bind and the accessibility node provider TalkBack itself
 * queries, so an entry that forgot to install the helper fails here.
 */
@RunWith(RobolectricTestRunner::class)
class FootnoteMarkerAccessibilityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val markwon = buildPilcrowMarkwon(context)
    private val calls = mutableListOf<Int>()

    private fun boundProse(markdown: String): ProseBlockEntry.Holder {
        val entry = ProseBlockEntry(context, words = ReaderWords.ENGLISH)
        val reader = FrameLayout(context).apply { setFootnoteTapHandler { calls += it } }
        val holder = entry.createHolder(LayoutInflater.from(context), reader)
        reader.addView(holder.textView)
        entry.bindHolder(markwon, holder, Footnotes.transform(markwon.parse(markdown)).firstChild!!)
        holder.textView.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        holder.textView.layout(0, 0, WIDTH_PX, holder.textView.measuredHeight)
        return holder
    }

    @Test
    fun `a marker is a Footnote N button that opens its note, and the paragraph still reads whole`() {
        val textView = boundProse("See[^n] here.\n\n[^n]: body\n").textView
        // The provider TalkBack itself queries, through the platform view.
        val provider = textView.accessibilityNodeProvider!!

        val node = provider.createAccessibilityNodeInfo(0)!!
        assertEquals("Footnote 1", node.text.toString())
        assertEquals(Button::class.java.name, node.className.toString())
        assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })

        val host = provider.createAccessibilityNodeInfo(View.NO_ID)!!
        assertEquals("the paragraph is read as before", "See1 here.", host.text.toString().trim())
        assertEquals("one marker, one node", 1, host.childCount)
        assertTrue(
            "the paragraph itself still offers no click, as M-157 asks of any selectable block",
            host.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK } && !host.isClickable,
        )

        assertTrue(provider.performAction(0, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals("a double tap asks for the definition's block, as a finger tap does", listOf(1), calls)
    }

    @Test
    fun `a paragraph with no marker has no marker nodes`() {
        val textView = boundProse("Plain prose.\n").textView
        val provider = textView.accessibilityNodeProvider!!
        val host = provider.createAccessibilityNodeInfo(View.NO_ID)!!
        assertEquals(0, host.childCount)
        assertEquals(
            "Guard: no marker, nothing to activate",
            false,
            provider.performAction(0, AccessibilityNodeInfo.ACTION_CLICK, null),
        )
        assertTrue(calls.isEmpty())
    }

    private companion object {
        const val WIDTH_PX = 600
    }
}
