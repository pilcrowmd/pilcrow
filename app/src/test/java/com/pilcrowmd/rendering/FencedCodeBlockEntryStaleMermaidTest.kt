// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.widget.TextView
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult
import io.mockk.every
import io.mockk.mockk
import io.noties.markwon.Markwon
import kotlinx.coroutines.CompletableDeferred
import org.commonmark.node.FencedCodeBlock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guards **M-222**: a code block must never show the source of a Mermaid diagram that failed to load.
 *
 * Code, YAML and Mermaid blocks share one recycled holder. A Mermaid bind starts a Coil request whose
 * error path writes the diagram's source into `codeView`. Seen on the S24+ (stress test v1.1): the
 * request outlived the bind, the holder was reused for a code block or the front-matter card, and the
 * late failure overwrote that block's text with the Mermaid source.
 *
 * The request here is real Coil, with a fetcher that fails only when the test says so, AFTER the holder
 * has been rebound. The holder sits in an attached activity window, as a list item does. Each case
 * waits for the fetch to START before rebinding and for the request to END (error or cancel) before
 * asserting, so it cannot pass by asserting before the late result arrives. Seen failing without the
 * fix: both rebind cases showed the Mermaid source.
 */
@RunWith(RobolectricTestRunner::class)
class FencedCodeBlockEntryStaleMermaidTest {

    private lateinit var context: Context
    private lateinit var activity: Activity
    private val release = CompletableDeferred<Unit>()
    private val fetchStarted = AtomicBoolean(false)
    private val requestEnded = AtomicBoolean(false)

    /** A fetcher for every URI that waits for [release], then fails as mermaid.ink does on bad syntax. */
    private inner class FailingFetcher : Fetcher {
        override suspend fun fetch(): FetchResult {
            fetchStarted.set(true)
            release.await()
            throw IOException("mermaid.ink rejected the diagram")
        }
    }

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        context = activity
        val ended = object : EventListener {
            override fun onError(request: ImageRequest, result: ErrorResult) = requestEnded.set(true)
            override fun onCancel(request: ImageRequest) = requestEnded.set(true)
            override fun onSuccess(request: ImageRequest, result: SuccessResult) = requestEnded.set(true)
        }
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components {
                    add(Fetcher.Factory<Uri> { _: Uri, _: Options, _: ImageLoader -> FailingFetcher() })
                }
                .eventListener(ended)
                .build(),
        )
    }

    @After
    fun tearDown() {
        release.complete(Unit)
        Coil.reset()
    }

    /** A Markwon that renders the node's literal as plain text. */
    private fun literalMarkwon(): Markwon = mockk<Markwon>(relaxed = true).also { m ->
        every { m.render(any()) } answers { android.text.SpannableString(firstArg<FencedCodeBlock>().literal) }
        every { m.setParsedMarkdown(any(), any()) } answers { firstArg<TextView>().text = secondArg() }
    }

    private fun node(info: String, literal: String) = FencedCodeBlock().apply {
        this.info = info
        this.literal = literal
    }

    private fun attachedHolder(entry: FencedCodeBlockEntry): FencedCodeBlockEntry.Holder {
        val parent = android.widget.FrameLayout(context)
        activity.setContentView(parent)
        val holder = entry.createHolder(android.view.LayoutInflater.from(context), parent)
        parent.addView(holder.itemView)
        idle()
        return holder
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Idle the main looper until [flag] is set; Coil hops to a worker thread and back. */
    private fun awaitFlag(flag: AtomicBoolean, what: String) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!flag.get()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            idle()
            Thread.sleep(POLL_MS)
        }
        idle()
    }

    private val mermaid = node("mermaid", "flowchart TD\n  A[Valid start] --> B{{Broken syntax")

    @Test
    fun `a failed Mermaid fetch does not overwrite the code block the holder shows next`() {
        val entry = FencedCodeBlockEntry(context)
        val markwon = literalMarkwon()
        val holder = attachedHolder(entry)

        entry.bindMermaid(markwon, holder, mermaid)
        awaitFlag(fetchStarted, "the Mermaid fetch to start")

        val python = node("python", "def f():\n    return 42")
        entry.bindHolder(markwon, holder, python)
        idle()
        assertEquals("precondition: the rebind shows the code block", python.literal, holder.codeView.text.toString())

        release.complete(Unit)
        awaitFlag(requestEnded, "the Mermaid request to end")

        assertEquals(
            "the late Mermaid failure must not replace the code block's text",
            python.literal,
            holder.codeView.text.toString(),
        )
    }

    @Test
    fun `a failed Mermaid fetch does not overwrite the front-matter card the holder shows next`() {
        val entry = FencedCodeBlockEntry(context)
        val yamlEntry = FrontmatterBlockEntry(context)
        val markwon = literalMarkwon()
        val holder = attachedHolder(entry)

        entry.bindMermaid(markwon, holder, mermaid)
        awaitFlag(fetchStarted, "the Mermaid fetch to start")

        yamlEntry.bindHolder(markwon, holder, node("yaml", "title: Stress test\nauthor: fixture"))
        idle()
        val card = holder.codeView.text.toString()
        assertTrue("precondition: the card shows the front matter", card.contains("Stress test"))

        release.complete(Unit)
        awaitFlag(requestEnded, "the Mermaid request to end")

        assertEquals(
            "the late Mermaid failure must not replace the front-matter card",
            card,
            holder.codeView.text.toString(),
        )
    }

    @Test
    fun `a Mermaid fetch that fails while its own block is shown still falls back to the source`() {
        val entry = FencedCodeBlockEntry(context)
        val holder = attachedHolder(entry)

        entry.bindMermaid(literalMarkwon(), holder, mermaid)
        awaitFlag(fetchStarted, "the Mermaid fetch to start")
        release.complete(Unit)
        awaitFlag(requestEnded, "the Mermaid request to end")

        assertEquals(
            "Safeguard 3: an unrendered diagram shows its source",
            mermaid.literal,
            holder.codeView.text.toString(),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
