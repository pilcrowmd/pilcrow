// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.testing

import com.pilcrowmd.domain.markdown.ReaderModel
import com.pilcrowmd.domain.model.RenderMode
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.PrepareReaderModelUseCase
import com.pilcrowmd.rendering.MarkwonRenderer

/**
 * The model the ViewModel would prepare for [content] — the same use case over the reader's own parse
 * (`provideFactory`'s wiring), run synchronously so a test that composes `MarkdownPreview` directly
 * gets exactly what production would hand it.
 */
internal fun MarkwonRenderer.readerModelFor(
    content: String,
    renderMode: RenderMode = RenderMode.MARKDOWN,
    bareLinks: Set<String> = emptySet(),
): ReaderModel = PrepareReaderModelUseCase(
    parse = ::parseForReader,
    headings = ParseMarkdownHeadingsUseCase(),
    inlineText = ::readerInlineText,
).prepare(content, renderMode, bareLinks)
