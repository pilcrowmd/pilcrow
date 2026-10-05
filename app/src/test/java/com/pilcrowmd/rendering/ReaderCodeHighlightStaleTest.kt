// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.theme.DarkColorScheme
import io.noties.markwon.recycler.MarkwonAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * M-136 — a colouring job that finishes after its holder was rebound must not colour what the holder
 * shows now. All fenced blocks share one holder type, and the front-matter and Mermaid-cloud routes never pass
 * through FencedCodeBlockEntry.bindHolder, so each route is covered.
 *
 * The fake tokeniser really suspends, and does NOT stop when cancelled (tm4e cannot be interrupted
 * mid-line), so the late result always arrives: only the holder's key can stop it landing. Every block
 * shows the text `echo hi`, so the late runs would fit whatever the holder shows; the offset guard can
 * never be what refuses them (each test checks that first).
 */
@RunWith(RobolectricTestRunner::class)
class ReaderCodeHighlightStaleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val keyword = DarkColorScheme.codeSyntax.keyword.toArgb()

    /** Suspends every request until the test releases it; always answers "echo" as a keyword. */
    private class GatedHighlighter : CodeHighlighter {
        val waiting = ArrayList<Continuation<Unit>>()

        override fun cachedRuns(scopeName: String, code: String): List<CodeRun>? = null

        override fun tokenize(scopeName: String, code: String, isActive: () -> Boolean): List<CodeRun>? =
            error("the reader never tokenises inline")

        override suspend fun tokenizeAsync(scopeName: String, code: String): List<CodeRun> {
            suspendCoroutine { waiting += it }
            return listOf(CodeRun(0, "echo".length, CodeRole.KEYWORD))
        }

        fun release(index: Int) = waiting[index].resume(Unit)
    }

    private val highlighter = GatedHighlighter()

    // Front matter must open the document, so the first code block is at position 1. Only `---` front
    // matter takes the metadata card; a ```yaml fence is an ordinary code block (NEW-32).
    private val markdown = listOf(
        "---\ntitle: echo hi\n---",
        "```bash\necho hi\n```",
        "```mermaid\necho hi\n```",
        "```ts\necho hi\n```",
    ).joinToString("\n\n")

    private fun adapter(search: SearchHighlight = SearchHighlight()): MarkwonAdapter {
        val markwon = buildPilcrowMarkwon(context, DarkColorScheme)
        return RecyclerAdapterEntries.buildMarkdownAdapter(
            context,
            markwon,
            mermaidCloudEnabled = true,
            searchHighlight = search,
            codeHighlighting = CodeHighlighting(highlighter, scope),
        ).also { it.setMarkdown(markwon, markdown) }
    }

    private fun MarkwonAdapter.holder(): MarkwonAdapter.Holder =
        onCreateViewHolder(FrameLayout(context), getItemViewType(0))

    private fun codeText(holder: MarkwonAdapter.Holder): Spanned =
        (holder as FencedCodeBlockEntry.Holder).codeView.text as Spanned

    private fun keywordSpans(text: Spanned) =
        text.getSpans(0, text.length, ForegroundColorSpan::class.java).filter { it.foregroundColor == keyword }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `control - a job that finishes while its block is still shown colours it`() {
        val adapter = adapter()
        val holder = adapter.holder()
        adapter.onBindViewHolder(holder, FIRST_CODE)
        assertTrue("no colour before the job ends", keywordSpans(codeText(holder)).isEmpty())
        highlighter.release(0)
        assertEquals(1, keywordSpans(codeText(holder)).size)
    }

    private fun assertRebindDropsTheLateResult(rebindTo: Int) {
        val adapter = adapter()
        val holder = adapter.holder()
        adapter.onBindViewHolder(holder, FIRST_CODE)
        adapter.onBindViewHolder(holder, rebindTo)
        val shown = codeText(holder)
        check(shown.contains("echo hi")) { "the late runs must fit the new text, or the offset guard answers first" }
        highlighter.release(0)
        assertEquals("late colours on block $rebindTo", emptyList<Any>(), keywordSpans(codeText(holder)))
    }

    @Test
    fun `code to code - the old block's colours never land`() = assertRebindDropsTheLateResult(3)

    @Test
    fun `code to front matter - the old block's colours never land`() = assertRebindDropsTheLateResult(0)

    @Test
    fun `code to Mermaid cloud - the old block's colours never land`() = assertRebindDropsTheLateResult(2)

    @Test
    fun `a late result keeps the search highlight`() {
        val colour = 0xFF123456.toInt()
        val adapter = adapter(SearchHighlight(query = "echo", otherColor = colour, focusedColor = colour))
        val holder = adapter.holder()
        adapter.onBindViewHolder(holder, FIRST_CODE)
        highlighter.release(0)
        val text = codeText(holder)
        assertEquals("token colour", 1, keywordSpans(text).size)
        val at = text.indexOf("echo")
        assertTrue(
            "search highlight",
            text.getSpans(at, at + 1, BackgroundColorSpan::class.java).any { it.backgroundColor == colour },
        )
    }

    private companion object {
        const val FIRST_CODE = 1
    }
}
