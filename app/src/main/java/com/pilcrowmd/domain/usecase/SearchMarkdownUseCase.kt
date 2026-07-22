// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.InlineMathDelimiters
import com.pilcrowmd.domain.model.SearchMatch
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text

/**
 * UseCase for searching markdown content and finding matches.
 * Depends on [ParseMarkdownHeadingsUseCase] to parse the document with the same parity parser the
 * renderer uses, so search walks the exact top-level block sequence the MarkwonAdapter paints.
 * Stateless; encapsulates the search logic extracted from MarkdownViewModel.
 *
 * Matches are found in **rendered/visible-text space**, not raw source. Each top-level block
 * is searched over its *assembled* visible text — the same concatenated string Markwon puts in the
 * block's TextView — so a hit's per-block occurrence ordinal lines up exactly with what the highlighter
 * paints and the scroll resolver walks. This means:
 *  - hits inside non-rendered syntax (link/image destinations, image alt text) never count — they
 *    were phantom matches that could be neither highlighted nor scrolled to;
 *  - a query straddling two inline nodes of different styling (`**fre**e` → "free") DOES count, because
 *    the assembled text is concatenated exactly as the TextView is;
 *  - soft/hard line breaks are assembled as the space / newline Markwon renders, so a multi-word query
 *    spanning a break is found identically on both sides.
 * Each match keeps an exact raw-source [SearchMatch.startIndex] (reconstructed during assembly) for the
 * editor's select-and-reveal, since commonmark-java 0.13.0 carries no source spans.
 */
class SearchMarkdownUseCase(private val parseHeadingsUseCase: ParseMarkdownHeadingsUseCase) {

    /**
     * Find all search matches in the markdown content.
     * Case-insensitive; returns [SearchMatch] objects with the rendered matched text, raw-source start
     * index, adapter position (block), and per-block occurrence ordinal (for focused highlighting).
     */
    fun findSearchMatches(content: String, query: String): List<SearchMatch> {
        if (query.isEmpty()) return emptyList()
        val doc = parseHeadingsUseCase.parseDocument(content) ?: return emptyList()
        return BlockScanner(content, query).scan(doc)
    }

    /**
     * Walks the parsed document once, accumulating matches over each block's assembled visible text.
     * Holds the monotonic raw-source [cursor] and the running [matches] as fields so the per-block and
     * per-node helpers stay small.
     */
    private class BlockScanner(private val content: String, private val query: String) {
        private var cursor = 0
        private val matches = mutableListOf<SearchMatch>()

        // Raw-source positions that render as a LaTeX formula image (inline `$…$` + display `$$…$$`).
        // Their *latex source* is excluded from prose search text so math is never a match (it renders
        // as an image, not searchable text). The renderer's highlighter + scroll
        // skip the matching `JLatexAsyncDrawableSpan`s, so all three agree on the occurrence ordinals.
        private val mathMask: BooleanArray = BooleanArray(content.length).also { mask ->
            for (range in InlineMathDelimiters.mathRanges(content)) {
                for (pos in range) if (pos < mask.size) mask[pos] = true
            }
        }

        fun scan(doc: Node): List<SearchMatch> {
            var blockIndex = 0
            var node = doc.firstChild
            while (node != null) {
                // occurrenceInBlock is per top-level block. A table renders as one TextView PER CELL, so
                // its ordinal threads across cells in row-major order — the exact order TableBlockEntry
                // paints and PreviewScroll walks; every other block is a single chunk.
                if (node is TableBlock) {
                    val occurrence = Occurrence()
                    forEachCell(node) { cell -> scanChunk(cell, blockIndex, occurrence) }
                } else {
                    scanChunk(node, blockIndex, Occurrence())
                }
                blockIndex++
                node = node.next
            }
            return matches
        }

        /**
         * Assemble [root]'s visible text (the string Markwon renders into its TextView), then record
         * every case-insensitive occurrence of [query] in it. [occurrence] advances per hit (threaded
         * across a table's cells); the cursor advances so each hit keeps an exact raw start offset.
         */
        private fun scanChunk(root: Node, blockIndex: Int, occurrence: Occurrence) {
            val sb = StringBuilder()
            val rawOffsets = mutableListOf<Int>()
            appendVisible(root, sb, rawOffsets)
            val text = sb.toString()
            if (text.isEmpty()) return

            var from = 0
            while (true) {
                val idx = text.indexOf(query, from, ignoreCase = true)
                if (idx < 0) break
                matches.add(
                    SearchMatch(
                        content = text.substring(idx, idx + query.length),
                        startIndex = rawOffsets[idx],
                        adapterPosition = blockIndex,
                        occurrenceInBlock = occurrence.next(),
                    ),
                )
                from = idx + 1 // step by 1 so self-overlapping queries ("aa" in "aaa") are all found
            }
        }

        /**
         * Append [node]'s visible contribution to [sb], pushing the raw-source offset of each appended
         * char to [offsets] in lockstep. Mirrors Markwon's default TextView rendering:
         *  - text / inline code / fenced+indented code / inline+block HTML → their literal text;
         *  - soft break → a space, hard break → a newline (what Markwon draws);
         *  - image → nothing (its alt text is not painted; counting it would be a phantom match);
         *  - anything else → recurse (paragraphs, headings, links, emphasis, table cells…).
         * Link/image *destinations* are node properties, never visited, so they never count.
         */
        private fun appendVisible(node: Node, sb: StringBuilder, offsets: MutableList<Int>) {
            when (node) {
                is Image -> return // alt text is not rendered into the TextView
                // Prose Text is the only place `$…$`/`$$…$$` are parsed into math by the renderer, so
                // only it skips math; code/HTML render `$` literally → keep them searchable.
                is Text -> appendLiteral(node.literal, sb, offsets, skipMath = true)
                is Code -> appendLiteral(node.literal, sb, offsets)
                is FencedCodeBlock -> appendLiteral(node.literal, sb, offsets)
                is IndentedCodeBlock -> appendLiteral(node.literal, sb, offsets)
                is HtmlBlock -> appendLiteral(node.literal, sb, offsets)
                is HtmlInline -> appendLiteral(node.literal, sb, offsets)
                is SoftLineBreak -> appendBreak(' ', sb, offsets)
                is HardLineBreak -> appendBreak('\n', sb, offsets)
                else -> {
                    var child = node.firstChild
                    while (child != null) {
                        appendVisible(child, sb, offsets)
                        child = child.next
                    }
                }
            }
        }

        /**
         * Append [literal] verbatim, locating its real raw-source offset forward from the cursor. When
         * [skipMath], a char whose raw position renders as a LaTeX formula image is omitted from the
         * searchable text (the cursor still advances past it, keeping later offsets aligned).
         */
        private fun appendLiteral(
            literal: String,
            sb: StringBuilder,
            offsets: MutableList<Int>,
            skipMath: Boolean = false,
        ) {
            if (literal.isEmpty()) return
            // Bounded forward search: accept the match only within LOOKAHEAD of the cursor. A literal
            // that isn't verbatim near its true position (a decoded entity/escape) must NOT snap to a
            // distant identical string — that would corrupt every later offset in the block. On a miss,
            // fall back to the cursor so the rare offset is approximate but the walk never derails.
            val found = content.indexOf(literal, cursor)
            val base = if (found in cursor..(cursor + LOOKAHEAD)) found else cursor
            var inMath = false
            for (k in literal.indices) {
                val raw = base + k
                if (skipMath && raw < mathMask.size && mathMask[raw]) {
                    // Replace each contiguous math run with ONE U+FFFC (object replacement char) so the
                    // text around a formula doesn't merge into a phantom word (`foo$x$bar` stays
                    // `foo<FFFC>bar`, never `foobar`). U+FFFC never appears in a user query → unmatchable.
                    if (!inMath) {
                        sb.append('\uFFFC')
                        offsets.add(raw)
                        inMath = true
                    }
                    continue
                }
                inMath = false
                sb.append(literal[k])
                offsets.add(raw)
            }
            cursor = base + literal.length
        }

        /** Append the single whitespace char [rendered] for a line-break node, anchored at the raw `\n`. */
        private fun appendBreak(rendered: Char, sb: StringBuilder, offsets: MutableList<Int>) {
            // Same bounded search as appendLiteral: a far-away `\n` must not pull the cursor forward.
            val nl = content.indexOf('\n', cursor)
            val base = if (nl in cursor..(cursor + LOOKAHEAD)) nl else cursor
            sb.append(rendered)
            offsets.add(base)
            cursor = base + 1
        }
    }

    /** Per-block occurrence counter; [next] returns the current ordinal then advances. */
    private class Occurrence(private var value: Int = 0) {
        fun next(): Int = value++
    }

    private companion object {
        // Forward window for locating a literal's raw-source offset from the monotonic cursor. Large
        // enough to span a normal block (paragraph/cell/most code), small enough to cap a pathological
        // false match (an escape/entity literal) so it can't cascade across the document. Mirrors the
        // bound the previous block-offset locator used.
        const val LOOKAHEAD = 4096
    }
}

/** Visit every [TableCell] of [table] in row-major order (header row, then body rows; cells L→R). */
private fun forEachCell(table: TableBlock, action: (TableCell) -> Unit) {
    var section: Node? = table.firstChild
    while (section != null) {
        if (section is TableHead || section is TableBody) forEachRow(section, action)
        section = section.next
    }
}

private fun forEachRow(section: Node, action: (TableCell) -> Unit) {
    var row: Node? = section.firstChild
    while (row != null) {
        if (row is TableRow) forEachCellInRow(row, action)
        row = row.next
    }
}

private fun forEachCellInRow(row: TableRow, action: (TableCell) -> Unit) {
    var cell: Node? = row.firstChild
    while (cell != null) {
        if (cell is TableCell) action(cell)
        cell = cell.next
    }
}
