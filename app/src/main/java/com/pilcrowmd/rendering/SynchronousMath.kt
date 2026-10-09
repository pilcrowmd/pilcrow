// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.Spanned
import android.util.Log
import androidx.annotation.ColorInt
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * Render every LaTeX formula in [text] now, on the calling thread, instead of on the plugin's
 * background executor. Covers block and inline maths (the inline span type extends
 * [JLatexAsyncDrawableSpan]).
 *
 * Two callers need the formula's real size before anything else happens. The PDF export lays out
 * detached views, which the plugin's callback never reaches. A table cell is measured once
 * and its column width is then fixed, so a formula that arrived later would not widen its column
 * (M-32). `JLatexMathDrawable.builder(latex).build()` is exactly what the plugin's own loader calls;
 * feeding its result back through `setResult` flips `hasResult()`, so the span reports the formula's
 * true size and draws it, and the plugin then loads nothing for it.
 *
 * [textSizePx] and [textColor] are what the screen's inline maths uses: the body size at the reading
 * scale (M-121) and the text colour. A span that already has its result is left alone. Each formula
 * is tried on its own, so one that fails keeps its source text and the rest still render
 * (Safeguard 3).
 *
 * @return whether any formula was resolved, so a view that already laid this text out can lay it out
 *   again at the formulas' real sizes.
 */
internal fun resolveLatexSynchronously(text: Spanned, textSizePx: Float, @ColorInt textColor: Int): Boolean {
    var resolved = false
    for (span in text.getSpans(0, text.length, JLatexAsyncDrawableSpan::class.java)) {
        val asyncDrawable = span.drawable
        if (asyncDrawable.hasResult()) continue
        runCatching {
            val math = JLatexMathDrawable.builder(asyncDrawable.destination)
                .textSize(textSizePx)
                .color(textColor)
                .build()
            math.setBounds(0, 0, math.intrinsicWidth, math.intrinsicHeight)
            asyncDrawable.setResult(math)
            resolved = true
        }.onFailure { e ->
            Log.w("JLatexMath", "Synchronous LaTeX render failed: ${e.message}")
        }
    }
    return resolved
}
