// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.model

/** The "PDF colors" setting. Persisted by name, like [ThemeMode]. */
enum class PdfColors {
    LIGHT_FOR_PRINTING, // The default: white pages and light code panels, whatever the app theme.
    MATCH_APP_THEME, // The theme at export time, on the whole page.
}
