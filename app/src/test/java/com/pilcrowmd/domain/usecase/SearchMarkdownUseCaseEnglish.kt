// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.EmojiShortcodes
import com.pilcrowmd.domain.markdown.ReaderWords

/**
 * A [SearchMarkdownUseCase] with the source-language reader words, for tests. The production
 * constructor takes the words with no default, so no production code can build one that forgets them.
 */
fun SearchMarkdownUseCase(
    parseHeadingsUseCase: ParseMarkdownHeadingsUseCase,
    emoji: EmojiShortcodes = EmojiShortcodes.NONE,
): SearchMarkdownUseCase = SearchMarkdownUseCase(parseHeadingsUseCase, ReaderWords.ENGLISH, emoji)
