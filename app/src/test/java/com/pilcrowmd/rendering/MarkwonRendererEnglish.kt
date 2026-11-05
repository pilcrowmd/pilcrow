// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import com.pilcrowmd.domain.markdown.EmojiShortcodes
import com.pilcrowmd.domain.markdown.ReaderWords
import com.pilcrowmd.repository.RelativeImageResolver

/**
 * A [MarkwonRenderer] with the source-language reader words, for tests. The production constructor
 * takes the words with no default, so no production code can build a renderer that forgets them.
 */
internal fun MarkwonRenderer(
    context: Context,
    relativeImages: RelativeImageResolver? = null,
    emoji: EmojiShortcodes = EmojiShortcodes.NONE,
): MarkwonRenderer = MarkwonRenderer(context, ReaderWords.ENGLISH, relativeImages, emoji)
