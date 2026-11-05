// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import com.pilcrowmd.domain.markdown.CalloutKind.CAUTION
import com.pilcrowmd.domain.markdown.CalloutKind.IMPORTANT
import com.pilcrowmd.domain.markdown.CalloutKind.NOTE
import com.pilcrowmd.domain.markdown.CalloutKind.TIP
import com.pilcrowmd.domain.markdown.CalloutKind.WARNING

/**
 * Words the reader paints that are not in the document (languages spec D2): a callout's default
 * title and the summary of a `<details>` without one. Built from strings.xml by the app
 * ([com.pilcrowmd.rendering.readerWords]); [ENGLISH] is the source-language default for tests.
 */
data class ReaderWords(val calloutTitles: Map<CalloutKind, String>, val detailsSummary: String) {
    /** The title [kind] paints when the callout's header gives none. */
    fun calloutTitle(kind: CalloutKind): String = calloutTitles.getValue(kind)

    companion object {
        val ENGLISH = ReaderWords(
            calloutTitles = mapOf(
                NOTE to "Note",
                TIP to "Tip",
                IMPORTANT to "Important",
                WARNING to "Warning",
                CAUTION to "Caution",
            ),
            detailsSummary = "Details",
        )
    }
}
