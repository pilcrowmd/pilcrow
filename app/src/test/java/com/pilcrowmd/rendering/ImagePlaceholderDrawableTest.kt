// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.DisplayMetrics
import android.util.TypedValue
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.theme.DarkColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/** M-93: the placeholder is laid out in dp and sp, so the whole box — label included — follows density. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagePlaceholderDrawableTest {

    private fun metrics(density: Float) = DisplayMetrics().apply {
        this.density = density
        @Suppress("DEPRECATION")
        scaledDensity = density
    }

    private fun placeholder(density: Float, scale: Float = 1f, alt: String = "Hut at dusk") =
        ImagePlaceholderDrawable(alt, DarkColorScheme, metrics(density), scale, maxWidthPx = 100_000)

    @Test
    fun `the label grows with screen density like the box around it`() {
        val one = placeholder(density = 1f).intrinsicWidth
        val three = placeholder(density = 3f).intrinsicWidth

        // Padding, glyph and gap are 3x; a label drawn at a density-blind size would leave this short.
        assertEquals(3f * one, three.toFloat(), 3f)
    }

    @Test
    fun `the label grows with the reading size`() {
        assertTrue(placeholder(density = 2f, scale = 1.6f).intrinsicWidth > placeholder(density = 2f).intrinsicWidth)
    }

    @Test
    fun `at Android's largest font size the label is as big as body text set in the same sp`() {
        RuntimeEnvironment.setFontScale(LARGEST_FONT_SCALE)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metrics = context.resources.displayMetrics
        val body = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImagePlaceholderDrawable.LABEL_SP)
        }
        val atDp = ImagePlaceholderDrawable.LABEL_SP * metrics.density
        // The setting really took: body text is well past its 1.0 size, so a label left in dp differs.
        assertTrue("body ${body.textSize} vs dp $atDp", body.textSize > atDp * 1.5f)

        val drawable = ImagePlaceholderDrawable(
            "Hut",
            DarkColorScheme,
            metrics,
            1f,
            maxWidthPx = 100_000,
            actionLabel = ImagePlaceholderDrawable.TAP_TO_SHOW,
        )

        assertEquals(body.textSize, drawable.labelSizePx, 0.01f)
        // The box grows with its label, so the bigger text still fits inside it.
        assertTrue(drawable.intrinsicHeight > body.lineHeight)
    }

    @Test
    fun `a short label is drawn whole at its own width when the sizes are fractional`() {
        // A 450 dpi phone at font size 0.9: padding and the sp sizes fall between pixels.
        val phone = DisplayMetrics().apply {
            density = PHONE_DENSITY
            @Suppress("DEPRECATION")
            scaledDensity = PHONE_DENSITY * PHONE_FONT_SCALE
        }
        val drawable = ImagePlaceholderDrawable(
            "Hut",
            DarkColorScheme,
            phone,
            1f,
            maxWidthPx = 100_000,
            actionLabel = ImagePlaceholderDrawable.TAP_TO_SHOW,
        )

        assertEquals("Hut" to " · Tap to show", drawable.textFor(drawable.intrinsicWidth))
    }

    @Test
    fun `nothing is drawn past the box when even the action does not fit`() {
        // " · Tap to show" alone is wider than this box: 2.0 font size at the 1.6 reading size.
        val narrow = 240
        val drawable = ImagePlaceholderDrawable(
            "Hut",
            DarkColorScheme,
            DisplayMetrics().apply {
                density = 2f
                @Suppress("DEPRECATION")
                scaledDensity = 4f
            },
            1.6f,
            maxWidthPx = narrow,
            actionLabel = ImagePlaceholderDrawable.TAP_TO_SHOW,
        )
        assertTrue(drawable.intrinsicWidth <= narrow)
        val bitmap = Bitmap.createBitmap(narrow * 3, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))

        val inkPastBox = (drawable.bounds.right until bitmap.width).sumOf { x ->
            (0 until bitmap.height).count { y -> Color.alpha(bitmap.getPixel(x, y)) != 0 }
        }
        assertEquals(0, inkPastBox)
    }

    @Test
    fun `a long label is ellipsized to the width it is given`() {
        val drawable = ImagePlaceholderDrawable("x".repeat(500), DarkColorScheme, metrics(2f), 1f, maxWidthPx = 300)

        assertTrue(drawable.intrinsicWidth <= 300)
        assertTrue(drawable.label.endsWith("…"))
    }

    private companion object {
        /** The top of Android's font-size setting since Android 14. */
        const val LARGEST_FONT_SCALE = 2.0f

        /** A 450 dpi phone at font size 0.9. */
        const val PHONE_DENSITY = 450f / 160f
        const val PHONE_FONT_SCALE = 0.9f
    }
}
