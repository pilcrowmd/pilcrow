// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.app.Activity
import android.content.Intent
import android.text.Spanned
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.R
import io.noties.markwon.LinkResolver
import io.noties.markwon.core.spans.LinkSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * M-159 / M-173: what a tap on a rendered link does, through the REAL Markwon instance the reader
 * builds, so the resolver is exercised where production installs it. The assertions are on observable
 * effects — the platform's started activity, and the block the list jumped to — never on a flag.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderLinkResolverTest {

    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    /** The rendered link [link], inside a reader list whose anchors are [targets] (or no list at all). */
    private class Reader(val list: RecyclerView?, val textView: TextView) {
        fun tap() {
            val spanned = textView.text as Spanned
            val span = spanned.getSpans(0, spanned.length, LinkSpan::class.java).single()
            span.onClick(textView)
        }
    }

    private fun reader(link: String, targets: Map<String, Int>? = null, inList: Boolean = true): Reader {
        val textView = TextView(activity)
        buildPilcrowMarkwon(activity).setMarkdown(textView, "[label]($link)")
        if (!inList) return Reader(null, textView)
        val list = RecyclerView(activity)
        list.layoutManager = LinearLayoutManager(activity)
        applyReaderBottomSpacer(list)
        targets?.let { list.setTag(R.id.anchor_targets, AnchorTargets().apply { byAnchor = it }) }
        list.addView(textView)
        return Reader(list, textView)
    }

    private fun tapped(link: String): Intent? {
        reader(link).tap()
        return shadowOf(activity).nextStartedActivity
    }

    // --- #heading ---

    @Test
    fun `a heading link jumps to its block and does not leave the app`() {
        val reader = reader("#target", targets = mapOf("target" to 7))
        reader.tap()
        assertNull("a # link must never start an activity", shadowOf(activity).nextStartedActivity)
        assertEquals("the block the anchor maps to is the one jumped to", 7, highlightedBlock(reader.list!!))
    }

    @Test
    fun `a heading link that names nothing does nothing`() {
        val reader = reader("#nowhere", targets = mapOf("target" to 7))
        reader.tap()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(RecyclerView.NO_POSITION, highlightedBlock(reader.list!!))
    }

    @Test
    fun `a heading link in a reader that has no targets does nothing`() {
        val reader = reader("#target")
        reader.tap()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(RecyclerView.NO_POSITION, highlightedBlock(reader.list!!))
    }

    @Test
    fun `a heading link outside any list does nothing and does not crash`() {
        reader("#target", inList = false).tap()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    // --- links that must not reach the platform (M-173, M-160 not built) ---

    @Test
    fun `a file link does nothing`() {
        assertNull(tapped("file:///sdcard/x.md"))
    }

    @Test
    fun `a relative note link does nothing`() {
        assertNull("./other.md", tapped("./other.md"))
        assertNull("../other", tapped("../other"))
        assertNull("/notes/other", tapped("/notes/other"))
        assertNull("other.md", tapped("other.md"))
        assertNull("other.MD?x=1", tapped("other.MD?x=1"))
        assertNull("other.txt#part", tapped("other.txt#part"))
    }

    // --- links that are unchanged ---

    @Test
    fun `a web link still opens in the viewer`() {
        val intent = tapped("https://example.com")
        assertNotNull("the tap must reach the platform", intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("https://example.com", intent.data.toString())
    }

    @Test
    fun `a mailto link still opens the mail app`() {
        val intent = tapped("mailto:a@example.com")
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("mailto:a@example.com", intent.data.toString())
    }

    // --- what reaches the delegate ---

    private class Recording : LinkResolver {
        val links = mutableListOf<String>()
        override fun resolve(view: View, link: String) {
            links += link
        }
    }

    @Test
    fun `a scheme-less web address goes to the delegate as written`() {
        val recording = Recording()
        val resolver = ReaderLinkResolver(recording)
        resolver.resolve(TextView(activity), "example.com/page")
        resolver.resolve(TextView(activity), "www.example.com")
        assertEquals(listOf("example.com/page", "www.example.com"), recording.links)
    }

    @Test
    fun `a heading link never reaches the delegate`() {
        val recording = Recording()
        ReaderLinkResolver(recording).resolve(TextView(activity), "#x")
        assertEquals(emptyList<String>(), recording.links)
    }

    // --- M-160: the screen's note-link handler ---

    private fun inReader(handler: NoteLinkHandler?): TextView {
        val list = RecyclerView(activity)
        list.layoutManager = LinearLayoutManager(activity)
        handler?.let { list.setTag(R.id.note_link_handler, it) }
        val textView = TextView(activity)
        list.addView(textView)
        return textView
    }

    @Test
    fun `a handler that takes a scheme-less link keeps it from the delegate`() {
        val recording = Recording()
        val seen = mutableListOf<String>()
        val view = inReader {
            seen += it
            true
        }
        ReaderLinkResolver(recording).resolve(view, "projects/plan.md")
        ReaderLinkResolver(recording).resolve(view, "www.example.com")
        assertEquals(listOf("projects/plan.md", "www.example.com"), seen)
        assertEquals(emptyList<String>(), recording.links)
    }

    @Test
    fun `a handler that declines, or none, leaves today's behaviour`() {
        for (handler in listOf<NoteLinkHandler?>(NoteLinkHandler { false }, null)) {
            val recording = Recording()
            val view = inReader(handler)
            ReaderLinkResolver(recording).resolve(view, "other.md")
            ReaderLinkResolver(recording).resolve(view, "www.example.com")
            assertEquals("a note path still does nothing", listOf("www.example.com"), recording.links)
        }
    }

    @Test
    fun `heading, file and web links never reach the handler`() {
        val seen = mutableListOf<String>()
        val view = inReader {
            seen += it
            true
        }
        val recording = Recording()
        for (link in listOf("#x", "file:///x.md", "https://example.com", "mailto:a@example.com")) {
            ReaderLinkResolver(recording).resolve(view, link)
        }
        assertEquals(emptyList<String>(), seen)
        assertEquals(listOf("https://example.com", "mailto:a@example.com"), recording.links)
    }

    // --- a refusal from the platform ---

    @Test
    fun `a delegate that throws does not take the reader down`() {
        var called = 0
        val resolver = ReaderLinkResolver(
            LinkResolver { _: View, _: String ->
                called++
                error("no handler")
            },
        )
        resolver.resolve(TextView(activity), "https://example.com")
        assertEquals("the delegate was reached, so the catch is what held", 1, called)
    }
}
