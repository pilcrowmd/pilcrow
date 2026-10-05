// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathAndroid
import java.util.concurrent.atomic.AtomicInteger

/**
 * M-260: a formula that fails to parse shows its whole source, wrapped onto as many lines as it
 * needs, instead of one line cut off at the right edge (issue #9).
 *
 * Asserted on the MEASURED layout at a phone-ish width: every line must fit the view. Before the
 * fix the source was drawn by the plugin's image span, which cannot break, so the layout held one
 * line wider than the view; deleting the fallback plugin makes [assertFullyReadable] fail.
 *
 * The failing formula uses a command JLaTeXMath will never know, so the test does not start
 * rendering the formula if Greek in `\text{}` is fixed later (M-197).
 *
 * NATIVE graphics: legacy Robolectric text layout never breaks a line (see FencedCodeBlockEntryWrapTest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MathSourceFallbackTest {

    private lateinit var context: Context
    private lateinit var markwon: Markwon

    private val failing = "\\pilcrowUnknownCommand{x} + " + (1..40).joinToString(" + ") { "a_{$it}" }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric does not run the library's self-init ContentProvider (see PdfExporterTest).
        JLatexMathAndroid.init(context)
        markwon = buildPilcrowMarkwon(context)
    }

    private fun laidOut(markdown: String): TextView {
        val view = TextView(context)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
        markwon.setMarkdown(view, markdown)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    private fun assertFullyReadable(view: TextView) {
        val layout = view.layout
        assertTrue("a source longer than the view must wrap", layout.lineCount > 1)
        val available = WIDTH_PX - view.totalPaddingLeft - view.totalPaddingRight
        for (line in 0 until layout.lineCount) {
            assertTrue(
                "line $line is ${layout.getLineMax(line)} px wide; the view shows $available px",
                layout.getLineMax(line) <= available,
            )
        }
        assertTrue("the whole source is in the text", view.text.toString().contains(failing))
    }

    @Test
    fun failingDisplayFormulaWrapsInsteadOfRunningOffTheEdge() {
        val view = laidOut("\$\$\n$failing\n\$\$")
        assertFullyReadable(view)
        assertTrue("no image span is left on it", latexSpans(view.text).isEmpty())
    }

    @Test
    fun failingInlineFormulaWrapsWithItsParagraph() {
        val view = laidOut("Before \$$failing\$ after.")
        assertFullyReadable(view)
        assertEquals("Before $failing after.", view.text.toString().trim())
    }

    /** The fallback must not catch formulas that parse: they still get the plugin's image span. */
    @Test
    fun formulaThatParsesKeepsItsImageSpan() {
        val view = laidOut("Before \$\\frac{a}{b}\$ after.")
        assertEquals(1, latexSpans(view.text).size)
        assertTrue(sourceSpans(view.text).isEmpty())
    }

    /** The search use case treats the source as maths, so the reader must not match inside it. */
    @Test
    fun searchSkipsTheSourceOfAFailedFormula() {
        val view = laidOut("pilcrowUnknownCommand \$$failing\$")
        assertEquals(1, sourceSpans(view.text).size)
        assertEquals(listOf(0), searchableMatchOffsets(view.text, "pilcrowUnknownCommand"))
    }

    /**
     * NEW-33a: the check runs on the main thread at every bind, so a formula seen to parse is not
     * parsed again when its block is bound again. The formula is unique to this test, so no other
     * test can have cached it; deleting the cache lookup makes the second render parse it again.
     */
    @Test
    fun aFormulaThatParsesIsCheckedOnceAcrossRebinds() {
        val latex = "\\frac{p}{q} + ${System.nanoTime()}"
        val parses = parsesOf(latex) {
            markwon.render(markwon.parse("\$$latex\$"))
            markwon.render(markwon.parse("\$$latex\$"))
        }
        assertEquals("parsed on the first render only", 1, parses)
    }

    /**
     * A failure is checked again each time: a `\newcommand` defined in another formula can make it
     * parse later, so it must not be stuck as a failure (NEW-33a).
     */
    @Test
    fun aFormulaThatFailsIsCheckedAgainOnEachRebind() {
        val parses = parsesOf(failing) {
            markwon.render(markwon.parse("\$$failing\$"))
            markwon.render(markwon.parse("\$$failing\$"))
        }
        assertEquals(2, parses)
    }

    /** How many times the check parsed [latex] while [block] ran; other formulas are ignored. */
    private fun parsesOf(latex: String, block: () -> Unit): Int {
        val count = AtomicInteger()
        MathSourceFallbackPlugin.onParse = { if (it == latex) count.incrementAndGet() }
        try {
            block()
        } finally {
            MathSourceFallbackPlugin.onParse = null
        }
        return count.get()
    }

    private fun latexSpans(text: CharSequence) =
        (text as Spanned).getSpans(0, text.length, JLatexAsyncDrawableSpan::class.java).toList()

    private fun sourceSpans(text: CharSequence) =
        (text as Spanned).getSpans(0, text.length, MathSourceSpan::class.java).toList()

    private companion object {
        const val WIDTH_PX = 600
        const val TEXT_SIZE_SP = 17f
    }
}
