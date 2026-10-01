// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.export.PdfContentLayoutBuilder
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.scilab.forge.jlatexmath.ParseException
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit

/**
 * A recursive user macro must fail to parse quickly — and so show its source, like any formula
 * that fails — instead of expanding for ever ([MacroExpansionLimit]).
 *
 * Every build runs on a daemon worker with a deadline, so a regression fails the test instead of
 * hanging the suite (a spinning JLaTeXMath thread cannot be stopped; being a daemon, it cannot
 * keep the JVM alive either).
 *
 * JLaTeXMath's macro table is process-wide and shared by every test in this JVM, so each test
 * defines macros under names no other test uses.
 */
@RunWith(RobolectricTestRunner::class)
class MacroRecursionGuardTest {

    private lateinit var context: Context
    private lateinit var markwon: Markwon

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric does not run the library's self-init ContentProvider (see PdfExporterTest).
        JLatexMathAndroid.init(context)
        // The production plugin chain; configuring it is what installs the expansion limit.
        markwon = buildPilcrowMarkwon(context)
    }

    /** Runs [work] on a daemon worker; null when it is still running at the deadline. */
    private fun <T> onWorker(work: () -> T): T? {
        val worker = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        val future = worker.submit(Callable { work() })
        worker.shutdown()
        return if (worker.awaitTermination(DEADLINE_MS, TimeUnit.MILLISECONDS)) future.get() else null
    }

    /** The build the on-screen loader and the PDF export both perform. */
    private fun build(latex: String): Result<JLatexMathDrawable>? =
        onWorker { runCatching { JLatexMathDrawable.builder(latex).textSize(TEXT_SIZE_PX).build() } }

    private fun assertFailsFast(latex: String) {
        val result = build(latex)
        assertNotNull("still expanding after $DEADLINE_MS ms: $latex", result)
        // The limit's own exception, not any failure: a StackOverflowError also fails the build, so
        // "failed" alone would pass even if the count never reached its limit.
        val error = result!!.exceptionOrNull()
        assertTrue(
            "must be stopped by the expansion limit, not by something else: $error ($latex)",
            error is ParseException && error.message.orEmpty().contains("macro expansion limit"),
        )
    }

    @Test
    fun directRecursionFailsFast() = assertFailsFast("\\newcommand{\\rgDirect}{\\rgDirect}\\rgDirect")

    @Test
    fun indirectRecursionFailsFast() =
        assertFailsFast("\\newcommand{\\rgPing}{\\rgPong}\\newcommand{\\rgPong}{\\rgPing}\\rgPing")

    @Test
    fun renewcommandRecursionFailsFast() =
        assertFailsFast("\\newcommand{\\rgRenew}{x}\\renewcommand{\\rgRenew}{\\rgRenew}\\rgRenew")

    /** No cycle in any definition: the recursion runs through the argument. */
    @Test
    fun recursionThroughAnArgumentFailsFast() = assertFailsFast("\\newcommand{\\rgTwice}[1]{#1#1}\\rgTwice\\rgTwice")

    /** The recursion sits inside a box argument, which JLaTeXMath parses with a nested parser. */
    @Test
    fun recursionInsideABoxArgumentFailsFast() = assertFailsFast("\\newcommand{\\rgBox}{\\mbox{\\rgBox}}\\rgBox")

    @Test
    fun recursionInsideTextFailsFast() = assertFailsFast("\\newcommand{\\rgText}{\\text{\\rgText}}\\rgText")

    @Test
    fun recursionInsideAFractionFailsFast() = assertFailsFast("\\newcommand{\\rgFrac}{\\frac{\\rgFrac}{2}}\\rgFrac")

    /** The macro table outlives the formula: a definition made in one recurses when another uses it. */
    @Test
    fun recursiveMacroDefinedInAnEarlierFormulaFailsFast() {
        assertNotNull("defining alone must finish", build("\\newcommand{\\rgLater}{\\rgLater}x"))
        assertFailsFast("y = \\rgLater")
    }

    @Test
    fun nonRecursiveMacroStillRenders() {
        val result = build("\\newcommand{\\rgReals}{\\mathbb{R}}x \\in \\rgReals")
        assertNotNull(result)
        assertTrue("a plain macro must render: ${result!!.exceptionOrNull()}", result.getOrThrow().intrinsicWidth > 0)
    }

    /** The limit counts per formula and sits far above hand-written use. */
    @Test
    fun heavyButFiniteMacroUseStillRenders() {
        val latex = "\\newcommand{\\rgVec}{v}" + "\\rgVec+".repeat(HEAVY_USES) + "0"
        val result = build(latex)
        assertNotNull(result)
        assertTrue("finite use must render: ${result!!.exceptionOrNull()}", result.getOrThrow().intrinsicWidth > 0)
    }

    /**
     * Loader threads are reused, so the count must restart with each formula: four finite formulas
     * built one after another on ONE thread total more than the limit, yet each must render.
     */
    @Test
    fun countRestartsForEachFormulaOnTheSameThread() {
        val latex = "\\newcommand{\\rgSame}{s}" + "\\rgSame+".repeat(HEAVY_USES) + "0"
        val later = "\\rgSame+".repeat(HEAVY_USES) + "0"
        val results = onWorker {
            listOf(latex, later, later, later).map { src ->
                runCatching { JLatexMathDrawable.builder(src).textSize(TEXT_SIZE_PX).build() }
            }
        }
        assertNotNull(results)
        results!!.forEachIndexed { i, r -> assertTrue("formula $i must render: ${r.exceptionOrNull()}", r.isSuccess) }
    }

    /**
     * On screen: the math loader builds on the common pool. It must settle, leave the formula
     * without a bitmap (so its source is drawn), and leave the text around it intact.
     */
    private fun assertOnScreenShowsSource(markdown: String, source: String) {
        val textView = TextView(context)
        markwon.setMarkdown(textView, markdown)
        val spans = latexSpans(textView)
        assertEquals("one formula expected in: $markdown", 1, spans.size)
        assertTrue("math loader still busy after $DEADLINE_MS ms", commonPoolGoesIdle())
        assertFalse("a recursive formula must not get a bitmap", spans.single().drawable.hasResult())
        assertEquals("Before $source after.", textView.text.toString().trim())
    }

    @Test
    fun onScreenInlineRecursionShowsSourceInItsParagraph() {
        val source = "\\newcommand{\\rgScreen}{\\rgScreen}\\rgScreen"
        assertOnScreenShowsSource("Before \$$source\$ after.", source)
    }

    @Test
    fun onScreenDisplayRecursionShowsSourceInItsParagraph() {
        val source = "\\newcommand{\\rgDisplay}{\\rgDisplay}\\rgDisplay"
        assertOnScreenShowsSource("Before \$\$$source\$\$ after.", source)
    }

    /** PDF export: the synchronous build must return, the formula keeping its source text. */
    @Test
    fun pdfExportRecursionFinishesAndShowsSource() {
        val source = "\\newcommand{\\rgPdf}{\\rgPdf}\\rgPdf"
        val node = markwon.parse("Before \$$source\$ after.").firstChild!!
        val builder = PdfContentLayoutBuilder(context)
        val view = onWorker {
            builder.inflateMeasuredBlock(markwon, node, LayoutInflater.from(context), PAGE_WIDTH_PX, 1f)
        }
        assertNotNull("PDF block build still running after $DEADLINE_MS ms", view)
        val spans = latexSpans(view!!)
        assertEquals(1, spans.size)
        assertFalse("a recursive formula must not get a bitmap", spans.single().drawable.hasResult())
        assertEquals("Before $source after.", textOf(view).trim())
    }

    /**
     * Whether the common pool, where the math loader builds, has gone idle by the deadline. It only
     * looks: `awaitQuiescence` would make this thread RUN queued loader tasks, so a formula that
     * never finishes would hang the test itself and the deadline would never apply.
     */
    private fun commonPoolGoesIdle(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS)
        while (!ForkJoinPool.commonPool().isQuiescent) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    private fun latexSpans(view: View): List<JLatexAsyncDrawableSpan> = when (view) {
        is ViewGroup -> (0 until view.childCount).flatMap { latexSpans(view.getChildAt(it)) }
        is TextView -> (view.text as? Spanned)
            ?.let { it.getSpans(0, it.length, JLatexAsyncDrawableSpan::class.java).toList() }
            .orEmpty()
        else -> emptyList()
    }

    private fun textOf(view: View): String = when (view) {
        is ViewGroup -> (0 until view.childCount).joinToString("") { textOf(view.getChildAt(it)) }
        is TextView -> view.text.toString()
        else -> ""
    }

    private companion object {
        const val DEADLINE_MS = 5_000L
        const val POLL_MS = 20L
        const val TEXT_SIZE_PX = 40f
        const val PAGE_WIDTH_PX = 1_000
        const val HEAVY_USES = 300
    }
}
