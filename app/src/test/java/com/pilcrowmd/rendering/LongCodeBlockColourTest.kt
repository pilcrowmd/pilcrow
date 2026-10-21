// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.os.Looper
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.ui.theme.DarkColorScheme
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.commonmark.node.FencedCodeBlock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Issue #17: a code block too long to colour at bind (Prism4j over 10,000 chars, TextMate over its old
 * 2,000 lines) is cut into pieces for the reader and coloured in full, off Main. The outcome a reader
 * sees is what is asserted, through the real adapter, the real Markwon instance and the real
 * highlighters:
 *
 *  - the LAST piece's last line is coloured, for a Prism4j language and for a TextMate one (before,
 *    everything past the first 10,000 chars or 2,000 lines stayed plain);
 *  - colouring does not run on the bind: a piece binds plain, the highlighter is never called inline, and
 *    when the colours arrive they land on every piece shown, from ONE tokenise of the whole block;
 *  - a search highlight on a piece is still there after the late colours land.
 *
 * Off-Main runs use a fake highlighter that really suspends, like [ReaderCodeHighlightStaleTest]'s. The
 * reader hops to Dispatchers.Default for the tokenise, so the tests wait on the scope's own jobs
 * ([awaitColouring]) and never on a sleep.
 */
@RunWith(RobolectricTestRunner::class)
class LongCodeBlockColourTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val markwon: Markwon = buildPilcrowMarkwon(context, DarkColorScheme)
    private val syntax = DarkColorScheme.codeSyntax
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
        mainScope.cancel()
    }

    /** A document with a heading, [fence] and a closing paragraph; the adapter over it and where the code is. */
    private class Built(val adapter: MarkwonAdapter, val codeStart: Int, val codeEnd: Int)

    private fun build(
        code: String,
        info: String,
        highlighting: CodeHighlighting,
        search: SearchHighlight = SearchHighlight(),
    ): Built {
        val markdown = "# Head\n\n```$info\n$code```\n\nAfter the block.\n"
        val document = ReaderDocument.transform(markwon.parse(markdown))
        val blocks = AdapterBlocks.of(document)
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(
            context,
            markwon,
            searchHighlight = search,
            codeHighlighting = highlighting,
        )
        adapter.setParsedMarkdown(markwon, document)
        return Built(
            adapter,
            blocks.indexOfFirst {
                it is FencedCodeBlock
            },
            blocks.indexOfLast { it is FencedCodeBlock },
        )
    }

    /** The adapter position of piece [index] of the block. */
    private fun Built.piece(index: Int): Int {
        assertTrue(
            "the block has a piece $index: it has ${codeEnd - codeStart + 1} items",
            codeStart + index <= codeEnd,
        )
        return codeStart + index
    }

    private fun MarkwonAdapter.bind(position: Int): FencedCodeBlockEntry.Holder {
        val holder = onCreateViewHolder(FrameLayout(context), getItemViewType(position))
        onBindViewHolder(holder, position)
        return holder as FencedCodeBlockEntry.Holder
    }

    private fun Spanned.hasColourAt(index: Int, colour: Int): Boolean {
        check(index in 0 until length) { "index $index is not in the text of $length chars" }
        return getSpans(index, index + 1, ForegroundColorSpan::class.java).any { it.foregroundColor == colour }
    }

    // --- The last piece of a long block is coloured ---------------------------------------------

    private val json: String = buildString {
        var i = 0
        while (length <= JSON_CHARS) {
            append("  { \"id\": $i, \"name\": \"item-$i\", \"active\": true, \"note\": null },\n")
            i++
        }
    }

    @Test
    fun `the last line of a 25,000 char json block is coloured, Prism4j path`() {
        check(json.length > 25_000) { "the fixture is far over the 10,000 char limit" }
        val built =
            build(
                json,
                "json",
                CodeHighlighting(TextMateCodeHighlighter(context.assets::open), scope = null, Prism4jCodeHighlighter()),
            )

        val text = built.adapter.bind(built.codeEnd).codeView.text as Spanned

        val lastLine = text.lastIndexOf("{ \"id\"")
        val lastId = Regex("\"item-(\\d+)\"").findAll(json).last().groupValues[1]
        assertTrue("the last piece shows the block's last line", text.contains("\"item-$lastId\""))
        val string = lastLine + text.substring(lastLine).indexOf("\"item-") + 1
        assertTrue("the last line's string has the string colour", text.hasColourAt(string, syntax.string.toArgb()))
        val number = lastLine + text.substring(lastLine).indexOf("$lastId,")
        assertTrue("the last line's number has the number colour", text.hasColourAt(number, syntax.number.toArgb()))
    }

    @Test
    fun `the first piece of the json block is coloured too, whichever piece is bound first`() {
        val built =
            build(
                json,
                "json",
                CodeHighlighting(TextMateCodeHighlighter(context.assets::open), scope = null, Prism4jCodeHighlighter()),
            )

        // The last piece first: it tokenises the whole block, the first piece then takes its share.
        built.adapter.bind(built.codeEnd)
        val text = built.adapter.bind(built.codeStart).codeView.text as Spanned

        val string = text.indexOf("\"item-0\"") + 1
        assertTrue(text.hasColourAt(string, syntax.string.toArgb()))
    }

    @Test
    fun `a long block becomes several adapter items`() {
        val built = build(rust, "rust", CodeHighlighting(TextMateCodeHighlighter(context.assets::open), scope = null))

        assertEquals("2,500 short lines in pieces of 100", 25, built.codeEnd - built.codeStart + 1)
    }

    private val rust: String = (1..RUST_LINES).joinToString("") { "fn f$it() {}\n" }

    @Test
    fun `the last of 2,500 rust lines is coloured, TextMate path`() {
        val built =
            build(
                rust,
                "rust",
                CodeHighlighting(TextMateCodeHighlighter(context.assets::open), scope = null, Prism4jCodeHighlighter()),
            )

        val text = built.adapter.bind(built.codeEnd).codeView.text as Spanned

        val lastLine = text.lastIndexOf("fn f$RUST_LINES()")
        assertTrue("the last piece shows the block's last line", lastLine >= 0)
        assertTrue("`fn` on the last line has the keyword colour", text.hasColourAt(lastLine, syntax.keyword.toArgb()))
        assertTrue("and so does the function name", text.hasColourAt(lastLine + "fn ".length, syntax.function.toArgb()))
    }

    // --- Colouring happens off the bind ----------------------------------------------------------

    /**
     * Suspends the one tokenise until the test releases it, and answers `fn` at the start of every line as
     * a keyword. Counts what the reader asks for, inline calls included (that would be Main). An inline
     * call answers null rather than throwing: the bind would catch a throw and show its failure text, and
     * the test must fail on the inline count, not on that.
     */
    private class GatedHighlighter : CodeHighlighter {
        val inlineCalls = AtomicInteger()
        val requested = CopyOnWriteArrayList<String>()
        val reached = CountDownLatch(1)
        private val waiting = CopyOnWriteArrayList<Continuation<Unit>>()

        override fun cachedRuns(scopeName: String, code: String): List<CodeRun>? = null

        override fun tokenize(scopeName: String, code: String, isActive: () -> Boolean): List<CodeRun>? {
            inlineCalls.incrementAndGet()
            return null
        }

        override suspend fun tokenizeAsync(scopeName: String, code: String): List<CodeRun> {
            requested += code
            suspendCoroutine {
                waiting += it
                reached.countDown()
            }
            return fnRuns(code)
        }

        fun release() = waiting.forEach { it.resume(Unit) }

        private fun fnRuns(code: String): List<CodeRun> {
            val runs = ArrayList<CodeRun>()
            var start = 0
            while (start < code.length) {
                runs += CodeRun(start, start + "fn".length, CodeRole.KEYWORD)
                start = code.indexOf('\n', start).let { if (it < 0) code.length else it + 1 }
            }
            return runs
        }
    }

    private val gate = GatedHighlighter()

    /** Waits until every colouring job the reader started has ended, and fails if one never does. */
    private fun awaitColouring() {
        assertTrue("the reader asked for the colours", gate.reached.await(WAIT_S, TimeUnit.SECONDS))
        gate.release()
        runBlocking {
            withTimeout(TimeUnit.SECONDS.toMillis(WAIT_S)) { scope.coroutineContext.job.children.toList().joinAll() }
        }
    }

    private fun keywordSpans(text: Spanned) = text.getSpans(0, text.length, ForegroundColorSpan::class.java).filter {
        it.foregroundColor ==
            syntax.keyword.toArgb()
    }

    private fun gated(search: SearchHighlight = SearchHighlight()) =
        build(rust, "rust", CodeHighlighting(gate, scope, prism4j = gate), search)

    @Test
    fun `a piece binds plain, nothing is tokenised on the bind, and the colours land when released`() {
        val built = gated()
        val holder = built.adapter.bind(built.piece(3))
        val text = holder.codeView.text as Spanned
        check(text.contains("fn f350()")) { "piece 3 of a rust block of short lines holds lines 301 to 400" }

        assertEquals("plain at bind", emptyList<Any>(), keywordSpans(text))
        assertEquals("no inline tokenise: that would run on Main", 0, gate.inlineCalls.get())

        awaitColouring()

        val coloured = holder.codeView.text as Spanned
        assertEquals("one keyword per line of the piece, none from another piece", 100, keywordSpans(coloured).size)
        keywordSpans(coloured).forEach {
            assertEquals(
                "the span sits on a `fn`",
                "fn",
                coloured.substring(coloured.getSpanStart(it), coloured.getSpanEnd(it)),
            )
        }
        assertEquals(0, gate.inlineCalls.get())
    }

    @Test
    fun `one release colours two pieces shown, from one tokenise of the whole block`() {
        val built = gated()
        val first = built.adapter.bind(built.piece(3))
        val second = built.adapter.bind(built.piece(4))
        assertEquals(emptyList<Any>(), keywordSpans(first.codeView.text as Spanned))
        assertEquals(emptyList<Any>(), keywordSpans(second.codeView.text as Spanned))

        awaitColouring()

        assertEquals(100, keywordSpans(first.codeView.text as Spanned).size)
        assertEquals(100, keywordSpans(second.codeView.text as Spanned).size)
        assertEquals("the whole block was tokenised once", listOf(rust), gate.requested.toList())
        assertEquals(0, gate.inlineCalls.get())
    }

    @Test
    fun `a piece bound after the colours are in takes them at bind, without another tokenise`() {
        val built = gated()
        built.adapter.bind(built.piece(3))
        awaitColouring()

        val late = built.adapter.bind(built.piece(10))

        assertEquals(100, keywordSpans(late.codeView.text as Spanned).size)
        assertEquals("still one tokenise", 1, gate.requested.size)
    }

    @Test
    fun `a search highlight on a piece is still there after the late colours land`() {
        val colour = 0xFF123456.toInt()
        val built = gated(SearchHighlight(query = "f350", otherColor = colour, focusedColor = colour))
        val holder = built.adapter.bind(built.piece(3))
        fun searchSpans(text: Spanned) = text.getSpans(0, text.length, BackgroundColorSpan::class.java).filter {
            it.backgroundColor ==
                colour
        }
        assertTrue("search highlight before the colours", searchSpans(holder.codeView.text as Spanned).isNotEmpty())

        awaitColouring()

        val text = holder.codeView.text as Spanned
        val at = text.indexOf("f350")
        assertTrue(
            "the search highlight survives, over the match",
            text.getSpans(at, at + 1, BackgroundColorSpan::class.java).any { it.backgroundColor == colour },
        )
        assertEquals("and the colours are there as well", 100, keywordSpans(text).size)
    }

    // --- A colouring that covers the front first paints the front first ---------------------------

    /** The reader's real scope: Main. Its work runs only when the test idles the main looper. */
    private val mainScope = CoroutineScope(Dispatchers.Main)

    /**
     * Calls onReady for the first [FRONT_LINES] lines (every `fn` among them is a keyword), then suspends
     * until released: the colours of the rest are not in yet. Each call is counted, and each step is a
     * permit, so a block coloured twice (a cancelled job, a restart) can be followed.
     */
    private class FrontFirstHighlighter(private val gateFront: Boolean = false) : CodeHighlighter {
        val calls = AtomicInteger()

        /**
         * With [gateFront], onReady is not called until the test releases this. A test that must hold Main
         * while the front is handed over needs it: the first `idle()` keeps draining the main queue until it
         * is empty, so a worker that is quick to post the front to Main lets that same idle() paint it.
         */
        val frontGate = Semaphore(0)

        /** About to hand the front over. */
        val frontOffered = Semaphore(0)

        /** The front was handed over and onReady returned: it has been painted. */
        val frontTaken = Semaphore(0)

        /** Waiting for the rest. */
        val restPending = Semaphore(0)
        private val waiting = CopyOnWriteArrayList<Continuation<Unit>>()

        override fun cachedRuns(scopeName: String, code: String): List<CodeRun>? = null

        override fun tokenize(scopeName: String, code: String, isActive: () -> Boolean): List<CodeRun>? = null

        override suspend fun tokenizeProgressively(
            scopeName: String,
            code: String,
            onReady: suspend (runsSoFar: List<CodeRun>, coveredChars: Int) -> Unit,
        ): List<CodeRun> {
            calls.incrementAndGet()
            val front = code.lines().take(FRONT_LINES).sumOf { it.length + 1 }
            frontOffered.release()
            if (gateFront) frontGate.acquire()
            onReady(fnRuns(code).filter { it.end <= front }, front)
            frontTaken.release()
            suspendCoroutine {
                waiting += it
                restPending.release()
            }
            return fnRuns(code).also { onReady(it, code.length) }
        }

        fun release() {
            val all = waiting.toList()
            waiting.clear()
            all.forEach { it.resume(Unit) }
        }

        private fun fnRuns(code: String) = code.lines().dropLast(1).runningFold(0) { at, line -> at + line.length + 1 }
            .dropLast(1).map { CodeRun(it, it + "fn".length, CodeRole.KEYWORD) }
    }

    private fun FencedCodeBlockEntry.Holder.keywords() = keywordSpans(codeView.text as Spanned).size

    /** Idles the main looper until [condition] holds, which is how work posted to Main gets to run. */
    private fun awaitIdle(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(WAIT_S)
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        fail("timed out waiting for: $message")
    }

    private fun awaitJobsEnded() =
        awaitIdle("every colouring job ended") { mainScope.coroutineContext.job.children.none { it.isActive } }

    private fun frontIsIn(front: FrontFirstHighlighter) =
        awaitIdle("the front is painted and the rest is awaited") { front.restPending.availablePermits() >= 1 }

    @Test
    fun `the pieces the first window covers are coloured while the rest of the block is still being coloured`() {
        val front = FrontFirstHighlighter(gateFront = true)
        val built = build(rust, "rust", CodeHighlighting(front, mainScope, prism4j = front))
        val covered = built.adapter.bind(built.piece(1))
        val firstUncovered = built.adapter.bind(built.piece(3))
        val far = built.adapter.bind(built.piece(10))
        assertEquals("plain at bind, the first piece past the window too", 0, firstUncovered.keywords())

        // Main runs the launch (one idle), then is held here: whatever the front needs Main for cannot happen.
        // The highlighter is gated, so the front is not handed over until this idle() has returned: an idle()
        // drains the queue until it is empty, and a worker quick enough would get its front painted by it.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the colouring offered its front", front.frontOffered.tryAcquire(WAIT_S, TimeUnit.SECONDS))
        front.frontGate.release()
        assertFalse(
            "the front is painted on Main: the highlighter's onReady cannot return while Main is not run",
            front.frontTaken.tryAcquire(OFF_MAIN_WAIT_MS, TimeUnit.MILLISECONDS),
        )
        assertEquals("nor has anything painted off Main", 0, covered.keywords())

        frontIsIn(front)
        assertEquals("a piece in the first window is coloured", 100, covered.keywords())
        assertEquals("the first piece past it is still plain", 0, firstUncovered.keywords())
        assertEquals("a far piece is still plain", 0, far.keywords())
        val boundLater = built.adapter.bind(built.piece(2))
        assertEquals("a covered piece bound now paints at bind", 100, boundLater.keywords())
        val boundBeyond = built.adapter.bind(built.piece(3))
        assertEquals("the first piece past the window does not", 0, boundBeyond.keywords())

        front.release()
        awaitJobsEnded()

        listOf(covered, firstUncovered, far, boundLater, boundBeyond).forEach {
            assertEquals("every piece is coloured at the end", 100, it.keywords())
        }
    }

    @Test
    fun `a block left while its front is in is coloured in full when it comes back`() {
        val front = FrontFirstHighlighter()
        val built = build(rust, "rust", CodeHighlighting(front, mainScope, prism4j = front))
        val covered = built.adapter.bind(built.piece(1))
        val far = built.adapter.bind(built.piece(10))
        frontIsIn(front)
        val views = checkNotNull(covered.pieceViews)
        assertNotNull("the front's runs are held while the rest is awaited", views.partialSlices)
        assertEquals(100, covered.keywords())

        // Every piece scrolled away: once Main has had its turn the job is given up.
        covered.resetPiece()
        far.resetPiece()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("the unseen block's job was cancelled and forgotten", views.job)

        val back = built.adapter.bind(built.piece(1))
        val farBack = built.adapter.bind(built.piece(10))
        assertEquals("the front paints at bind from the runs held", 100, back.keywords())
        assertEquals("a piece past it is plain", 0, farBack.keywords())
        awaitIdle("the block is coloured again from the start") { front.restPending.availablePermits() >= 2 }
        assertEquals("the colouring started over", 2, front.calls.get())

        front.release()
        awaitJobsEnded()

        assertEquals(100, back.keywords())
        assertEquals("the far piece is coloured by the restarted job", 100, farBack.keywords())
        assertEquals("a piece that was scrolled away is not painted by the cancelled job", 0, far.keywords())
    }

    private companion object {
        const val FRONT_LINES = 300
        const val JSON_CHARS = 25_000
        const val RUST_LINES = 2_500
        const val WAIT_S = 20L
        const val POLL_MS = 5L
        const val OFF_MAIN_WAIT_MS = 300L
    }
}
