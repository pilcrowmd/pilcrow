// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.screenshot

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

/**
 * One golden-image fixture. [name] becomes the screenshot filename (so a diff names itself);
 * [markdown] is the source fed to the real renderer.
 *
 * [awaitMathRender]: JLatexMath renders math bitmaps asynchronously, and under Robolectric that
 * race is JVM-warmth-dependent — a capture may land before OR after the bitmaps resolve, flipping
 * the golden between raw source and rendered math across runs. Samples with this flag wait until
 * every LaTeX span has resolved, so their goldens deterministically lock the RENDERED math.
 * Every sample containing math MUST set it: an un-awaited math capture is a
 * race, not a stable fallback.
 */
data class MarkdownSample(
    val name: String,
    val markdown: String,
    val awaitMathRender: Boolean = false,
    // PLAIN renders the sample through the verbatim plain-text path (.txt view).
    val renderMode: com.pilcrowmd.domain.model.RenderMode = com.pilcrowmd.domain.model.RenderMode.MARKDOWN,
) {
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
        // Math samples lock the RENDERED formulas (awaitMathRender): the screenshot test now
        // initializes JLatexMath unconditionally, so an un-awaited capture would race the async
        // render. Historically these goldens locked the raw-source
        // fallback — that only held while uninitialized renders aborted deterministically.
        // Single-`$…$` inline math is rendered via SingleDollarMathInlineProcessor — this sample
        // and the `kitchen_sink` `$E=mc^2$` exercise it. Currency (`$5`) stays literal via the
        // disambiguation rules, exhaustively covered in InlineMathRenderingTest (parse-tree level).
        MarkdownSample(
            name = "latex_inline",
            markdown = "Inline math \$a^2 + b^2 = c^2\$ within a sentence.",
            awaitMathRender = true,
        ),
        MarkdownSample(
            name = "latex_block",
            markdown = "Block equation:\n\n\$\$\\int_0^1 x^2 \\, dx = \\frac{1}{3}\$\$",
            awaitMathRender = true,
        ),
        // mhchem chemistry (\ce{…}) is translated to plain LaTeX by CeMacroShimPlugin before
        // JLatexMath: formulas and reactions become renderable math; beyond-tier content (the
        // C-C bond) degrades to upright literal text INSIDE the equation — never a whole-equation
        // raw dump. awaitMathRender: this golden locks the RENDERED chemistry (subscripts,
        // charges, arrows — including the ⇄ that replaced the blank-glyph \rightleftharpoons)
        // plus surrounding typography.
        MarkdownSample(
            name = "chemistry_mhchem",
            markdown = "Water \$\\ce{H2O}\$ and \$\\ce{SO4^2-}\$ ions.\n\n" +
                "\$\$\\ce{2H2 + O2 -> 2H2O}\$\$\n\n" +
                "\$\$\\ce{N2 + 3H2 <=> 2NH3}\$\$\n\n" +
                "Fallback bond \$\\ce{C-C}\$ stays literal.",
            awaitMathRender = true,
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
        // Footnotes. Every line here earns its place — this one sample carries all four
        // of the behaviours that were previously wrong or unpinned, so the golden IS the proof:
        //  - `[^1]: gravity` — a single-token body is valid link-reference-definition syntax, so
        //    before the fix `[^1]` rendered as a live blue link to "gravity" and the definition
        //    line vanished from the page entirely (see docs/evidence/footnotes-before/);
        //  - `[^gone]` — an orphan reference stays literal, exactly as GitHub leaves it;
        //  - `[^unused]` — an unreferenced definition still renders, showing its label instead of
        //    a fabricated number (GitHub drops it; we never drop what the author wrote);
        //  - `[^c]` is defined ABOVE `[^1]` but referenced after it, so the notes read 2 then 1
        //    down the page — the accepted out-of-order consequence of numbering by first
        //    reference while rendering in place. Pinned so it can never surprise anyone twice.
        MarkdownSample(
            name = "footnotes",
            markdown = "Newton[^1] and Curie[^c] wrote it down; an orphan[^gone] stays literal.\n\n" +
                "[^c]: Discovered polonium.\n\n" +
                "[^1]: gravity\n\n" +
                "[^unused]: Never referenced.\n",
        ),
        // Plain-text render: the same Markdown-significant syntax that parses in the
        // samples above must stay LITERAL here — no heading typography, no bold, no list glyphs,
        // and the multi-blank stretch keeps its exact line count. Locks the .txt reader view.
        MarkdownSample(
            name = "plaintext_txt",
            markdown = "# not a heading\n**not bold** and *not italic*\n- not a list\n\n" +
                "| not | a table |\n`not code`\n\n\n\nafter a three-blank-line stretch\n" +
                "    indented spaces preserved",
            renderMode = com.pilcrowmd.domain.model.RenderMode.PLAIN,
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
            awaitMathRender = true,
        ),
    )
}
