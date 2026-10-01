// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import org.scilab.forge.jlatexmath.MacroInfo
import org.scilab.forge.jlatexmath.NewCommandMacro
import org.scilab.forge.jlatexmath.ParseException
import org.scilab.forge.jlatexmath.TeXParser
import java.lang.ref.WeakReference

/**
 * Caps how many times user-defined macros (`\newcommand`, `\renewcommand`) may expand while one
 * formula is parsed, so a recursive definition fails to parse instead of spinning for ever.
 *
 * JLaTeXMath expands a user macro in place and rescans from the same position, with no limit, so
 * `\newcommand{\a}{\a}\a` never finishes — on screen it pins a math-loader thread at full CPU, in
 * the PDF export it blocks the synchronous build. A pre-check on the source cannot close this: the
 * library's macro table is process-wide (a definition in one formula recurses when another formula
 * uses it), and a macro can recurse through its arguments (`\newcommand{\p}[1]{#1#1}\p\p`) with no
 * cycle in its definition at all. Counting expansions catches every one of those shapes.
 *
 * How it hooks in: every user macro JLaTeXMath registers is invoked through the shared instance it
 * keeps in [MacroInfo.Packages] under [NewCommandMacro]'s class name, looked up when the macro is
 * DEFINED. [install] puts this subclass there first, so each expansion passes through
 * [executeMacro]. Over the limit it throws a [ParseException] — the same failure a malformed
 * formula produces — so the formula takes the existing graceful path and shows its source
 * (Safeguard 3). Built-in commands never use this class and are not counted.
 *
 * The count is per parser: JLaTeXMath expands macros only in [TeXParser]'s first pass, which runs
 * on one thread for one parser, so a new parser on a thread starts a fresh count.
 */
class MacroExpansionLimit : NewCommandMacro() {

    private class Budget {
        var parser: WeakReference<TeXParser>? = null
        var expansions = 0
    }

    override fun executeMacro(tp: TeXParser, args: Array<String>): String {
        val budget = BUDGET.get()!!
        if (budget.parser?.get() !== tp) {
            budget.parser = WeakReference(tp)
            budget.expansions = 0
        }
        budget.expansions++
        if (budget.expansions > MAX_EXPANSIONS_PER_FORMULA) {
            throw ParseException("macro expansion limit ($MAX_EXPANSIONS_PER_FORMULA) exceeded: recursive definition?")
        }
        return super.executeMacro(tp, args)
    }

    companion object {
        /** Far above any hand-written formula; a recursive macro reaches it in well under a millisecond. */
        const val MAX_EXPANSIONS_PER_FORMULA = 1_000

        private val BUDGET = ThreadLocal.withInitial { Budget() }

        /**
         * Idempotent. Must run before the first user macro is defined, i.e. before any formula is
         * built — it is called where the math plugin is configured. [MacroInfo.Packages] is a plain
         * `HashMap`, and more than one renderer instance can be built on different threads, so the
         * check-and-put is synchronized: an unsynchronized concurrent put can corrupt the map.
         */
        @Synchronized
        fun install() {
            val key = NewCommandMacro::class.java.name
            if (MacroInfo.Packages[key] !is MacroExpansionLimit) {
                MacroInfo.Packages[key] = MacroExpansionLimit()
            }
        }
    }
}
