// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import androidx.compose.ui.graphics.Color
import com.pilcrowmd.domain.model.PdfColors
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.ui.theme.AmoledColorScheme
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PrintCodeColorScheme
import com.pilcrowmd.ui.theme.PrintColorScheme

/**
 * Every colour one export draws with: [page] for the page and its prose, tables and cards, [code] for
 * code and diagrams, [pageNumber] for the footer.
 */
internal data class PdfPalette(val page: PilcrowColorScheme, val code: PilcrowColorScheme, val pageNumber: Color) {
    companion object {
        /** Today's PDF, unchanged: the footer's pure black is not Print's near-black text. */
        val PRINT = PdfPalette(PrintColorScheme, PrintCodeColorScheme, Color.Black)

        fun of(scheme: PilcrowColorScheme) = PdfPalette(scheme, scheme, scheme.primaryText)

        fun of(colors: PdfColors, theme: ThemeMode): PdfPalette = when (colors) {
            PdfColors.LIGHT_FOR_PRINTING -> PRINT
            PdfColors.MATCH_APP_THEME -> of(
                when (theme) {
                    ThemeMode.DARK -> DarkColorScheme
                    ThemeMode.AMOLED -> AmoledColorScheme
                    ThemeMode.LIGHT -> LightColorScheme
                },
            )
        }
    }
}
