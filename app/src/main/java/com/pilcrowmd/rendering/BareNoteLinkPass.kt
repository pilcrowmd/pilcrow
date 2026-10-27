// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import com.pilcrowmd.domain.folders.BareNoteLinks
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text

/**
 * M-160 (K13/K14): makes `[label](Note 2.md)` and `<Note 2.md>`, which strict Markdown leaves as plain
 * text, into links, but ONLY those whose destination is in [linkBareNotes]'s `resolvable` set (the
 * resolver found a note for it). Everything else stays exactly as it was. Only the parsed tree
 * changes; the file never does.
 *
 * It is a pass over a finished parse tree, run by `PrepareReaderModelUseCase`, not a parser plugin:
 * the set is known only after the note is on screen, and the PDF path never runs it.
 *
 * Splits each plain Text of [document] around the candidates in [resolvable]; never inside a link or an
 * image. A K14 name the parser read as inline HTML (`<Note two.md>` looks like a tag with an attribute)
 * is replaced only when the WHOLE tag is that one candidate; any other inline HTML is left as it was.
 */
internal fun linkBareNotes(document: Node, resolvable: Set<String>) {
    if (resolvable.isEmpty()) return
    val texts = mutableListOf<Text>()
    val htmls = mutableListOf<HtmlInline>()
    document.accept(object : AbstractVisitor() {
        override fun visit(text: Text) {
            texts += text
        }

        override fun visit(htmlInline: HtmlInline) {
            htmls += htmlInline
        }

        // Not inside an existing link or an image's alt text. Inline code holds no Text.
        override fun visit(link: Link) = Unit

        override fun visit(image: Image) = Unit
    })
    texts.forEach { splitAround(it, resolvable) }
    htmls.forEach { replaceIfNoteName(it, resolvable) }
}

private fun replaceIfNoteName(html: HtmlInline, resolvable: Set<String>) {
    val literal = html.literal
    val bare = BareNoteLinks.find(literal).singleOrNull() ?: return
    if (bare.start != 0 || bare.end != literal.length || bare.destination !in resolvable) return
    html.insertBefore(Link(bare.destination, null).apply { appendChild(Text(bare.label)) })
    html.unlink()
}

private fun splitAround(text: Text, resolvable: Set<String>) {
    val literal = text.literal
    val found = BareNoteLinks.find(literal).filter { it.destination in resolvable }
    if (found.isEmpty()) return
    var at = 0
    for (bare in found) {
        if (bare.start > at) text.insertBefore(Text(literal.substring(at, bare.start)))
        text.insertBefore(Link(bare.destination, null).apply { appendChild(Text(bare.label)) })
        at = bare.end
    }
    if (at < literal.length) text.insertBefore(Text(literal.substring(at)))
    text.unlink()
}
