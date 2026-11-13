// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.model.RenderMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S8-A94 (large-documents spec, Rule 5): pictures are counted in the reader model's blocks, never by
 * parsing the text again. On a 20 MB note shown as plain text, that parse was a whole tree nobody
 * looked at, and it ran the phone out of memory on open (S8-A93).
 */
class CountRelativeImagesUseCaseTest {

    private val headings = ParseMarkdownHeadingsUseCase()
    private val count = CountRelativeImagesUseCase()

    private fun prepare(parsedFrom: String, ceiling: Int = Int.MAX_VALUE) =
        PrepareReaderModelUseCase({ headings.parseTree(parsedFrom) }, headings, markdownCeilingChars = ceiling)

    @Test
    fun `the pictures are the reader model's, not a parse of the text`() {
        // The reader's parse here is of other text: two pictures next to the note, one remote.
        val reader = prepare("![a](images/a.png) ![b](b.png) ![c](https://example.com/c.png)\n")
        val model = reader.prepare("No pictures in this text.\n", RenderMode.MARKDOWN)

        assertEquals(2, count(model))
    }

    @Test
    fun `pictures in code are not pictures`() {
        val text = "`![a](a.png)`\n\n```\n![b](b.png)\n```\n\n![c](c.png)\n"
        assertEquals(1, count(prepare(text).prepare(text, RenderMode.MARKDOWN)))
    }

    @Test
    fun `a note shown as plain text has no pictures to ask a folder for`() {
        val text = "![a](images/a.png)\n"
        assertEquals("too large to format", 0, count(prepare(text, ceiling = 4).prepare(text, RenderMode.MARKDOWN)))
        assertEquals("plain text by choice", 0, count(prepare(text).prepare(text, RenderMode.PLAIN)))
    }
}
