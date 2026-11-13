// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.model.PdfColors
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.rendering.buildPrintMarkwon
import com.pilcrowmd.ui.theme.AmoledColorScheme
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.PrintCodeColorScheme
import com.pilcrowmd.ui.theme.PrintColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * S8-A82: "Match app theme" leaves nothing white, black or print-coloured by mistake. The page is drawn
 * in a scheme none of whose tokens is one of those colours, so any such pixel can only be a hard-coded
 * colour, or the print scheme left on the themed path.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xhdpi")
class PdfThemedColoursTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val sentinel = DarkColorScheme.copy(
        primaryBackground = Color(0xFF203040),
        secondarySurface = Color(0xFF283848),
        primaryText = Color(0xFFE0D0C0),
        strongText = Color(0xFFE8D8C8),
        codeBlockBorder = Color(0xFF506070), // Dark's own border is the same grey as Print's
        // Not grey: grey text antialiased on Dark's grey panels lands on Print's greys by chance.
        codeBlockBg = Color(0xFF182838),
        inlineCodeBg = Color(0xFF243444),
    )

    private fun draw(palette: PdfPalette): IntArray {
        val markwon = buildPrintMarkwon(context, codeColors = palette.code)
        val builder = PdfContentLayoutBuilder(context)
        builder.palette = palette
        val nodes = parseTopLevelBlocks(markwon, PDF_COLOUR_SAMPLE)
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
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }

    /** The surfaces of the print schemes: flat areas, which antialiased text cannot produce by chance. */
    private val forbidden = mapOf(
        "white" to Color.White,
        "black" to Color.Black,
        "print surface" to PrintColorScheme.secondarySurface,
        "print inline chip" to PrintColorScheme.inlineCodeBg,
        "print inline code" to PrintColorScheme.inlineCodeText,
        "print code panel" to PrintCodeColorScheme.codeBlockBg,
        "print code chip" to PrintCodeColorScheme.inlineCodeBg,
        "print code text" to PrintCodeColorScheme.editorText,
        "print text" to PrintColorScheme.primaryText,
        "print border" to PrintColorScheme.codeBlockBorder,
    )

    private fun offenders(pixels: IntArray): Map<String, Int> {
        val counts = pixels.toList().groupingBy { it }.eachCount()
        return forbidden.mapValues { (_, colour) -> counts[colour.toArgb()] ?: 0 }.filterValues { it > 0 }
    }

    @Test
    fun nothingHardCodedIsLeftOnTheThemedPath() {
        val found = offenders(draw(PdfPalette.of(sentinel)))
        assertTrue("left on the themed path: $found", found.isEmpty())
    }

    /** The check above can fail: the print palette, drawn the same way, is full of those colours. */
    @Test
    fun control_thePrintPaletteTripsTheSameCheck() {
        val found = offenders(draw(PdfPalette.PRINT))
        assertTrue("found only $found", "print code panel" in found && "white" in found)
    }

    /** The page fill and footer are drawn by the exporter, which needs a real PdfDocument: pin the source. */
    @Test
    fun theExporterAndBuilderNameNoFixedColour() {
        val dir = File("src/main/java/com/pilcrowmd/export")
        listOf("PdfExporter.kt", "PdfContentLayoutBuilder.kt").forEach { name ->
            val text = File(dir, name).readText()
            listOf("Color.WHITE", "Color.BLACK", "PrintColorScheme", "PrintCodeColorScheme").forEach {
                assertTrue("$name names $it", it !in text)
            }
        }
    }

    @Test
    fun theDefaultIsTheUnchangedPrintPaletteWhateverTheTheme() {
        ThemeMode.entries.forEach { theme ->
            val palette = PdfPalette.of(PdfColors.LIGHT_FOR_PRINTING, theme)
            assertSame(PrintColorScheme, palette.page)
            assertSame(PrintCodeColorScheme, palette.code)
        }
        assertEquals(Color.White, PdfPalette.PRINT.page.primaryBackground)
        assertEquals(Color.Black, PdfPalette.PRINT.pageNumber)
    }

    @Test
    fun matchAppThemeTakesTheThemeAtExportTime() {
        mapOf(
            ThemeMode.DARK to DarkColorScheme,
            ThemeMode.AMOLED to AmoledColorScheme,
            ThemeMode.LIGHT to LightColorScheme,
        ).forEach { (theme, scheme) ->
            val palette = PdfPalette.of(PdfColors.MATCH_APP_THEME, theme)
            assertSame(scheme, palette.page)
            assertSame(scheme, palette.code)
            assertEquals(scheme.primaryText, palette.pageNumber)
        }
    }

    private companion object {
        const val PAGE_WIDTH_PX = 780
    }
}
