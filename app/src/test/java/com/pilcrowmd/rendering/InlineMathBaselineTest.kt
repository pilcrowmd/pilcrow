// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.text.Spanned
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.PreviewLineHeightMultiplier
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathDrawable
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * S8-A42: an inline formula's own baseline sits on the text baseline of its line.
 *
 * The offset is read from the real draw: the span is drawn onto a recording canvas with the line's
 * top / baseline / bottom from the TextView's own layout, and the formula's baseline is the drawable's
 * bottom minus its TeX depth. Native graphics, so the layout honours the reader's line spacing like the
 * device does (the legacy shadow ignores it). Positive = the formula sits lower than the text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineMathBaselineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var renderer: MarkwonRenderer

    @Before
    fun setup() {
        // Robolectric never runs jlatexmath's init provider (see MarkdownScreenshotTest).
        ru.noties.jlatexmath.JLatexMathAndroid.init(context)
        renderer = warmedMarkwonRenderer(context)
    }

    /** The reader's own prose TextView set-up, one formula in a line of words, waited on until it has drawn. */
    private fun renderedFormula(latex: String, scale: Float): Triple<TextView, Spanned, JLatexAsyncDrawableSpan> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = TextView(activity)
        // As the reader sets up its prose TextView (ProseBlockEntry): size, and the line spacing that
        // adds room below the baseline, which is what pushed centred formulas down.
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, PilcrowTypography.PROSE_BODY_FONT_SIZE_SP * scale)
        view.setLineSpacing(0f, PreviewLineHeightMultiplier)
        activity.setContentView(view)
        renderer.markwonFor(DarkColorScheme, scale).setMarkdown(view, "Some words \$$latex\$ and more words")
        val text = view.text as Spanned
        val span = text.getSpans(0, text.length, JLatexAsyncDrawableSpan::class.java).single()
        repeat(MAX_PASSES) {
            shadowOf(Looper.getMainLooper()).idle()
            if (span.drawable.hasResult()) return Triple(view, text, span)
            ForkJoinPool.commonPool().awaitQuiescence(STEP_MS, TimeUnit.MILLISECONDS)
        }
        error("the formula never rendered")
    }

    /** Pixels the formula baseline sits below the text baseline of its line. */
    private fun baselineOffset(latex: String, scale: Float): Float {
        val (view, text, span) = renderedFormula(latex, scale)
        return drawnOffset(view, text, span)
    }

    private fun drawnOffset(view: TextView, text: Spanned, span: JLatexAsyncDrawableSpan): Float {
        view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val layout = view.layout
        val start = text.getSpanStart(span)
        val line = layout.getLineForOffset(start)
        var translateY: Float? = null
        val canvas =
            object : Canvas(Bitmap.createBitmap(WIDTH_PX, 400, Bitmap.Config.ARGB_8888)) {
                override fun translate(dx: Float, dy: Float) {
                    // The span's own translate comes first; JLatexMath's later ones are inside the image.
                    if (translateY == null) translateY = dy
                    super.translate(dx, dy)
                }
            }
        val baseline = layout.getLineBaseline(line)
        span.draw(
            canvas,
            text,
            start,
            text.getSpanEnd(span),
            layout.getPrimaryHorizontal(start),
            layout.getLineTop(line),
            baseline,
            layout.getLineBottom(line),
            view.paint,
        )
        val bounds = span.drawable.result.bounds
        val depth = (span.drawable.result as JLatexMathDrawable).icon().iconDepth
        return translateY!! + bounds.bottom - depth - baseline
    }

    private fun assertOnBaseline(latex: String) {
        val offsets = listOf(1f, 1.6f).associateWith { baselineOffset(latex, it) }
        offsets.forEach { (scale, offset) ->
            assertTrue("\$$latex\$ at $scale× sits $offset px off the text baseline", abs(offset) <= 1f)
        }
    }

    @Test fun `plain letter`() = assertOnBaseline("a")

    @Test fun `superscript`() = assertOnBaseline("x^2")

    @Test fun `subscript`() = assertOnBaseline("x_1")

    @Test fun `sub and superscript`() = assertOnBaseline("x^2_{567}")

    @Test fun `fraction`() = assertOnBaseline("\\frac{a}{b}")

    @Test fun `square root`() = assertOnBaseline("\\sqrt{2}")

    @Test fun `sum with limits`() = assertOnBaseline("\\sum_{i=1}^n")

    /**
     * The other half of the fix: the line box. The library's span reserved half the image height on each
     * side of the baseline; the line must instead make room for the image above the baseline and its
     * TeX depth below it, or a tall formula overlaps the line above.
     */
    @Test
    fun `the line box reserves the height above the baseline and the depth below it`() {
        val (view, text, span) = renderedFormula("x_1", 1f)
        val fm = Paint.FontMetricsInt()
        span.getSize(view.paint, text, text.getSpanStart(span), text.getSpanEnd(span), fm)
        val height = span.drawable.result.bounds.bottom
        val depth = (span.drawable.result as JLatexMathDrawable).icon().iconDepth
        assertTrue("a subscript has depth to reserve", depth > 0)
        assertEquals("below the baseline", depth, fm.descent)
        assertEquals("above the baseline", -(height - depth), fm.ascent)
        assertEquals(fm.ascent, fm.top)
        assertEquals(fm.descent, fm.bottom)
    }

    private companion object {
        const val MAX_PASSES = 50
        const val STEP_MS = 200L
        const val WIDTH_PX = 1000
    }
}
