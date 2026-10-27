// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.CalloutBlock
import com.pilcrowmd.domain.markdown.CodeBlockPiece
import com.pilcrowmd.domain.markdown.Details
import com.pilcrowmd.domain.markdown.EmojiShortcodeMatch
import com.pilcrowmd.domain.markdown.EmojiShortcodes
import com.pilcrowmd.domain.markdown.FootnoteReference
import com.pilcrowmd.domain.markdown.InlineMathDelimiters
import com.pilcrowmd.domain.markdown.MERMAID_LANGUAGE
import com.pilcrowmd.domain.markdown.PlainTextChunks
import com.pilcrowmd.domain.markdown.SourceAlignment
import com.pilcrowmd.domain.markdown.TooDeepDocument
import com.pilcrowmd.domain.markdown.fenceLanguage
import com.pilcrowmd.domain.model.SearchMatch
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.Block
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
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
 *
 * [emoji] must be the instance the renderer was built with (M-247): a `:shortcode:` the reader paints
 * as an emoji is modelled as that emoji, so its letters are not a match, and one it leaves literal
 * is modelled literally.
 */
class SearchMarkdownUseCase(
    private val parseHeadingsUseCase: ParseMarkdownHeadingsUseCase,
    private val emoji: EmojiShortcodes = EmojiShortcodes.NONE,
) {

    /**
     * Find all search matches in the markdown content.
     * Case-insensitive; returns [SearchMatch] objects with the rendered matched text, raw-source start
     * index, adapter position (block), and per-block occurrence ordinal (for focused highlighting).
     */
    fun findSearchMatches(content: String, query: String): List<SearchMatch> {
        if (query.isEmpty()) return emptyList()
        val doc = parseHeadingsUseCase.parseDocument(content) ?: return emptyList()
        // A document nested too deep to render is painted as plain-text chunks (`ReaderTree`), so it
        // is searched over the same chunks: its parse is empty, but every word of it is on screen.
        if (doc is TooDeepDocument) return findPlainSearchMatches(PlainTextChunks.split(content), query)
        return BlockScanner(content, query, emoji).scan(doc)
    }

    /**
     * Plain-mode search: matches over the literal chunk texts — the visible text IS the
     * source, so Markdown syntax is findable (`# alpha` matches). [chunkLiterals] must be the same
     * split the plain render uses (adapter position = chunk index); chunks join with "\n", so raw
     * start indices are cumulative. Case-insensitive and steps by ONE char (overlaps counted) —
     * the same rules as the markdown scan and the TextView highlighter, so counts and focus
     * ordinals never diverge.
     */
    fun findPlainSearchMatches(chunkLiterals: List<String>, query: String): List<SearchMatch> {
        if (query.isEmpty()) return emptyList()
        val matches = mutableListOf<SearchMatch>()
        var chunkStart = 0
        chunkLiterals.forEachIndexed { position, literal ->
            var index = literal.indexOf(query, ignoreCase = true)
            var ordinal = 0
            while (index >= 0) {
                matches += SearchMatch(
                    content = literal.substring(index, index + query.length),
                    startIndex = chunkStart + index,
                    adapterPosition = position,
                    occurrenceInBlock = ordinal++,
                )
                index = literal.indexOf(query, index + 1, ignoreCase = true)
            }
            chunkStart += literal.length + 1 // +1: the newline consumed at the chunk boundary
        }
        return matches
    }

    /**
     * Walks the parsed document once, accumulating matches over each block's assembled visible text.
     * Holds the monotonic raw-source [cursor] and the running [matches] as fields so the per-block and
     * per-node helpers stay small.
     */
    private class BlockScanner(private val content: String, private val query: String, emoji: EmojiShortcodes) {
        // Never past the end (M-401): every miss advances it from wherever it is, so a cursor that
        // overran would hand later reads an offset outside the document.
        private var cursor = 0
            set(value) {
                field = value.coerceAtMost(content.length)
            }
        private val matches = mutableListOf<SearchMatch>()

        // Whether the walk is currently inside a contiguous run of masked (formula) source, so the
        // run collapses to exactly ONE U+FFFC. This is per math RUN, not per node: the parity parser
        // has no inline-math processor, so `$a **b** c$` arrives as three inline nodes (Text /
        // StrongEmphasis / Text) while the renderer paints one formula image. Scoped to the scanner
        // rather than to appendLiteral so a run that spans several nodes still yields one
        // placeholder — otherwise search models three images where the TextView paints one.
        // Reset at the start of every chunk, at an UNMASKED line break, and at an image; a MASKED
        // line break is interior to a multi-line display formula and keeps the run open.
        private var inMath = false

        // What inline HTML paints depends on the tags before it in the same render (block or cell).
        private var html = InlineHtmlPaint()

        // Raw-source positions that render as a LaTeX formula image (inline `$…$` + display `$$…$$`).
        // Their *latex source* is excluded from prose search text so math is never a match (it renders
        // as an image, not searchable text). The renderer's highlighter + scroll
        // skip the matching `JLatexAsyncDrawableSpan`s, so all three agree on the occurrence ordinals.
        private val mathMask: BooleanArray = BooleanArray(content.length).also { mask ->
            for (range in InlineMathDelimiters.mathRanges(content)) {
                for (pos in range) if (pos < mask.size) mask[pos] = true
            }
        }

        // M-247: the `:shortcode:`s the renderer paints as emoji. Its processor only sees prose, so
        // only prose Text consults this.
        private val shortcodeIndex = ShortcodeIndex(content, emoji)

        fun scan(doc: Node): List<SearchMatch> {
            // Numbered as the adapter numbers its items: a link reference definition has none (M-214).
            AdapterBlocks.of(doc).forEachIndexed { blockIndex, node ->
                // occurrenceInBlock is per top-level block. A table renders as one TextView PER CELL, so
                // its ordinal threads across cells in row-major order — the exact order TableBlockEntry
                // paints and PreviewScroll walks; every other block is a single chunk.
                if (node is TableBlock) {
                    val occurrence = Occurrence()
                    forEachCell(node) { cell -> scanChunk(cell, blockIndex, occurrence) }
                } else {
                    scanChunk(node, blockIndex, Occurrence())
                }
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
            inMath = false // each chunk (block, or table cell) starts outside any math run
            html = InlineHtmlPaint()
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
         *  - text / inline code / fenced+indented code / block HTML → their literal text;
         *  - inline HTML → only what it paints, never its markup (see [InlineHtmlPaint]);
         *  - soft break → a space, hard break → a newline (what Markwon draws);
         *  - image → nothing (its alt text is not painted; counting it would be a phantom match);
         *  - anything else → recurse (paragraphs, headings, links, emphasis, table cells…).
         * Link/image *destinations* are node properties, never visited, so they never count.
         */
        // One flat dispatch on the node type: each `is X ->` arm counts as a branch and none nests.
        // M-33's HtmlInline if/else and M-228's fence check took it past detekt's limit.
        @Suppress("CyclomaticComplexMethod")
        private fun appendVisible(node: Node, sb: StringBuilder, offsets: MutableList<Int>) {
            when (node) {
                // Alt text is not rendered into the TextView. The early return skips appendLiteral /
                // appendBreak, so it must end the math run itself — otherwise two formulas separated
                // only by an image would merge into one placeholder (a leak the pre-hoist local
                // `inMath` could not have).
                is Image -> {
                    inMath = false
                    return
                }
                // Prose Text is the only place `$…$`/`$$…$$` are parsed into math by the renderer, so
                // it always skips math. Code and inline HTML render `$` literally and stay searchable,
                // unless the whole node sits inside a formula (M-33): then it is part of the image.
                is Text -> {
                    val autolink = shortcodeIndex.isAutolinkText(node, locate(node.literal, Source.TEXT))
                    val kind = if (autolink) Source.AUTOLINK_TEXT else Source.TEXT
                    appendLiteral(node.literal, sb, offsets, kind, skipMath = true)
                }
                is Code -> {
                    val opensInMath = codeOpensInMath(content, mathMask, locate(node.literal, Source.CODE_SPAN))
                    appendLiteral(node.literal, sb, offsets, Source.CODE_SPAN, skipMath = opensInMath)
                }
                // A top-level Mermaid fence is drawn as a picture, so its source is not on screen (M-228).
                // One nested in a list or quote is painted as text inside that block, so it still counts, and so
                // does a piece of a fence cut for being too long to draw (S8-A31): that is shown as source.
                is FencedCodeBlock -> if (!isDrawnAsPicture(node)) appendLiteral(node.literal, sb, offsets)
                is IndentedCodeBlock -> appendLiteral(node.literal, sb, offsets)
                is HtmlBlock -> appendHtmlBlock(node, sb, offsets)
                // M-161: a callout paints its title where its marker was, then its content. M-227 /
                // M-251: the title is its own when it has one, and nothing else on the header line is
                // painted (fold sign, `!!!` type and quotes, `:::` type), so the cursor skips the whole
                // line: a body that repeats the title must not be placed inside it.
                is CalloutBlock -> {
                    appendAnchored(node.paintedTitle + "\n", node.marker, sb, offsets)
                    cursor = lineEnd(content, cursor)
                    appendChildren(node, sb, offsets)
                }
                // M-33: a tag opens with '<' and a formula with '$', so the tag is inside a formula
                // exactly when its first character is, and then joins the formula's placeholder.
                is HtmlInline -> if (mathMask.getOrElse(locate(node.literal)) { false }) {
                    appendLiteral(node.literal, sb, offsets, skipMath = true)
                } else {
                    appendAnchored(html.paint(node.literal, sb), node.literal, sb, offsets)
                }
                // A resolved footnote marker paints its ORDINAL, so that is what search must see —
                // appending nothing would under-model the block, and appending the raw `[^label]`
                // would count text the TextView never draws (the phantom-match class removed).
                is FootnoteReference -> appendFootnoteMarker(node, sb, offsets)
                is SoftLineBreak -> appendBreak(' ', sb, offsets)
                is HardLineBreak -> appendBreak('\n', sb, offsets)
                else -> appendChildren(node, sb, offsets)
            }
        }

        /**
         * M-28: two blocks inside one container (the paragraphs of a quote, the items of a list) are
         * painted on separate lines, so a '\n' goes between them. Without it `> one` / `> two` was
         * modelled as "onetwo" and a query straddling the join counted a match nothing paints. The
         * separator consumes no source: it is anchored at the cursor, which does not move. A query is
         * a single line, so the '\n' can only remove such straddling matches, never add one.
         */
        private fun appendChildren(node: Node, sb: StringBuilder, offsets: MutableList<Int>) {
            var child = node.firstChild
            while (child != null) {
                if (child is Block && child.previous != null) {
                    inMath = false
                    sb.append('\n')
                    offsets.add(cursor)
                }
                appendVisible(child, sb, offsets)
                child = child.next
            }
        }

        /**
         * M-161: a `<details>` header paints its summary (and any body text in the same block) instead
         * of the raw tags, and a lone `</details>` paints nothing. Everything else keeps the literal.
         */
        private fun appendHtmlBlock(node: HtmlBlock, sb: StringBuilder, offsets: MutableList<Int>) {
            val header = Details.parseHeader(node.literal)
            when {
                header != null -> {
                    val painted = if (header.inlineBody.isEmpty()) {
                        header.summary
                    } else {
                        header.summary + "\n" +
                            header.inlineBody
                    }
                    appendAnchored(painted, node.literal, sb, offsets)
                }
                // Nothing painted; only the cursor moves past the tag.
                Details.isCloseBlock(node.literal) -> cursor = locate(node.literal) + node.literal.length
                else -> appendLiteral(node.literal, sb, offsets)
            }
        }

        /**
         * Append [painted] — text the renderer draws in place of [source] — with every character
         * anchored on [source]'s raw start, then move the cursor past [source]. The same anchoring
         * [appendFootnoteMarker] uses: painted characters that have no source position of their own.
         */
        private fun appendAnchored(painted: String, source: String, sb: StringBuilder, offsets: MutableList<Int>) {
            val base = locate(source)
            inMath = false
            for (char in painted) {
                sb.append(char)
                offsets.add(base)
            }
            cursor = base + source.length
        }

        private fun locate(source: String, kind: Source = Source.VERBATIM): Int =
            placeLiteral(content, source, cursor, kind).start

        /**
         * Append [literal] verbatim, locating its real raw-source offset forward from the cursor (see
         * [placeLiteral]; [kind] says what the parser may have changed in it). When [skipMath], a char
         * whose raw position renders as a LaTeX formula image is omitted from the searchable text (the
         * cursor still advances past it, keeping later offsets aligned). In prose [Source.TEXT], a
         * `:name:` the renderer paints as an emoji is modelled as that emoji.
         */
        private fun appendLiteral(
            literal: String,
            sb: StringBuilder,
            offsets: MutableList<Int>,
            kind: Source = Source.VERBATIM,
            skipMath: Boolean = false,
        ) {
            if (literal.isEmpty()) return
            val placed = placeLiteral(content, literal, cursor, kind)
            val base = placed.start
            // M-247: where each char sits in the source, when a shortcode could be among them.
            val shortcodeRaws = if (kind == Source.TEXT) shortcodeIndex.offsets(literal, placed, cursor) else null
            val raws = shortcodeRaws ?: placed.raws
            var k = 0
            while (k < literal.length) {
                // A missed literal is placed from the cursor, so near the end it can run past it (M-401).
                val raw = raws?.get(k) ?: minOf(base + k, content.length)
                val shortcode = shortcodeRaws?.let { shortcodeIndex.at(raw, room = literal.length - k) }
                if (skipMath && raw < mathMask.size && mathMask[raw]) {
                    // Replace each contiguous math run with ONE U+FFFC (object replacement char) so the
                    // text around a formula doesn't merge into a phantom word (`foo$x$bar` stays
                    // `foo<FFFC>bar`, never `foobar`). U+FFFC never appears in a user query → unmatchable.
                    if (!inMath) {
                        sb.append('\uFFFC')
                        offsets.add(raw)
                        inMath = true
                    }
                } else if (shortcode != null) {
                    // The emoji it paints, every char anchored on the opening `:` as a footnote
                    // marker's digits are (appendFootnoteMarker); then on past the closing `:`.
                    inMath = false
                    for (char in shortcode.emoji) {
                        sb.append(char)
                        offsets.add(raw)
                    }
                    k += shortcode.range.last - shortcode.range.first
                } else {
                    inMath = false
                    sb.append(literal[k])
                    offsets.add(raw)
                }
                k++
            }
            cursor = raws?.get(literal.length) ?: (base + literal.length)
        }

        /**
         * Append the ordinal a [FootnoteReference] paints, anchored on the raw `[` of its
         * `[^label]` source.
         *
         * Two rules, both load-bearing:
         *  - **every emitted character anchors at the same `[` offset.** A two-digit ordinal must not
         *    fabricate offsets for source positions that draw no separate glyph — the same anchoring
         *    the math `U+FFFC` already uses.
         *  - **the cursor advances past the closing `]`.** The marker replaced source the walk would
         *    otherwise never consume, so without this the next literal's bounded forward search has to
         *    bridge the marker and could snap to a duplicate substring beyond it.
         *
         * A marker inside a formula contributes nothing: the surrounding math run already collapses to
         * one `U+FFFC`, and adding digits there would make a formula's interior searchable.
         */
        private fun appendFootnoteMarker(node: FootnoteReference, sb: StringBuilder, offsets: MutableList<Int>) {
            val source = "[^" + node.label + "]"
            val found = content.indexOf(source, cursor)
            val base = if (found in cursor..(cursor + LOOKAHEAD)) found else cursor
            if (base < mathMask.size && mathMask[base]) {
                cursor = base + source.length
                return
            }
            inMath = false
            for (digit in node.ordinal.toString()) {
                sb.append(digit)
                offsets.add(base)
            }
            cursor = base + source.length
        }

        /**
         * Append the single whitespace char [rendered] for a line-break node, anchored at the raw `\n`
         * — unless that newline is itself inside a formula, in which case it belongs to the math run.
         *
         * Both branches matter. An **unmasked** newline ends the run: inline `$…$` is single-line, so a
         * formula closing line 1 and another opening line 2 are two runs and must not merge. A
         * **masked** newline is interior to a multi-line display `$$…$$` block (its range spans
         * newlines), which the renderer paints as ONE image — so the break is part of the run, not
         * searchable whitespace. Appending it unconditionally would both split the placeholder and
         * make the formula's interior line breaks findable, a phantom hit that can be neither
         * highlighted nor scrolled to.
         */
        private fun appendBreak(rendered: Char, sb: StringBuilder, offsets: MutableList<Int>) {
            // Same bounded search as appendLiteral: a far-away `\n` must not pull the cursor forward.
            val nl = content.indexOf('\n', cursor)
            val base = if (nl in cursor..(cursor + LOOKAHEAD)) nl else cursor
            if (base < mathMask.size && mathMask[base]) {
                if (!inMath) {
                    sb.append('\uFFFC')
                    offsets.add(base)
                    inMath = true
                }
            } else {
                inMath = false
                sb.append(rendered)
                offsets.add(base)
            }
            cursor = base + 1
        }
    }

    /**
     * M-247: the `:shortcode:`s of one document that the renderer paints as emoji, keyed by the raw
     * offset of the opening `:`, and where a prose literal's characters sit in the source.
     */
    private class ShortcodeIndex(private val content: String, emoji: EmojiShortcodes) {
        private val byStart: Map<Int, EmojiShortcodeMatch> = emoji.ranges(content).associateBy { it.range.first }

        /**
         * The raw offset of each char of [literal] plus one past its end, or null when no shortcode can
         * be in it. A verbatim literal starts at its [placed] start; one with an escape or entity in it
         * (`\:`, `&amp;`) is aligned char by char, so an escaped `:` never opens one.
         */
        fun offsets(literal: String, placed: Placed, cursor: Int): IntArray? = when {
            byStart.isEmpty() || ':' !in literal -> null
            placed.raws != null -> placed.raws
            placed.verbatim -> IntArray(literal.length + 1) { placed.start + it }
            else -> SourceAlignment.align(content, literal, cursor, LOOKAHEAD)
        }

        /** The shortcode opening at [raw], when all of it lies within the [room] chars left in the literal. */
        fun at(raw: Int, room: Int): EmojiShortcodeMatch? =
            byStart[raw]?.takeIf { it.range.last - it.range.first < room }

        /**
         * An autolink's `<url>` is consumed whole by the renderer's autolink processor, so a `:name:` in
         * it stays literal there. Its text, located at [at], is the only [Text] that directly follows `<`.
         */
        fun isAutolinkText(node: Text, at: Int): Boolean =
            node.parent is Link && at in 1..content.length && content[at - 1] == '<'
    }

    /** Per-block occurrence counter; [next] returns the current ordinal then advances. */
    private class Occurrence(private var value: Int = 0) {
        fun next(): Int = value++
    }
}

// Forward window for locating a literal's raw-source offset from the monotonic cursor. Large
// enough to span a normal block (paragraph/cell/most code), small enough to cap a pathological
// false match (an escape/entity literal) so it can't cascade across the document. Mirrors the
// bound the previous block-offset locator used.
private const val LOOKAHEAD = 4096

/**
 * M-33: whether the inline code span whose text starts at [codeStart] lies inside a formula, decided
 * by its OPENING backtick, not by its characters. `$b `c` d$` paints one formula, so its code is part
 * of the image; `` `$x$` `` paints the code literally, and its `$x$` must stay searchable even though
 * the raw-source math scan behind [mathMask], which knows nothing of code spans, marks those
 * characters as maths. The code's text excludes the backticks and the one space each side may carry,
 * so step back over those spaces to the backtick.
 */
internal fun codeOpensInMath(content: String, mathMask: BooleanArray, codeStart: Int): Boolean {
    var pos = minOf(codeStart, content.length) - 1
    while (pos >= 0 && content[pos] == ' ') pos--
    return pos >= 0 && content[pos] == '`' && mathMask.getOrElse(pos) { false }
}

/** What the parser may have changed in a literal, so where to look for it in the source (M-401). */
private enum class Source {
    /** Copied unchanged: code blocks, HTML. */
    VERBATIM,

    /** Prose [Text]: escapes and entities are decoded, and `:name:`s may paint as emoji. */
    TEXT,

    /** An autolink's [Text]: placed as [TEXT], but the renderer leaves its `:name:`s literal. */
    AUTOLINK_TEXT,

    /** An inline [Code] span: line breaks become spaces. */
    CODE_SPAN,
}

/** A literal's raw [start], and each char's raw offset when they are not simply consecutive from it. */
private class Placed(val start: Int, val raws: IntArray?, val verbatim: Boolean)

/**
 * Where [literal] starts in [content], searching forward from [cursor]. Bounded: a match is accepted
 * only within LOOKAHEAD of the cursor. A literal that isn't verbatim near its true position must NOT
 * snap to a distant identical string — that would corrupt every later offset in the block. On a miss,
 * fall back to the cursor so the rare offset is approximate but the walk never derails.
 *
 * M-401: a literal the parser changed (a [Source.TEXT]'s `&lt;` → `<` or `\*` → `*`, a
 * [Source.CODE_SPAN]'s line break → space) can also occur verbatim further on, and snapping to that
 * copy pushed the cursor past its real source, then past the end of the document. Its real start is
 * the first one from the cursor whose source produces it, so that is tried first.
 */
private fun placeLiteral(content: String, literal: String, cursor: Int, kind: Source): Placed {
    val found = content.indexOf(literal, cursor)
    val verbatim = found in cursor..(cursor + LOOKAHEAD)
    val window = if (verbatim) found - cursor - 1 else LOOKAHEAD
    val aligned = when {
        found == cursor || kind == Source.VERBATIM -> null
        kind == Source.CODE_SPAN -> SourceAlignment.alignCodeSpan(content, literal, cursor, window)
        else -> SourceAlignment.align(content, literal, cursor, window)
    }
    return when {
        aligned != null -> Placed(aligned.first(), aligned, verbatim = false)
        verbatim -> Placed(found, null, verbatim = true)
        else -> Placed(cursor, null, verbatim = false)
    }
}

/** The index of the line break ending the line that holds [from], or the end of [content]. */
private fun lineEnd(content: String, from: Int): Int =
    content.indexOf('\n', from).let { if (it < 0) content.length else it }

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

/** Whether [block] is a top-level Mermaid fence, which the reader draws as a picture, not as text. */
private fun isDrawnAsPicture(block: FencedCodeBlock): Boolean =
    block !is CodeBlockPiece && block.parent is Document && fenceLanguage(block.info) == MERMAID_LANGUAGE
