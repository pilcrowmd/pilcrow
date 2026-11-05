// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.res.Resources
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.CalloutKind
import com.pilcrowmd.domain.markdown.ReaderWords

/**
 * The reader's words (languages spec D2) as [resources] spells them. The `when` has no `else`, so a
 * [CalloutKind] added without a string fails to compile here rather than painting nothing.
 */
fun readerWords(resources: Resources): ReaderWords = ReaderWords(
    calloutTitles = CalloutKind.entries.associateWith { kind ->
        resources.getString(
            when (kind) {
                CalloutKind.NOTE -> R.string.callout_title_note
                CalloutKind.TIP -> R.string.callout_title_tip
                CalloutKind.IMPORTANT -> R.string.callout_title_important
                CalloutKind.WARNING -> R.string.callout_title_warning
                CalloutKind.CAUTION -> R.string.callout_title_caution
            },
        )
    },
    detailsSummary = resources.getString(R.string.details_default_summary),
)
