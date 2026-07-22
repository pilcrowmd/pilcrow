// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.FontSet
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.FencedCodeBlock

/**
 * Mutable search-highlight request shared with the prose entry. The composable updates these
 * fields and calls adapter.notifyDataSetChanged(), so highlights are applied at BIND time —
 * surviving view recycling and covering off-screen blocks, unlike post-hoc holder mutation.
 * `query` empty = no highlight. `focusedPosition` is the adapter position of the current match's
 * block (drawn in `focusedColor`); other matching blocks use `otherColor`. Colors are ARGB ints
 * sourced from the token layer by the caller.
 */
class SearchHighlight(
    var query: String = "",
    var focusedPosition: Int = -1,
    // 0-based ordinal of the current match WITHIN its block — only that occurrence is drawn in
    // `focusedColor`; all other matches (incl. siblings in the same block) use `otherColor`. So
    // next/prev visibly moves the focus even when many matches share one block.
    var focusedOccurrence: Int = 0,
    var otherColor: Int = 0,
    var focusedColor: Int = 0,
)

/**
 * Factory for building a MarkwonAdapter configured for block-level rendering.
 *
 * This adapter renders each top-level Markdown node as a separate RecyclerView item:
 * - Prose blocks (paragraphs, headings, lists, etc.) render into wrapped TextViews
 * - Table blocks render into HorizontalScrollView containers
 * - Fenced code blocks render into HorizontalScrollView containers
 * - LaTeX display math blocks ($$...$$) render into HorizontalScrollView containers
 * - YAML frontmatter (leading ---...---) renders as a styled code block with "yaml" label
 *
 * The adapter reuses the Markwon instance and its entire plugin chain (CommonMark, GFM tables,
 * Prism4j syntax highlighting, JLatexMath for math rendering, etc.).
 *
 * Unsupported node types (Mermaid, footnotes, etc.) gracefully fall back to the default prose entry,
 * ensuring no crash on unexpected syntax (Safeguard 3).
 */
object RecyclerAdapterEntries {

    /**
     * Build a MarkwonAdapter configured for block-level Markdown rendering.
     *
     * @param context Android context for inflating layouts
     * @param markwon Configured Markwon instance (from MarkwonRenderer) with all plugins
     * @param fontScale Font scale multiplier (0.85–1.6, default 1.0) applied to all text sizes
     * @param fontSet Selected typography set — drives reading + mono typefaces
     * @param mermaidCloudEnabled When true, ```mermaid blocks render via mermaid.ink
     * @param searchHighlight Search highlight state
     * @param colorScheme Active color scheme (Dark or Light) for rendering colors
     * @return MarkwonAdapter ready to be set on a RecyclerView
     */
    fun buildMarkdownAdapter(
        context: Context,
        markwon: Markwon,
        fontScale: Float = 1.0f,
        fontSet: FontSet = FontSets.DEFAULT,
        mermaidCloudEnabled: Boolean = false,
        searchHighlight: SearchHighlight = SearchHighlight(),
        colorScheme: PilcrowColorScheme = DarkColorScheme,
    ): MarkwonAdapter {
        // Default entry = styled prose (reading font / 17sp / primaryText / 1.35) so headings,
        // paragraphs, lists, blockquotes etc. match the reading design — NOT the unstyled SimpleEntry default.
        // The prose entry also applies search highlights at bind time.
        val adapter = MarkwonAdapter.builder(ProseBlockEntry(context, fontScale, fontSet, searchHighlight, colorScheme))
            // Register custom entry for YAML frontmatter
            // Must come before generic FencedCodeBlockEntry so "yaml" blocks route here first
            .include(
                FencedCodeBlock::class.java,
                ConditionalCodeBlockEntry(
                    context,
                    fontScale,
                    fontSet,
                    mermaidCloudEnabled,
                    colorScheme,
                    searchHighlight,
                ),
            )
            // Register custom entry for table blocks (renders into HorizontalScrollView)
            .include(TableBlock::class.java, TableBlockEntry(context, fontScale, fontSet, colorScheme, searchHighlight))
            // Unregistered node types fall back to the default prose entry (graceful-ignore)
            .build()

        // NOTE: LaTeX display math block entry is deferred.
        // If ext-latex 4.6.2 emits a dedicated block-math node type (e.g., DisplayMathBlock),
        // we would register it here with .include(DisplayMathBlock::class.java, latexEntry).
        // For now, block math renders via ProseBlockEntry (inline math is fully supported).
        // This is acceptable graceful degradation (block math falls back to inline rendering).

        return adapter
    }

    /**
     * Conditional code block entry that routes FencedCodeBlock nodes based on info string:
     * - info == "yaml" → FrontmatterBlockEntry (styled YAML block with label)
     * - else → FencedCodeBlockEntry (normal code block with copy button)
     *
     * This allows both entries to handle FencedCodeBlock without conflicts.
     */
    private class ConditionalCodeBlockEntry(
        private val context: Context,
        private val fontScale: Float = 1.0f,
        private val fontSet: FontSet = FontSets.DEFAULT,
        private val mermaidCloudEnabled: Boolean = false,
        private val colorScheme: PilcrowColorScheme = DarkColorScheme,
        private val searchHighlight: SearchHighlight = SearchHighlight(),
    ) : MarkwonAdapter.Entry<FencedCodeBlock, FencedCodeBlockEntry.Holder>() {

        private val yamlEntry = FrontmatterBlockEntry(context, fontScale, fontSet, colorScheme, searchHighlight)
        private val codeEntry = FencedCodeBlockEntry(context, fontScale, fontSet, colorScheme, searchHighlight)

        override fun createHolder(
            inflater: android.view.LayoutInflater,
            parent: android.view.ViewGroup,
        ): FencedCodeBlockEntry.Holder {
            // All fenced blocks (code, yaml, mermaid) share ONE holder/layout — MarkwonAdapter
            // allows a single entry per node type, so they cannot have separate holder classes.
            return codeEntry.createHolder(inflater, parent)
        }

        override fun bindHolder(
            markwon: io.noties.markwon.Markwon,
            holder: FencedCodeBlockEntry.Holder,
            node: FencedCodeBlock,
        ) {
            // Route by info string. yaml → styled frontmatter; mermaid (if opted in) → cloud
            // image; everything else → normal code block. All bind into the same holder.
            val info = node.info?.trim()?.lowercase()
            when {
                info == "mermaid" && mermaidCloudEnabled -> codeEntry.bindMermaid(markwon, holder, node)
                info == "mermaid" -> codeEntry.bindMermaidOff(markwon, holder, node)
                info == "yaml" -> yamlEntry.bindHolder(markwon, holder, node)
                else -> codeEntry.bindHolder(markwon, holder, node)
            }
        }
    }
}
