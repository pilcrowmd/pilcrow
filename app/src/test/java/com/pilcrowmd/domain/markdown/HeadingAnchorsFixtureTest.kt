// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import org.commonmark.node.Heading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * M-159 against a file a user reported: a Chinese note whose table of contents links to its own
 * headings. Every link must land on the heading whose text it is labelled with.
 */
class HeadingAnchorsFixtureTest {

    private val content = javaClass.getResourceAsStream("/anchors/heading-links-zh.md")!!
        .readBytes().toString(Charsets.UTF_8)

    private val links = Regex("\\[([^\\]]+)]\\((#[^)]*)\\)").findAll(content)
        .map { it.groupValues[1] to it.groupValues[2] }.toList()

    @Test
    fun `every table-of-contents link lands on the heading it is labelled with`() {
        // The file lists 28 numbered sections plus the four sub-sections 6.1 to 6.4 under section 6.
        assertEquals("the fixture's link count", FIXTURE_LINKS, links.size)

        val document = ParseMarkdownHeadingsUseCase().parseDocument(content)!!
        val targets = HeadingAnchors.targets(document)
        val blocks = AdapterBlocks.of(document)
        links.forEach { (label, link) ->
            val index = HeadingAnchors.blockIndexOf(targets, link)
            assertNotNull("$link names no heading", index)
            val heading = blocks[index!!] as Heading
            assertEquals(link, label, HeadingAnchors.plainText(heading))
        }
    }

    private companion object {
        const val FIXTURE_LINKS = 32
    }
}
