// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.text.BreakIterator

/** The characters kept at the end of a shortened file name (M-221). */
internal const val FILE_NAME_TAIL = 8

internal const val MIDDLE_ELLIPSIS = "…"

/**
 * M-221: shortens [name] in the middle so that its last [tail] characters stay visible.
 *
 * Returns [name] unchanged when it [fits], or when it has no more than `tail + 1` characters (there
 * is nothing to gain by cutting it). Otherwise returns the longest start of the name that still
 * [fits] followed by `…` and the last [tail] characters. When not even `…` plus those characters
 * fit, returns exactly that and leaves the rest to the caller's end-ellipsis (NEW-25).
 *
 * Characters are counted as the reader sees them (grapheme clusters), so an emoji, a ZWJ sequence
 * or a letter with a combining accent is never split.
 */
internal fun middleEllipsize(name: String, tail: Int = FILE_NAME_TAIL, fits: (String) -> Boolean): String {
    if (fits(name)) return name
    val bounds = graphemeBoundaries(name)
    val clusters = bounds.size - 1
    if (clusters <= tail + 1) return name
    val end = MIDDLE_ELLIPSIS + name.substring(bounds[clusters - tail])
    // At least one character is always hidden, so the longest start is clusters - tail - 1.
    var low = 0
    var high = clusters - tail - 1
    var best = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (fits(name.substring(0, bounds[mid]) + end)) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return if (best <= 0) end else name.substring(0, bounds[best]) + end
}

/** The string offsets between grapheme clusters, from 0 to `text.length` inclusive. */
private fun graphemeBoundaries(text: String): List<Int> {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val bounds = mutableListOf(iterator.first())
    var next = iterator.next()
    while (next != BreakIterator.DONE) {
        bounds += next
        next = iterator.next()
    }
    return bounds
}

/**
 * One line of file name, shortened in the middle when it does not fit (M-221). The fit is decided
 * by measuring pixels at the width the line actually has, never by sp arithmetic, so it holds at
 * every OS font size. `TextOverflow.Ellipsis` stays as the safety net for the fallback.
 *
 * TalkBack reads the full name: the node's text semantics is [name], not the shortened string, so
 * the name is read the same way it was before this row, only never cut.
 */
@Composable
internal fun FileNameText(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    val style = LocalTextStyle.current.merge(TextStyle(color = color, fontSize = fontSize))
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val shown = remember(name, maxWidth, bounded, style, measurer) {
            if (!bounded) {
                name
            } else {
                middleEllipsize(name) { candidate ->
                    measurer.measure(candidate, style, softWrap = false, maxLines = 1).size.width <= maxWidth
                }
            }
        }
        Text(
            text = shown,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { text = AnnotatedString(name) },
        )
    }
}
