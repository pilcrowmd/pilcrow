// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

/**
 * Translates mhchem `\ce{…}` chemistry syntax into plain LaTeX that JLaTeXMath can render,
 * per the mhchem shim design (tier grammar, fallback).
 *
 * Pure `String → String`. Contract:
 * - **Identity when inert:** a string without a real `\ce` occurrence is returned as the SAME
 *   reference — the untouched-path guarantee for every non-chemistry equation.
 * - **Tiers translated** (via [CeTermGrammar]): trivial species, formulas (subscripts, trailing
 *   charges, hydrate `*`, parens/brackets), and core reaction syntax (arrows, `+`, glued states,
 *   precipitate/gas markers, integer + fractional coefficients).
 * - **Fallback:** an argument beyond the tiers becomes upright literal text (`\text{…}` with the
 *   spike-validated escape table) so the rest of the equation still renders.
 * - **Idempotent:** output never contains a `\ce{` occurrence, so a second pass no-ops.
 * - **Never guesses:** unbalanced braces abort the whole translate (original string returned).
 */
object CeMacroShim {

    private const val MACRO = "\\ce"

    private val WHITESPACE = Regex("\\s+")

    /** A coefficient written as its own term: an integer or a simple fraction (`2`, `1/2`). */
    private val PURE_NUMBER = Regex("^\\d+(/\\d+)?$")

    private val TEXT_MODE_ESCAPES = mapOf(
        '{' to "\\{", '}' to "\\}", '_' to "\\_", '^' to "\\^{}", '~' to "\\~{}",
        '\\' to "{\\backslash}", '%' to "\\%", '$' to "\\$", '&' to "\\&", '#' to "\\#",
    )

    /** A located `\ce{…}` occurrence: [start] of `\ce`, argument between braces, [endExclusive]. */
    private data class Occurrence(val start: Int, val argument: String, val endExclusive: Int)

    fun translate(latex: String): String {
        if (!latex.contains(MACRO)) return latex
        var out: StringBuilder? = null
        var copied = 0
        var searchFrom = 0
        while (true) {
            val occurrence = findOccurrence(latex, searchFrom) ?: break
            // Unbalanced braces: never guess a boundary — abort the whole translate.
            if (occurrence.endExclusive < 0) return latex
            if (out == null) out = StringBuilder(latex.length)
            out.append(latex, copied, occurrence.start)
            out.append(renderArgument(occurrence.argument))
            copied = occurrence.endExclusive
            searchFrom = occurrence.endExclusive
        }
        if (out == null) return latex
        out.append(latex, copied, latex.length)
        return out.toString()
    }

    /**
     * Finds the next real `\ce` occurrence at/after [from]: a `\ce` whose backslash is not itself
     * escaped (an odd run of preceding backslashes means ours pairs into a literal `\\`), at a
     * macro-name boundary (`\cee`/`\cellcolor` don't match), followed by optional whitespace and a
     * brace group. Returns null when none; endExclusive < 0 signals unbalanced braces.
     */
    private fun findOccurrence(latex: String, from: Int): Occurrence? {
        var index = from
        while (true) {
            index = latex.indexOf(MACRO, index)
            if (index < 0) return null
            val nameEnd = index + MACRO.length
            val braceIndex = if (isRealMacroStart(latex, index, nameEnd)) braceGroupStart(latex, nameEnd) else -1
            if (braceIndex < 0) {
                index = nameEnd
                continue
            }
            val closeIndex = balancedGroupEnd(latex, braceIndex)
            if (closeIndex < 0) return Occurrence(index, "", -1)
            return Occurrence(index, latex.substring(braceIndex + 1, closeIndex), closeIndex + 1)
        }
    }

    /** The backslash at [index] is unescaped AND `\ce` ends at a macro-name boundary. */
    private fun isRealMacroStart(latex: String, index: Int, nameEnd: Int): Boolean {
        var precedingBackslashes = 0
        while (index - 1 - precedingBackslashes >= 0 && latex[index - 1 - precedingBackslashes] == '\\') {
            precedingBackslashes++
        }
        val escapedBackslash = precedingBackslashes % 2 == 1
        val macroBoundary = nameEnd >= latex.length || !latex[nameEnd].isLetter()
        return !escapedBackslash && macroBoundary
    }

    /** Index of the `{` opening the argument (after optional whitespace), or -1 when absent. */
    internal fun braceGroupStart(latex: String, nameEnd: Int): Int {
        var braceIndex = nameEnd
        while (braceIndex < latex.length && latex[braceIndex].isWhitespace()) braceIndex++
        return if (braceIndex < latex.length && latex[braceIndex] == '{') braceIndex else -1
    }

    /** Index of the `}` closing the group opened at [braceIndex] (escape-aware), or -1 if unbalanced. */
    internal fun balancedGroupEnd(latex: String, braceIndex: Int): Int {
        var depth = 0
        var scan = braceIndex
        while (scan < latex.length) {
            when (latex[scan]) {
                '\\' -> scan++ // skip the escaped char
                '{' -> depth++
                '}' -> if (--depth == 0) return scan
            }
            scan++
        }
        return -1
    }

    /** Renders one `\ce` argument: brace-grouped translation, fallback, or "" when empty. */
    private fun renderArgument(argument: String): String {
        val trimmed = argument.trim()
        if (trimmed.isEmpty()) return ""
        val terms = glueCoefficients(trimmed.split(WHITESPACE))
        val rendered = ArrayList<String>(terms.size)
        for (term in terms) {
            rendered += CeTermGrammar.renderTerm(term) ?: return fallback(trimmed)
        }
        return "{${rendered.joinToString(" ")}}"
    }

    /**
     * M-229: a coefficient written apart from its species (`2 H2`) is joined to it (`2H2`), so the
     * coefficient rule renders it. Only when the next term starts a formula (a letter or `(`): a
     * number before `+`, an arrow or nothing stays a term of its own and falls back as before.
     */
    private fun glueCoefficients(terms: List<String>): List<String> {
        val glued = ArrayList<String>(terms.size)
        var i = 0
        while (i < terms.size) {
            val next = terms.getOrNull(i + 1)?.firstOrNull()
            val startsSpecies = next != null && (next.isLetter() || next == '(')
            if (startsSpecies && PURE_NUMBER.matches(terms[i])) {
                glued += terms[i] + terms[i + 1]
                i += 2
            } else {
                glued += terms[i]
                i++
            }
        }
        return glued
    }

    /** F1 fallback: the raw argument as upright literal text, LaTeX specials escaped. */
    private fun fallback(trimmed: String): String {
        val escaped = StringBuilder(trimmed.length)
        for (c in trimmed) escaped.append(TEXT_MODE_ESCAPES[c] ?: c)
        return "{\\text{$escaped}}"
    }
}

/**
 * The tier grammar for one whitespace-delimited `\ce` term:
 * arrows / `+` / precipitate-gas markers, or a species = [coefficient] body [charge] [state] with
 * hydrate `*` sub-parts. Glued state suffixes are stripped BEFORE the trailing-charge rule so
 * `Na+(aq)` parses as charge + state. Returns null for anything beyond the tiers (mid-token bonds,
 * isotopes, arrow annotations, …), which poisons the whole occurrence into the caller's fallback.
 */
private object CeTermGrammar {

    private val STATE_SUFFIX = Regex("\\((s|l|g|aq)\\)$")
    private val BRACED_CHARGE_SUFFIX = Regex("\\^\\{(\\d*[+-])\\}$")
    private val CARET_CHARGE_SUFFIX = Regex("\\^(\\d*[+-])$")
    private val FRACTION_COEFFICIENT = Regex("^(\\d+)/(\\d+)")
    private val INTEGER_COEFFICIENT = Regex("^\\d+")
    private val BODY_CHARS = Regex("^[A-Za-z0-9()\\[\\]]+$")

    private val ARROWS = mapOf(
        "->" to "\\longrightarrow",
        "<-" to "\\longleftarrow",
        // NOT \rightleftharpoons: jlatexmath-android parses it but the glyph draws blank
        // (verified on-device and by ink-pixel probe). \rightleftarrows (⇄) keeps the correct
        // forward/reverse direction; harpoon-vs-arrowhead is an accepted fidelity deviation.
        // Guarded by CeShimJLatexAcceptanceTest's ink assertions.
        "<=>" to "\\rightleftarrows",
        "<->" to "\\longleftrightarrow",
    )

    /** One term; null poisons the occurrence into the fallback. */
    fun renderTerm(term: String): String? = when (term) {
        in ARROWS -> ARROWS.getValue(term)
        "+" -> "+"
        "v" -> "\\downarrow"
        "^" -> "\\uparrow"
        else -> renderSpecies(term)
    }

    /** A species: [coefficient] body [charge] [state], hydrate `*` splitting into sub-parts. */
    private fun renderSpecies(term: String): String? {
        var rest = term
        val state = STATE_SUFFIX.find(rest)?.value?.also { rest = rest.dropLast(it.length) }
        val charge = extractChargeSuffix(rest)?.also { rest = rest.dropLast(it.raw.length) }?.rendered
        if (rest.isEmpty()) return null
        val parts = rest.split('*')
        val renderedParts = ArrayList<String>(parts.size)
        for ((i, part) in parts.withIndex()) {
            val chargeForPart = charge.takeIf { i == parts.lastIndex }
            renderedParts += renderPart(part, chargeForPart) ?: return null
        }
        val species = renderedParts.joinToString("\\cdot ")
        return if (state != null) "$species\\,\\mathrm{$state}" else species
    }

    private class Charge(val raw: String, val rendered: String)

    /** Trailing charge: `^{n±}`, `^n±`, or a bare `±` after a letter/digit/closing bracket. */
    private fun extractChargeSuffix(rest: String): Charge? {
        BRACED_CHARGE_SUFFIX.find(rest)?.let { return Charge(it.value, it.groupValues[1]) }
        CARET_CHARGE_SUFFIX.find(rest)?.let { return Charge(it.value, it.groupValues[1]) }
        val last = rest.lastOrNull()
        if ((last == '+' || last == '-') && rest.length >= 2) {
            val before = rest[rest.length - 2]
            if (before.isLetterOrDigit() || before == ')' || before == ']') {
                return Charge(last.toString(), last.toString())
            }
        }
        return null
    }

    /** One hydrate part: optional coefficient, then a `\mathrm` body with digit-run subscripts. */
    private fun renderPart(part: String, charge: String?): String? {
        var body = part
        var coefficient = ""
        FRACTION_COEFFICIENT.find(body)?.let { match ->
            if (startsFormula(body, match.value.length)) {
                coefficient = "\\tfrac{${match.groupValues[1]}}{${match.groupValues[2]}}"
                body = body.substring(match.value.length)
            }
        }
        if (coefficient.isEmpty()) {
            INTEGER_COEFFICIENT.find(body)?.let { match ->
                if (startsFormula(body, match.value.length)) {
                    coefficient = match.value
                    body = body.substring(match.value.length)
                }
            }
        }
        if (!BODY_CHARS.matches(body) || body.none { it.isLetter() }) return null
        val subscripted = subscriptDigitRuns(body)
        if (charge != null) subscripted.append("^{").append(charge).append('}')
        return "$coefficient\\mathrm{$subscripted}"
    }

    /** Digit runs after a letter or closing bracket become subscripts (`H2O` → `H_{2}O`). */
    private fun subscriptDigitRuns(body: String): StringBuilder {
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c.isDigit() && i > 0 && subscriptsAfter(body[i - 1])) {
                val runEnd = digitRunEnd(body, i)
                out.append("_{").append(body, i, runEnd).append('}')
                i = runEnd
            } else {
                out.append(c)
                i++
            }
        }
        return out
    }

    private fun subscriptsAfter(previous: Char): Boolean = previous.isLetter() || previous == ')' || previous == ']'

    private fun startsFormula(body: String, at: Int): Boolean =
        at < body.length && (body[at].isLetter() || body[at] == '(')

    private fun digitRunEnd(body: String, from: Int): Int {
        var end = from
        while (end < body.length && body[end].isDigit()) end++
        return end
    }
}

/**
 * M-225: rewrites amsmath equation numbers, which JLaTeXMath does not know, into maths it can
 * draw, so a formula carrying one renders instead of showing its source. Render-only, like
 * [CeMacroShim]: `\tag{X}` becomes `\qquad(X)`, `\tag*{X}` becomes `\qquad X`, and `\notag` /
 * `\nonumber` are dropped. The number follows the formula after a `\qquad`; it is not
 * right-aligned as in LaTeX (an accepted degradation).
 *
 * Contract, as for [CeMacroShim]: the same reference back when nothing is rewritten; never
 * guesses (a `\tag{` whose brace never closes returns the whole string unchanged, so the formula
 * fails and shows its source as before); idempotent (a nested `\tag` inside a tag is rewritten in
 * the same pass, so no `\tag` survives into the output).
 */
internal object EquationTagShim {

    private const val TAG = "tag"
    private val DROPPED = setOf("notag", "nonumber")

    /** A `}` at [index] that closes a rewritten tag, and the [text] it becomes. */
    private class Closer(val index: Int, val text: String)

    fun translate(latex: String): String {
        if (!latex.contains(TAG) && !latex.contains("nonumber")) return latex
        val out = StringBuilder(latex.length)
        val closers = ArrayDeque<Closer>()
        var i = 0
        while (i < latex.length) {
            i = when {
                closers.lastOrNull()?.index == i -> {
                    out.append(closers.removeLast().text)
                    i + 1
                }
                latex[i] == '\\' -> rewriteControlSequence(latex, i, out, closers) ?: return latex
                else -> {
                    out.append(latex[i])
                    i + 1
                }
            }
        }
        val result = out.toString()
        return if (result == latex) latex else result
    }

    /**
     * Copies or rewrites the control sequence starting at [start] and returns the index after the
     * part consumed, or null when a tag's brace group never closes.
     */
    private fun rewriteControlSequence(
        latex: String,
        start: Int,
        out: StringBuilder,
        closers: ArrayDeque<Closer>,
    ): Int? {
        val nameEnd = controlSequenceEnd(latex, start)
        if (latex.substring(start + 1, nameEnd) in DROPPED) return nameEnd
        val brace = tagBraceStart(latex, start, nameEnd)
        if (brace < 0) {
            out.append(latex, start, nameEnd)
            return nameEnd
        }
        val close = CeMacroShim.balancedGroupEnd(latex, brace)
        if (close < 0) return null
        val starred = latex[nameEnd] == '*'
        out.append(if (starred) "\\qquad " else "\\qquad(")
        closers.addLast(Closer(close, if (starred) "" else ")"))
        return brace + 1
    }

    /**
     * End of the control sequence at [start]: a letter run (`\tag`), or one character for a control
     * symbol (`\\`, `\{`, …), which is copied whole so its character is never read as syntax.
     */
    private fun controlSequenceEnd(latex: String, start: Int): Int {
        var end = start + 1
        while (end < latex.length && latex[end].isLetter()) end++
        return if (end == start + 1) minOf(start + 2, latex.length) else end
    }

    /** The `{` of the argument when the sequence is `\tag` or `\tag*`, else -1. */
    private fun tagBraceStart(latex: String, start: Int, nameEnd: Int): Int {
        if (latex.substring(start + 1, nameEnd) != TAG) return -1
        val afterStar = if (nameEnd < latex.length && latex[nameEnd] == '*') nameEnd + 1 else nameEnd
        return CeMacroShim.braceGroupStart(latex, afterStar)
    }
}
