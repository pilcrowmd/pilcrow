// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import org.commonmark.node.Heading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M-159: the GitHub heading-anchor rule, and the anchor → adapter-position map built from it. */
class HeadingAnchorsTest {

    private fun document(markdown: String) = ParseMarkdownHeadingsUseCase().parseDocument(markdown)!!

    private fun slugOfHeadingLine(line: String): String =
        HeadingAnchors.slug(HeadingAnchors.plainText(document(line).firstChild as Heading))

    // --- slug ---

    @Test
    fun `CJK headings keep their letters and lose punctuation, as on GitHub`() {
        val expected = mapOf(
            "1. 学术建模与 Markdown 公式规范" to "1-学术建模与-markdown-公式规范",
            "6.1 编码前先思考" to "61-编码前先思考",
            "8. “禁止改动范围”提示词" to "8-禁止改动范围提示词",
            "10. Bug 复现与修复提示词" to "10-bug-复现与修复提示词",
            "2. SVG 拆解与 PPT 生成规范" to "2-svg-拆解与-ppt-生成规范",
            "5. 长对话压缩与 JSON 摘要提示词" to "5-长对话压缩与-json-摘要提示词",
        )
        expected.forEach { (heading, anchor) -> assertEquals(heading, anchor, HeadingAnchors.slug(heading)) }
    }

    @Test
    fun `English punctuation is dropped, spaces become hyphens, and runs are not collapsed`() {
        assertEquals("hello-world-its-2026", HeadingAnchors.slug("Hello, World! It's 2026"))
        assertEquals("a---b", HeadingAnchors.slug("a - b"))
        assertEquals("snake_case-kept", HeadingAnchors.slug("snake_case kept"))
    }

    @Test
    fun `inline code contributes its text to the slug`() {
        assertEquals("the-foo-function", slugOfHeadingLine("## The `foo` function"))
        assertEquals("use-foo_bar-now", slugOfHeadingLine("## Use `foo_bar()` now"))
    }

    @Test
    fun `text inside emphasis and links contributes to the slug`() {
        assertEquals("bold-and-link-end", slugOfHeadingLine("## **Bold** and [link](x) end"))
    }

    // --- targets ---

    @Test
    fun `a repeated heading gets a numeric suffix, skipping a suffixed form already taken`() {
        val targets = HeadingAnchors.targets(document("# A\n\n# A\n\n# A-1\n"))
        assertEquals(mapOf("a" to 0, "a-1" to 1, "a-1-1" to 2), targets)
    }

    @Test
    fun `a link reference definition takes no adapter position`() {
        val targets = HeadingAnchors.targets(
            document("[ref]: https://example.com\n\nIntro.\n\n## Target\n"),
        )
        assertEquals(mapOf("target" to 1), targets)
    }

    @Test
    fun `a heading nested in a quote is not a target`() {
        assertEquals(mapOf("outer" to 1), HeadingAnchors.targets(document("> ## Inner\n\n## Outer\n")))
    }

    @Test
    fun `a nested heading still counts when a repeat is numbered, as on GitHub`() {
        assertEquals(mapOf("a-1" to 1), HeadingAnchors.targets(document("> ## A\n\n## A\n")))
    }

    @Test
    fun `an image's alt text is not part of the slug`() {
        assertEquals("-title", slugOfHeadingLine("# ![logo](x.png) Title"))
    }

    // --- blockIndexOf ---

    private val targets = mapOf("hello-world" to 3, "1-学术建模" to 5, "a+b" to 7)

    @Test
    fun `a percent-encoded link finds the heading, and a plain one too`() {
        assertEquals(5, HeadingAnchors.blockIndexOf(targets, "#1-%E5%AD%A6%E6%9C%AF%E5%BB%BA%E6%A8%A1"))
        assertEquals(5, HeadingAnchors.blockIndexOf(targets, "#1-学术建模"))
    }

    @Test
    fun `an upper-case link still matches`() {
        assertEquals(3, HeadingAnchors.blockIndexOf(targets, "#Hello-World"))
    }

    @Test
    fun `a plus sign stays a plus sign`() {
        assertEquals(7, HeadingAnchors.blockIndexOf(targets, "#a+b"))
    }

    @Test
    fun `a link that is undecodable is looked up as written, not thrown on`() {
        assertNull(HeadingAnchors.blockIndexOf(targets, "#bad%zz"))
        assertEquals(3, HeadingAnchors.blockIndexOf(mapOf("hello%zz" to 3), "#hello%zz"))
    }

    @Test
    fun `no match, a bare hash, and a non-hash link all name nothing`() {
        assertNull(HeadingAnchors.blockIndexOf(targets, "#nope"))
        assertNull(HeadingAnchors.blockIndexOf(targets, "#"))
        assertNull(HeadingAnchors.blockIndexOf(targets, "hello-world"))
    }
}
