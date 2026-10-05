// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Node
import org.commonmark.node.Text
import java.net.URLDecoder
import java.util.IdentityHashMap
import java.util.Locale

/**
 * M-159: what `[text](#anchor)` means in a Markdown file — the ONE home of the heading-anchor rule.
 *
 * The rule is GitHub's, which is `github-slugger`: lower-case the heading's text, drop everything that
 * is not a letter, mark, digit, connector punctuation, space or hyphen, then turn each space into a
 * hyphen. Notes written for GitHub carry links built by that rule (CJK headings included, whose
 * letters stay), so the reader resolves them the same way instead of inventing its own numbering.
 *
 * Pure Kotlin/JVM, no Android and no I/O. [targets] numbers the headings with [AdapterBlocks], the
 * numbering the reader's adapter and the table of contents already share, so a link lands on the
 * block the adapter paints; [blockIndexOf] looks a tapped link up in that map.
 */
object HeadingAnchors {

    private val DROPPED = Regex("[^\\p{L}\\p{M}\\p{N}\\p{Pc} -]")

    /**
     * `github-slugger`'s base `slug()`: no collapsing of runs and no trimming, exactly as it does.
     * Uniqueness across a document is [targets]'s job, not this function's.
     */
    fun slug(text: String): String = text.lowercase(Locale.ROOT).replace(DROPPED, "").replace(' ', '-')

    /**
     * The text a heading's slug is made from: every text and inline-code literal in its subtree, in
     * document order — including inside emphasis, strong and links, but not an image's alt text.
     * GitHub slugs the rendered `textContent`, which keeps code spans.
     */
    fun plainText(heading: Heading): String {
        val sb = StringBuilder()
        heading.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                sb.append(text.literal)
            }

            override fun visit(code: Code) {
                sb.append(code.literal)
            }

            // An image's alt text is not in GitHub's rendered text, so it is not in the slug.
            override fun visit(image: Image) = Unit
        })
        return sb.toString()
    }

    /**
     * Anchor → adapter position for every top-level heading of [document], numbered by
     * [AdapterBlocks.of]. A repeated slug gets `-1`, `-2`, … appended, skipping any suffixed form
     * already taken (`github-slugger`'s rule), so `A`, `A`, `A-1` map `a`, `a-1`, `a-1-1`. Every heading
     * counts towards that numbering in document order, as on GitHub, but only a top-level one is a
     * target: a heading nested in a quote or a list item is not a block the reader can scroll to.
     */
    fun targets(document: Node): Map<String, Int> {
        val blockIndex = IdentityHashMap<Node, Int>()
        AdapterBlocks.of(document).forEachIndexed { index, node -> blockIndex[node] = index }
        val occurrences = HashMap<String, Int>()
        val result = LinkedHashMap<String, Int>()
        document.accept(object : AbstractVisitor() {
            override fun visit(heading: Heading) {
                val anchor = unique(slug(plainText(heading)), occurrences)
                blockIndex[heading]?.let { result[anchor] = it }
            }
        })
        return result
    }

    private fun unique(base: String, occurrences: MutableMap<String, Int>): String {
        var anchor = base
        while (occurrences.containsKey(anchor)) {
            val count = occurrences.getValue(base) + 1
            occurrences[base] = count
            anchor = "$base-$count"
        }
        occurrences[anchor] = 0
        return anchor
    }

    /**
     * The adapter position the in-document link [link] names, or null when it is not a `#` link or
     * names no heading in [targets]. [link] is the raw destination: `#61-编码前先思考` or its
     * percent-encoded form. The leading `#` is dropped, the rest percent-decoded as UTF-8 (a literal
     * `+` stays `+`; undecodable text is used as written) and lower-cased, as GitHub matches anchors
     * case-insensitively. A bare `#` names nothing.
     */
    fun blockIndexOf(targets: Map<String, Int>, link: String): Int? {
        if (!link.startsWith("#")) return null
        val anchor = decode(link.substring(1)).lowercase(Locale.ROOT)
        if (anchor.isEmpty()) return null
        return targets[anchor]
    }

    private fun decode(raw: String): String = try {
        // URLDecoder is form-decoding: it would turn '+' into a space, so protect it first.
        URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") // the Charset overload is API 33
    } catch (ignored: IllegalArgumentException) {
        raw
    }
}
