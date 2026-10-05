// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.text.TextPaint
import android.util.TypedValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import io.noties.markwon.core.spans.LinkSpan
import io.noties.markwon.ext.tasklist.TaskListSpan
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Guards **M-219**, the light/dark contrast set agreed with the Mac app (values must match it).
 *
 * Links and task boxes are checked as the RENDERER produces them, not as tokens: before M-219 they
 * took the platform theme's colours, and a test that read a token would not have seen that. On the
 * S24+ the platform gave links `#80CBC4` (1.63:1 on the light page) but gave task boxes and the whole
 * PDF a different, device-dependent accent (about `#475D92`; Robolectric resolves the same here).
 * The PDF is not part of M-219, so the print instance must still take the platform colours.
 *
 * Ratios use the WCAG 2.x formula, (L1 + 0.05) / (L2 + 0.05), with 4.5:1 as the bar.
 */
@RunWith(RobolectricTestRunner::class)
class ContrastFixesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        check(context.theme.resolveAttribute(attr, value, true)) { "attr $attr not in the app theme" }
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }

    private fun rendered(scheme: PilcrowColorScheme, markdown: String): Spanned =
        buildPilcrowMarkwon(context, scheme).toMarkdown(markdown)

    /** The colour a reader TextView paints a link in: its paint starts with the platform link colour. */
    private fun linkColour(scheme: PilcrowColorScheme): Int {
        val text = rendered(scheme, "[a link](https://example.org)")
        val span = text.getSpans(0, text.length, LinkSpan::class.java).single()
        val paint = TextPaint().apply { linkColor = themeColor(android.R.attr.textColorLink) }
        span.updateDrawState(paint)
        return paint.color
    }

    /** Fill, outline and tick of the task-list box, read from Markwon 4.6.2's drawable. */
    private fun taskBox(scheme: PilcrowColorScheme): Triple<Int, Int, Int> {
        val text = rendered(scheme, "- [x] done")
        return boxColours(text.getSpans(0, text.length, TaskListSpan::class.java).single())
    }

    private fun boxColours(span: TaskListSpan): Triple<Int, Int, Int> {
        val drawable = TaskListSpan::class.java.getDeclaredField("drawable").apply { isAccessible = true }.get(span)
        fun field(name: String) = drawable.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(drawable)
        val tick = (field("checkMarkPaint") as android.graphics.Paint).color
        return Triple(field("checkedFillColor") as Int, field("normalOutlineColor") as Int, tick)
    }

    private fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    }

    private fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun hex(s: String): Int = android.graphics.Color.parseColor(s)

    @Test
    fun `light links are the agreed teal and read on the page`() {
        val link = linkColour(LightColorScheme)
        assertEquals("light link colour (agreed with the Mac app)", hex("#00796B"), link)
        assertTrue(ratio(link, LightColorScheme.primaryBackground.toArgb()) >= AA)
    }

    @Test
    fun `dark links keep the teal they had on the phone`() {
        assertEquals(hex("#80CBC4"), linkColour(DarkColorScheme))
    }

    @Test
    fun `light task boxes take the link teal, with a tick that reads on it`() {
        val (fill, outline, tick) = taskBox(LightColorScheme)
        assertEquals(hex("#00796B"), fill)
        assertEquals(hex("#00796B"), outline)
        val page = LightColorScheme.primaryBackground.toArgb()
        assertTrue("an unchecked box's outline reads on the page", ratio(outline, page) >= AA)
        assertTrue("the tick reads on the filled box", ratio(tick, fill) >= AA)
    }

    @Test
    fun `dark task boxes take the link teal, with a tick that reads on it`() {
        val (fill, outline, tick) = taskBox(DarkColorScheme)
        assertEquals(hex("#80CBC4"), fill)
        assertEquals(hex("#80CBC4"), outline)
        val page = DarkColorScheme.primaryBackground.toArgb()
        assertTrue("an unchecked box's outline reads on the page", ratio(outline, page) >= AA)
        assertTrue("the tick reads on the filled box", ratio(tick, fill) >= AA)
    }

    @Test
    fun `the PDF keeps the platform colours for links and task boxes`() {
        val print = buildPrintMarkwon(context)
        val link = print.toMarkdown("[a link](https://example.org)").let { t ->
            val paint = TextPaint().apply { linkColor = themeColor(android.R.attr.textColorLink) }
            t.getSpans(0, t.length, LinkSpan::class.java).single().updateDrawState(paint)
            paint.color
        }
        assertEquals(themeColor(android.R.attr.textColorLink), link)
        val box = print.toMarkdown("- [x] done").let { t -> t.getSpans(0, t.length, TaskListSpan::class.java).single() }
        val (fill, outline, tick) = boxColours(box)
        assertEquals(themeColor(android.R.attr.textColorLink), fill)
        assertEquals(themeColor(android.R.attr.textColorLink), outline)
        assertEquals(themeColor(android.R.attr.colorBackground), tick)
    }

    @Test
    fun `dark footnote markers read on the page and on the code panel`() {
        val marker = DarkColorScheme.footnoteMarker.toArgb()
        assertEquals("dark footnote marker (agreed with the Mac app)", hex("#9E8FDE"), marker)
        assertTrue(ratio(marker, DarkColorScheme.primaryBackground.toArgb()) >= AA)
        assertTrue(ratio(marker, hex("#313131")) >= AA)
        assertEquals("accent is a fill colour and is not part of M-219", Color(0xFF8E7CD6), DarkColorScheme.accent)
    }

    @Test
    fun `light editor links read on the page and on the current-line band`() {
        val theme = JSONObject(context.assets.open("textmate/md-light.json").bufferedReader().use { it.readText() })
        val band = hex(theme.getJSONObject("colors").getString("editor.lineHighlightBackground"))
        val rules = theme.getJSONArray("tokenColors")
        val link = (0 until rules.length()).map { rules.getJSONObject(it) }
            .single { it.optJSONArray("scope")?.toString()?.contains("markup.underline.link") == true }
            .getJSONObject("settings").getString("foreground")
        assertEquals("light editor link (agreed with the Mac app)", "#436B31", link)
        assertTrue(ratio(hex(link), LightColorScheme.primaryBackground.toArgb()) >= AA)
        assertTrue(ratio(hex(link), band) >= AA)
    }

    private companion object {
        const val AA = 4.5
    }
}
