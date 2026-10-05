// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import com.pilcrowmd.ui.theme.PilcrowColorScheme
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.core.MarkwonTheme

/**
 * Code backgrounds from the token layer (M-135). `SyntaxHighlightPlugin` sets only Markwon's inline
 * code background, and the fenced-block background falls back to it, so one value drove both. This
 * sets the two separately: fenced blocks take [PilcrowColorScheme.codeBlockBg], inline code takes
 * [PilcrowColorScheme.inlineCodeBg]. Registered AFTER `SyntaxHighlightPlugin`, so it wins.
 *
 * Also the link colour (M-219), when the scheme has one: [PilcrowColorScheme.link]. Without it Markwon
 * paints links in the TextView's `textColorLink`, from the platform theme.
 */
internal class CodeSurfacePlugin(private val colorScheme: PilcrowColorScheme) : AbstractMarkwonPlugin() {
    override fun configureTheme(builder: MarkwonTheme.Builder) {
        builder
            .codeBackgroundColor(colorScheme.inlineCodeBg.toArgb())
            .codeBlockBackgroundColor(colorScheme.codeBlockBg.toArgb())
        colorScheme.link?.let { builder.linkColor(it.toArgb()) }
    }
}
