// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.ThresholdValidator
import com.github.takahirom.roborazzi.captureRoboImage
import com.pilcrowmd.rendering.buildPrintMarkwon
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S8-A88: `==marked==` in the PDF, in both colour options. Each page is checked twice: a golden, and a
 * count of the pixels that are exactly the scheme's highlight colour, which a missing highlight cannot
 * reach (a few marked words move too few pixels for the golden's tolerance alone).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xhdpi")
class PdfHighlightGoldenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun draw(palette: PdfPalette, golden: String): Int {
        val markwon = buildPrintMarkwon(context, codeColors = palette.code)
        val builder = PdfContentLayoutBuilder(context)
        builder.palette = palette
        val nodes = parseTopLevelBlocks(markwon, SAMPLE)
        val bounds = builder.measureBlockBounds(markwon, nodes, PAGE_WIDTH_PX, 1f)
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH_PX, bounds.last().second, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(palette.page.primaryBackground.toArgb())
        builder.forEachBlock(PdfContentLayoutBuilder.ParseSpec(markwon, nodes, PAGE_WIDTH_PX, 1f)) { i, _, view ->
            canvas.save()
            canvas.translate(0f, bounds[i].first.toFloat())
            view.draw(canvas)
            canvas.restore()
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.captureRoboImage(
            "src/test/screenshots/$golden.png",
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(
                    imageComparator = SimpleImageComparator(maxDistance = MAX_PIXEL_DISTANCE),
                    resultValidator = ThresholdValidator(0f),
                ),
            ),
        )
        val colour = palette.page.highlight.toArgb()
        return pixels.count { it == colour }
    }

    @Test
    fun theLightForPrintingExportShowsTheHighlight() {
        assertTrue(draw(PdfPalette.PRINT, "pdf_highlight_print") > MIN_HIGHLIGHT_PIXELS)
    }

    @Test
    fun theMatchAppThemeExportShowsTheHighlight() {
        val palette = PdfPalette.of(com.pilcrowmd.ui.theme.DarkColorScheme)
        assertTrue(draw(palette, "pdf_highlight_dark") > MIN_HIGHLIGHT_PIXELS)
    }

    private companion object {
        const val PAGE_WIDTH_PX = 780
        const val MAX_PIXEL_DISTANCE = 0.01f
        const val MIN_HIGHLIGHT_PIXELS = 2000

        val SAMPLE = """
            # Marked ==heading==

            A paragraph with ==a whole marked phrase that runs on across more than one line of the page== and
            then ==**bold** and [a link](https://example.org)== inside the marks, then a == b left alone.
        """.trimIndent()
    }
}
