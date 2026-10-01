// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.text.Spanned
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit

/**
 * M-121: equations follow the reading size (Settings → Preview text size) like every other block.
 *
 * Measured on the real drawable JLatexMath renders, attached to a window so the async load
 * actually runs — the size is decided inside that load, so nothing short of it can observe the fix.
 * The same formula at 150% must come out ~1.5× as tall as at 100%; before the fix they were equal.
 */
@RunWith(RobolectricTestRunner::class)
class MathReadingSizeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var renderer: MarkwonRenderer

    @Before
    fun setup() {
        // Robolectric never runs jlatexmath's init provider (see MarkdownScreenshotTest).
        ru.noties.jlatexmath.JLatexMathAndroid.init(context)
        renderer = MarkwonRenderer(context).also { it.awaitFontPreWarm() }
    }

    private fun renderedMathHeight(
        scale: Float,
        markdown: String,
        instance: (Float) -> io.noties.markwon.Markwon = { renderer.markwonFor(DarkColorScheme, it) },
    ): Int {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = TextView(activity)
        activity.setContentView(view)
        instance(scale).setMarkdown(view, markdown)
        val text = view.text as Spanned
        val span = text.getSpans(0, text.length, JLatexAsyncDrawableSpan::class.java).single()
        repeat(MAX_PASSES) {
            shadowOf(Looper.getMainLooper()).idle()
            if (span.drawable.hasResult()) return span.drawable.result.bounds.height()
            ForkJoinPool.commonPool().awaitQuiescence(STEP_MS, TimeUnit.MILLISECONDS)
        }
        error("the formula never rendered")
    }

    private fun assertScalesWithReadingSize(markdown: String) {
        val base = renderedMathHeight(1f, markdown)
        val large = renderedMathHeight(1.5f, markdown)
        val ratio = large / base.toDouble()
        assertEquals("150% should render the maths 1.5× as tall ($base → $large px)", 1.5, ratio, 0.08)
    }

    @Test
    fun `inline maths follows the reading size`() = assertScalesWithReadingSize("Area \$\\frac{a}{b}\$ here.")

    @Test
    fun `block maths follows the reading size`() = assertScalesWithReadingSize("\$\$\\int_0^1 x^2 \\, dx\$\$")

    /**
     * The PDF export resolves each formula itself at the reading size, but the plugin's own render of
     * the same formula can land after it and replace it; that render must be at the reading size too.
     */
    @Test
    fun `the export's own maths render follows the reading size`() {
        val markdown = "\$\$\\int_0^1 x^2 \\, dx\$\$"
        val base = renderedMathHeight(1f, markdown) { renderer.printMarkwonFor(it) }
        val large = renderedMathHeight(1.5f, markdown) { renderer.printMarkwonFor(it) }
        assertEquals("150% export maths ($base → $large px)", 1.5, large / base.toDouble(), 0.08)
    }

    /** 100% is the pre-M-121 instance, so nothing at the default size can have changed. */
    @Test
    fun `the default size keeps the original instances, other sizes are cached per theme`() {
        assertSame(renderer.markwon, renderer.markwonFor(DarkColorScheme, 1f))
        assertSame(renderer.markwonFor(LightColorScheme, 1.2f), renderer.markwonFor(LightColorScheme, 1.2f))
        assertNotSame(renderer.markwonFor(DarkColorScheme, 1.2f), renderer.markwonFor(LightColorScheme, 1.2f))
    }

    private companion object {
        const val MAX_PASSES = 50
        const val STEP_MS = 200L
    }
}
