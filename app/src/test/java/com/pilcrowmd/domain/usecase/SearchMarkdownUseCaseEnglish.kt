// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.EmojiShortcodes
import com.pilcrowmd.domain.markdown.PlainTextChunks
import com.pilcrowmd.domain.markdown.ReaderWords
import com.pilcrowmd.domain.markdown.TooDeepDocument
import com.pilcrowmd.domain.model.SearchMatches

/**
 * A [SearchMarkdownUseCase] with the source-language reader words, for tests. The production
 * constructor takes the words with no default, so no production code can build one that forgets them.
 * [parseHeadingsUseCase] is the parse [findSearchMatches] below gives the blocks of; production search
 * takes the reader's own blocks instead (large-documents spec, Rule 1).
 */
fun SearchMarkdownUseCase(
    @Suppress("UNUSED_PARAMETER") parseHeadingsUseCase: ParseMarkdownHeadingsUseCase,
    emoji: EmojiShortcodes = EmojiShortcodes.NONE,
): SearchMarkdownUseCase = SearchMarkdownUseCase(ReaderWords.ENGLISH, emoji)

/**
 * Search [content] over the parity parser's blocks — the tree production searched before it took the
 * reader's (S8-A94), which has the same blocks (`ParseParityTest`). A document too deep to render is
 * searched as the plain chunks the reader shows for it, as before.
 */
fun SearchMarkdownUseCase.findSearchMatches(
    content: String,
    query: String,
    limit: Int = SearchMatches.MAX_MATCHES,
): SearchMatches {
    val doc = ParseMarkdownHeadingsUseCase().parseDocument(content) ?: return SearchMatches.EMPTY
    if (doc is TooDeepDocument) return findPlainSearchMatches(content, PlainTextChunks.split(content), query, limit)
    return findSearchMatches(content, AdapterBlocks.of(doc), query, limit)
}
