// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.theme

import androidx.annotation.FontRes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.pilcrowmd.R

/**
 * A bundled typography set. Each set pairs a reading font (preview body,
 * headings, tables) with a monospace font (fenced code, frontmatter, editor).
 *
 * All families here are SIL OFL — the only license we may bundle (hard constraint).
 *
 * The `*Regular` resource ids are what the Markwon block entries and the Sora editor
 * load via `ResourcesCompat.getFont` at bind/init time (bold is synthesised by the
 * platform, matching the pre-existing single-font behaviour). The Compose `FontFamily`
 * values are for any Compose-side text that wants the set.
 */
data class FontSet(
    val id: String,
    val displayName: String,
    @FontRes val readingRegular: Int,
    @FontRes val readingBold: Int,
    @FontRes val monoRegular: Int,
    @FontRes val monoBold: Int,
) {
    val readingFamily: FontFamily = FontFamily(
        Font(readingRegular, FontWeight.Normal),
        Font(readingBold, FontWeight.Bold),
    )
    val monoFamily: FontFamily = FontFamily(
        Font(monoRegular, FontWeight.Normal),
        Font(monoBold, FontWeight.Bold),
    )
}

/** Registry of selectable font sets. Persisted by id (`font_set_id`). */
object FontSets {
    // Classic keeps the id "source" so an existing user's persisted selection survives the rename.
    val CLASSIC = FontSet(
        id = "source",
        displayName = "Classic",
        readingRegular = R.font.source_serif_4_regular,
        readingBold = R.font.source_serif_4_bold,
        monoRegular = R.font.jetbrains_mono_regular,
        monoBold = R.font.jetbrains_mono_bold,
    )

    val BOOK = FontSet(
        id = "book",
        displayName = "Book",
        readingRegular = R.font.merriweather_regular,
        readingBold = R.font.merriweather_bold,
        monoRegular = R.font.ibm_plex_mono_regular,
        monoBold = R.font.ibm_plex_mono_bold,
    )

    val MODERN = FontSet(
        id = "modern",
        displayName = "Modern",
        readingRegular = R.font.atkinson_hyperlegible_regular,
        readingBold = R.font.atkinson_hyperlegible_bold,
        monoRegular = R.font.jetbrains_mono_regular,
        monoBold = R.font.jetbrains_mono_bold,
    )

    val ALL = listOf(CLASSIC, BOOK, MODERN)
    val DEFAULT = CLASSIC

    // Old ids ("literata", "inter") fall back to DEFAULT after the E1 lineup swap.
    fun byId(id: String?): FontSet = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
