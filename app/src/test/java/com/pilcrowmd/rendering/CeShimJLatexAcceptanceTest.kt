// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * Pins the library contract the shim rests on: every LaTeX string [CeMacroShim] can emit —
 * tier translations (\mathrm, subscripts, charges, \cdot, arrows, \tfrac, states) AND the F1
 * fallback (\text{…} with the full escape table, including the spike-validated `\^{}`, `\_`,
 * `\~{}`, `{\backslash}`) — must parse and render in JLaTeXMath without tripping the error
 * handler. Guards against a jlatexmath/ext-latex upgrade silently breaking a target macro.
 *
 * Standalone symbols are additionally checked for INK: parsing alone is not enough — the library
 * accepted \rightleftharpoons but drew a blank glyph (caught on-device, S24+ UAT 2026-08-20),
 * which is why `<=>` maps to \rightleftarrows. The ink assertion makes that class of bug
 * impossible to reintroduce.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CeShimJLatexAcceptanceTest {

    @Before
    fun setup() {
        // Robolectric does not run the library's self-init ContentProvider (see PdfExporterTest).
        ru.noties.jlatexmath.JLatexMathAndroid.init(ApplicationProvider.getApplicationContext())
    }

    private fun assertRenders(input: String) {
        val translated = CeMacroShim.translate(input)
        val drawable = JLatexMathDrawable.builder(translated).textSize(40f).build()
        assertTrue(
            "translate(\"$input\") = \"$translated\" must render to a non-empty drawable",
            drawable.intrinsicWidth > 0,
        )
    }

    @Test fun tier1Trivial() = assertRenders("k[\\ce{A}]^2")

    @Test fun tier2Formula() = assertRenders("\\ce{H2O}")

    @Test fun tier2Charges() = assertRenders("\\ce{Na+} \\ce{SO4^2-} \\ce{Fe^{3+}} \\ce{e-}")

    @Test fun tier2ParensAndHydrate() = assertRenders("\\ce{Ca(OH)2} \\ce{CuSO4*5H2O}")

    @Test fun tier3Reaction() = assertRenders("\\ce{2H2 + O2 -> 2H2O}")

    @Test fun tier3ArrowsStatesMarkers() =
        assertRenders("\\ce{A <=> B} \\ce{A <-> B} \\ce{A <- B} \\ce{AgCl(s) v} \\ce{H2 ^} \\ce{1/2O2}")

    @Test fun tier3ChargeThenState() = assertRenders("\\ce{Na+(aq)} \\ce{Fe^{3+}(aq)}")

    @Test fun fallbackBondAndAnnotation() = assertRenders("\\ce{C-C} \\ce{A ->[H2O] B}")

    @Test fun fallbackFullEscapeTable() =
        assertRenders("\\ce{^{227}_{90}Th} \\ce{a~b} \\ce{a%b} \\ce{a\$b} \\ce{a&b} \\ce{a#b} \\ce{\\ce{H2O}}")

    @Test fun confirmedDefectRateLawShape() = assertRenders("\\ln[\\ce{A}]_t = -kt + \\ln[\\ce{A}]_0")

    /** Alpha-channel ink count of a lone rendered latex string. */
    private fun inkPixels(latex: String): Int {
        val drawable = JLatexMathDrawable.builder(latex).textSize(60f).color(Color.BLACK).build()
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.count { (it ushr 24) > INK_ALPHA_THRESHOLD }
    }

    @Test
    fun everyStandaloneSymbolDrawsInk() {
        // Every symbol the shim emits WITHOUT adjacent letters (arrows, markers, spacing-safe
        // forms). Kept in sync with CeMacroShim's ARROWS map and marker/hydrate outputs — the
        // exact strings are pinned by CeMacroShimTest, this guards that each actually DRAWS.
        val standaloneSymbols = listOf(
            "\\longrightarrow",
            "\\longleftarrow",
            "\\rightleftarrows",
            "\\longleftrightarrow",
            "\\uparrow",
            "\\downarrow",
            "\\cdot",
        )
        standaloneSymbols.forEach { symbol ->
            assertTrue("\"$symbol\" parses but draws NO ink (blank glyph)", inkPixels(symbol) > 0)
        }
    }

    private companion object {
        const val INK_ALPHA_THRESHOLD = 32
    }
}
