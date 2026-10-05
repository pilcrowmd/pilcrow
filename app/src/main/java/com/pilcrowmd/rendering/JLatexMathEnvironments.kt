// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.util.Log
import org.scilab.forge.jlatexmath.NewEnvironmentMacro
import org.scilab.forge.jlatexmath.PredefMacros

/**
 * Issue #9: JLatexMath 0.2.0 defines `cases` as `\left\{\begin{array}{l@{\!}l}`, a NEGATIVE thin
 * space between the columns, so "t, & 0 ≤ t < 1" renders as "t,0 ≤ t < 1". amsmath puts `\quad`
 * there; this redefines `cases` the same way. Every other environment is left as the library has it.
 *
 * Order matters: [PredefMacros]' static initializer is what defines the library's `cases`, so it
 * must have run before ours, or it would overwrite ours when it did. Constructing it forces it,
 * exactly as `TeXFormula`'s own static initializer does. That initializer only fills the macro
 * tables — it loads no fonts — so this is safe before JLatexMath's Android init.
 *
 * `addNewEnvironment` ends in a plain `HashMap.put`, so a later definition replaces the earlier one.
 * Runs once per process; called before any Markwon instance can render a formula. A failure only
 * logs: the library's own `cases` still renders, just without the gap (Safeguard 3).
 */
internal fun installJLatexMathEnvironments() = casesRedefinition

@Suppress("TooGenericExceptionCaught") // Safeguard 3: a library change, even a linkage error, must not crash
private val casesRedefinition: Unit by lazy<Unit> {
    try {
        PredefMacros()
        NewEnvironmentMacro.addNewEnvironment(
            "cases",
            "\\left\\{\\begin{array}{l@{\\quad}l}",
            "\\end{array}\\right.",
            0,
        )
    } catch (e: Exception) {
        keepLibraryCases(e)
    } catch (e: LinkageError) {
        keepLibraryCases(e)
    }
}

private fun keepLibraryCases(e: Throwable) {
    Log.w("JLatexMath", "cases redefinition failed, keeping the library's: ${e.message}")
}
