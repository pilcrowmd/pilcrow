// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Fixture suite for [CeMacroShim.translate] — the pure mhchem `\ce{…}` → plain-LaTeX translator.
 * Fixtures mirror the shim's design exactly: tier tables, fallback, the
 * untouched-path identity guarantee (assertSame — same reference, not just equal), and the
 * idempotency / no-`\ce{`-in-output invariants.
 *
 * Grammar summary: Tier 1 trivial species; Tier 2 formulas (subscripts, trailing
 * charges, hydrate `*`, parens); Tier 3-core reactions (arrows, `+`, glued states stripped BEFORE
 * the trailing-charge rule, precipitate/gas markers, integer + fractional coefficients). Anything
 * beyond falls back to upright literal `\text{…}` with the escape table validated by the
 * JLaTeXMath spike (`\^{}`, `\_`, `\~{}`, `\{`, `\}`, `{\backslash}`, `\%`, `\$`, `\&`, `\#`).
 */
class CeMacroShimTest {

    private fun t(latex: String): String = CeMacroShim.translate(latex)

    // --- untouched path: identity (same reference) when no \ce occurrence exists ---

    @Test fun identityPlainEquation() = "E=mc^2".let { assertSame(it, t(it)) }

    @Test fun identityFraction() = "\\frac{a}{b} + \\cdot x".let { assertSame(it, t(it)) }

    @Test fun identityEmptyString() = "".let { assertSame(it, t(it)) }

    @Test fun identityMacroBoundaryCee() = "\\cee{X}".let { assertSame(it, t(it)) }

    @Test fun identityMacroBoundaryCellcolor() = "\\cellcolor{red}".let { assertSame(it, t(it)) }

    @Test fun identityBareCeWithoutBraceGroup() = "a \\ce b".let { assertSame(it, t(it)) }

    @Test fun identityUnbalancedBracesAbortsWholeString() = "\\ce{H2O".let { assertSame(it, t(it)) }

    @Test fun identityEscapedBackslashIsNotAMacro() {
        // `\\ce{…}` is a LaTeX line break followed by literal "ce{…}" — not the \ce macro.
        "\\\\ce{H2O}".let { assertSame(it, t(it)) }
    }

    // --- Tier 1: trivial species ---

    @Test fun t1SingleSpecies() = assertEquals("{\\mathrm{A}}", t("\\ce{A}"))

    @Test fun t1InsideConcentrationBrackets() = assertEquals("k[{\\mathrm{A}}]", t("k[\\ce{A}]"))

    @Test fun t1SpacedOpener() = assertEquals("{\\mathrm{H_{2}O}}", t("\\ce {H2O}"))

    // --- Tier 2: formulas ---

    @Test fun t2Water() = assertEquals("{\\mathrm{H_{2}O}}", t("\\ce{H2O}"))

    @Test fun t2LeadingCoefficient() = assertEquals("{2\\mathrm{H_{2}O}}", t("\\ce{2H2O}"))

    @Test fun t2CationBareCharge() = assertEquals("{\\mathrm{Na^{+}}}", t("\\ce{Na+}"))

    @Test fun t2AnionCaretCharge() = assertEquals("{\\mathrm{SO_{4}^{2-}}}", t("\\ce{SO4^2-}"))

    @Test fun t2BracedCharge() = assertEquals("{\\mathrm{Fe^{3+}}}", t("\\ce{Fe^{3+}}"))

    @Test fun t2ParenGroupSubscript() = assertEquals("{\\mathrm{Ca(OH)_{2}}}", t("\\ce{Ca(OH)2}"))

    @Test fun t2HydrateDot() = assertEquals("{\\mathrm{CuSO_{4}}\\cdot 5\\mathrm{H_{2}O}}", t("\\ce{CuSO4*5H2O}"))

    @Test fun t2Electron() = assertEquals("{\\mathrm{e^{-}}}", t("\\ce{e-}"))

    // --- Tier 3-core: reaction syntax ---

    @Test fun t3FullReaction() = assertEquals(
        "{2\\mathrm{H_{2}} + \\mathrm{O_{2}} \\longrightarrow 2\\mathrm{H_{2}O}}",
        t("\\ce{2H2 + O2 -> 2H2O}"),
    )

    // \rightleftarrows (⇄), not \rightleftharpoons (⇌): jlatexmath-android parses the harpoon
    // macro but its glyph draws BLANK (device + ink-probe verified). ⇄ keeps the correct
    // forward/reverse direction; the harpoon-vs-arrowhead difference is an accepted deviation.
    @Test fun t3Equilibrium() = assertEquals("{\\mathrm{A} \\rightleftarrows \\mathrm{B}}", t("\\ce{A <=> B}"))

    @Test fun t3ReverseArrow() = assertEquals("{\\mathrm{A} \\longleftarrow \\mathrm{B}}", t("\\ce{A <- B}"))

    @Test fun t3ResonanceArrow() = assertEquals("{\\mathrm{A} \\longleftrightarrow \\mathrm{B}}", t("\\ce{A <-> B}"))

    @Test fun t3StateAndPrecipitate() = assertEquals(
        "{\\mathrm{AgCl}\\,\\mathrm{(s)} \\downarrow}",
        t("\\ce{AgCl(s) v}"),
    )

    @Test fun t3GasMarker() = assertEquals("{\\mathrm{H_{2}} \\uparrow}", t("\\ce{H2 ^}"))

    @Test fun t3FractionCoefficient() = assertEquals("{\\tfrac{1}{2}\\mathrm{O_{2}}}", t("\\ce{1/2O2}"))

    // Deliberate token order: glued state stripped BEFORE the trailing-charge rule,
    // so the `+` is a charge, not a mid-token bond triggering fallback.
    @Test fun t3ChargeThenState() = assertEquals("{\\mathrm{Na^{+}}\\,\\mathrm{(aq)}}", t("\\ce{Na+(aq)}"))

    @Test fun t3BracedChargeThenState() = assertEquals("{\\mathrm{Fe^{3+}}\\,\\mathrm{(aq)}}", t("\\ce{Fe^{3+}(aq)}"))

    // --- fallback (F1): beyond-tier content becomes upright literal text, equation survives ---

    @Test fun fallbackBond() = assertEquals("{\\text{C-C}}", t("\\ce{C-C}"))

    @Test fun fallbackDoubleBond() = assertEquals("{\\text{C=C}}", t("\\ce{C=C}"))

    @Test fun fallbackIsotopeEscapesSpecials() =
        assertEquals("{\\text{\\^{}\\{227\\}\\_\\{90\\}Th}}", t("\\ce{^{227}_{90}Th}"))

    @Test fun fallbackArrowAnnotationPoisonsWholeOccurrence() = assertEquals(
        "{\\text{A ->[H2O] B}}",
        t("\\ce{A ->[H2O] B}"),
    )

    @Test fun fallbackAdversarialNestedCe() {
        val out = t("\\ce{\\ce{H2O}}")
        assertEquals("{\\text{{\\backslash}ce\\{H2O\\}}}", out)
        assertFalse("fallback output must never contain \\ce{", out.contains("\\ce{"))
    }

    @Test fun fallbackRestOfEquationStillTranslates() {
        // one occurrence falls back, the other translates — independent per occurrence
        assertEquals("{\\mathrm{H_{2}O}} + {\\text{C-C}}", t("\\ce{H2O} + \\ce{C-C}"))
    }

    // --- edges ---

    @Test fun emptyArgumentIsDropped() = assertEquals("x  y", t("x \\ce{} y"))

    @Test fun nestedInsideFrac() = assertEquals("\\frac{{\\mathrm{H_{2}O}}}{2}", t("\\frac{\\ce{H2O}}{2}"))

    @Test fun multipleOccurrencesTranslateIndependently() = assertEquals(
        "{\\mathrm{Na^{+}}} and {\\mathrm{Cl^{-}}}",
        t("\\ce{Na+} and \\ce{Cl-}"),
    )

    @Test fun occurrenceAtStringStartAndEnd() = assertEquals("{\\mathrm{A}} x {\\mathrm{B}}", t("\\ce{A} x \\ce{B}"))

    @Test fun argumentWhitespaceIsTrimmed() = assertEquals("{\\mathrm{H_{2}O}}", t("\\ce{ H2O }"))

    // --- invariants across every fixture input above ---

    private val allInputs = listOf(
        "E=mc^2", "\\frac{a}{b} + \\cdot x", "", "\\cee{X}", "\\cellcolor{red}", "a \\ce b",
        "\\ce{H2O", "\\\\ce{H2O}", "\\ce{A}", "k[\\ce{A}]", "\\ce {H2O}", "\\ce{H2O}",
        "\\ce{2H2O}", "\\ce{Na+}", "\\ce{SO4^2-}", "\\ce{Fe^{3+}}", "\\ce{Ca(OH)2}",
        "\\ce{CuSO4*5H2O}", "\\ce{e-}", "\\ce{2H2 + O2 -> 2H2O}", "\\ce{A <=> B}",
        "\\ce{A <- B}", "\\ce{A <-> B}", "\\ce{AgCl(s) v}", "\\ce{H2 ^}", "\\ce{1/2O2}",
        "\\ce{Na+(aq)}", "\\ce{Fe^{3+}(aq)}", "\\ce{C-C}", "\\ce{C=C}", "\\ce{^{227}_{90}Th}",
        "\\ce{A ->[H2O] B}", "\\ce{\\ce{H2O}}", "\\ce{H2O} + \\ce{C-C}", "x \\ce{} y",
        "\\frac{\\ce{H2O}}{2}", "\\ce{Na+} and \\ce{Cl-}", "\\ce{A} x \\ce{B}", "\\ce{ H2O }",
    )

    @Test
    fun idempotentAcrossAllFixtures() {
        allInputs.forEach { input ->
            val once = t(input)
            assertEquals("translate must be idempotent for: \"$input\"", once, t(once))
        }
    }

    @Test
    fun outputNeverContainsCeMacroAcrossAllFixtures() {
        allInputs.forEach { input ->
            val out = t(input)
            // The only \ce{ allowed in an output is the untouched-identity case (unbalanced /
            // escaped-backslash / bare \ce inputs return the original string by design).
            if (out !== input) {
                assertFalse("output must not contain \\ce{ for: \"$input\"", out.contains("\\ce{"))
            }
        }
    }
}
