// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.screenshot

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

/**
 * One golden-image fixture. [name] becomes the screenshot filename (so a diff names itself);
 * [markdown] is the source fed to the real renderer.
 */
data class MarkdownSample(val name: String, val markdown: String) {
    // Drives the parameterized test's display name; keeps it readable (no embedded newlines).
    override fun toString(): String = name
}

/**
 * The canonical set of standard Markdown layout elements captured as the Golden Baseline.
 * One sample per block type, plus a kitchen-sink mixed document. Adding a value here
 * automatically produces a new golden image via [MarkdownScreenshotTest] — no test edits needed.
 *
 * Modeled as a [PreviewParameterProvider] so the fixtures double as Compose preview inputs.
 */
class MarkdownSampleProvider : PreviewParameterProvider<MarkdownSample> {
    override val values: Sequence<MarkdownSample> = sequenceOf(
        MarkdownSample(
            name = "headings",
            markdown = "# Heading 1\n## Heading 2\n### Heading 3\n#### Heading 4",
        ),
        MarkdownSample(
            name = "paragraph_emphasis",
            markdown = "A paragraph with **bold**, *italic*, ~~strikethrough~~, and `inline code` " +
                "to verify body typography, line-height, and inline spans.",
        ),
        MarkdownSample(
            name = "list_unordered",
            markdown = "- First item\n- Second item\n- Third item",
        ),
        MarkdownSample(
            name = "list_ordered",
            markdown = "1. First step\n2. Second step\n3. Third step",
        ),
        MarkdownSample(
            name = "list_nested",
            markdown = "- Parent\n    - Child A\n    - Child B\n        - Grandchild\n- Sibling",
        ),
        MarkdownSample(
            name = "task_list",
            markdown = "- [x] Done item\n- [ ] Pending item\n- [ ] Another pending",
        ),
        MarkdownSample(
            name = "blockquote",
            markdown = "> A blockquote spanning\n> two source lines.\n>\n> > And a nested quote.",
        ),
        MarkdownSample(
            name = "code_fenced",
            markdown = "```kotlin\nfun greet(name: String): String {\n    return \"Hello, \$name\"\n}\n```",
        ),
        MarkdownSample(
            name = "table",
            markdown = "| Left | Center | Right |\n|:-----|:------:|------:|\n| a | b | c |\n| dd | ee | ff |",
        ),
        MarkdownSample(
            name = "horizontal_rule",
            markdown = "Above the rule.\n\n---\n\nBelow the rule.",
        ),
        MarkdownSample(
            name = "link_and_image",
            // No ImagesPlugin in the reader path, so the image degrades to deterministic alt text.
            markdown = "A [hyperlink](https://example.com) and an image ![alt text](https://example.com/x.png).",
        ),
        // NOTE: JLatexMath does not rasterize formulas under Robolectric, so these goldens lock the
        // graceful-degradation fallback (raw source) + surrounding typography — Safeguard 3 — rather
        // than rendered formula bitmaps. On-device formula rendering is covered by UAT.
        // Single-`$…$` inline math IS now rendered (via SingleDollarMathInlineProcessor) —
        // this sample and the `kitchen_sink` `$E=mc^2$` exercise it. Currency (`$5`) stays literal via
        // the disambiguation rules, exhaustively covered in InlineMathRenderingTest (parse-tree level).
        MarkdownSample(
            name = "latex_inline",
            markdown = "Inline math \$a^2 + b^2 = c^2\$ within a sentence.",
        ),
        MarkdownSample(
            name = "latex_block",
            markdown = "Block equation:\n\n\$\$\\int_0^1 x^2 \\, dx = \\frac{1}{3}\$\$",
        ),
        MarkdownSample(
            name = "mermaid_degraded",
            // Safeguard 3: unsupported syntax must render gracefully (as a code block here).
            markdown = "```mermaid\ngraph TD\n    A[Start] --> B[End]\n```",
        ),
        MarkdownSample(
            name = "frontmatter",
            markdown = "---\ntitle: Sample Doc\nauthor: Tester\n---\n\nBody after frontmatter.",
        ),
        MarkdownSample(
            name = "kitchen_sink",
            markdown = """
                # Kitchen Sink

                A paragraph with **bold**, *italic*, and `code`.

                - bullet
                - [x] task

                > quote

                | A | B |
                |---|---|
                | 1 | 2 |

                ```kotlin
                val x = 42
                ```

                Inline ${'$'}E = mc^2${'$'} math.
            """.trimIndent(),
        ),
    )
}
