// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

/**
 * M-331: what inline HTML paints, which is never its markup. One instance models one render (a block,
 * or a table cell), because the reader's HTML support (Markwon's `MarkwonHtmlParserImpl`) keeps state
 * across the tags of a render:
 *  - a block-level tag (`div`, `p`, `h1`, `li`, ...) starts on a new line: a '\n' unless the text so
 *    far is empty or already ends with one. A closing `</p>` adds a '\n', and a new tag opened while a
 *    `<p>` is open closes it with a '\n' first;
 *  - once a block-level tag closes around some content, the NEXT inline tag, non-block tag or CDATA
 *    starts on a new line too. Text after the closing tag is drawn by Markdown, not by the HTML
 *    support, so `a<div>x</div>b` paints "a\nxb";
 *  - `<br>` paints '\n', `<img>` one picture (U+FFFC; its alt text is not counted, as for a Markdown
 *    image), an EMPTY `<iframe></iframe>` a no-break space, and a CDATA section its text with runs of
 *    whitespace collapsed to one space. Every other tag, comment or declaration paints nothing.
 * Text between an opening and a closing tag arrives as separate Text nodes and is counted there.
 */
internal class InlineHtmlPaint {

    private class OpenBlock(val name: String, val start: Int)

    private val openBlocks = mutableListOf<OpenBlock>()
    private var previousIsBlock = false
    private var insidePre = false

    /** The text [literal] paints when [painted] is what the same render has painted before it. */
    fun paint(literal: String, painted: CharSequence): String {
        val out = Output(painted)
        val closing = CLOSING_TAG.find(literal)
        val opening = OPENING_TAG.find(literal)
        when {
            literal.startsWith(CDATA_OPEN) && literal.endsWith(CDATA_CLOSE) ->
                characters(literal.substring(CDATA_OPEN.length, literal.length - CDATA_CLOSE.length), out)
            closing != null -> end(closing.groupValues[1].lowercase(), out)
            opening != null -> start(opening.groupValues[1].lowercase(), literal.trimEnd().endsWith("/>"), out)
        }
        return out.added.toString()
    }

    private fun start(name: String, selfClosing: Boolean, out: Output) {
        val void = name in VOID_TAGS || selfClosing
        if (name in INLINE_TAGS) {
            newLineIfPreviousWasBlock(out)
            if (void) out.append(emptyReplacement(name))
            return
        }
        val current = openBlocks.lastOrNull()?.name
        if (current == "p") {
            out.append("\n")
            openBlocks.removeAt(openBlocks.lastIndex)
        } else if (name == "li" && current == "li") {
            openBlocks.removeAt(openBlocks.lastIndex)
        }
        if (name in BLOCK_TAGS) {
            insidePre = name == "pre"
            out.ensureNewLine()
        } else {
            newLineIfPreviousWasBlock(out)
        }
        if (void) out.append(emptyReplacement(name)) else openBlocks.add(OpenBlock(name, out.length))
    }

    /** A closing inline tag paints nothing; a closing block tag closes the nearest open one of its name. */
    private fun end(name: String, out: Output) {
        if (name in INLINE_TAGS) return
        val index = openBlocks.indexOfLast { it.name == name }
        if (index < 0) return
        val block = openBlocks[index]
        if (name == "pre") insidePre = false
        if (block.start == out.length) out.append(emptyReplacement(name))
        if (block.start != out.length) previousIsBlock = name in BLOCK_TAGS
        if (name == "p") out.append("\n")
        while (openBlocks.size > index) openBlocks.removeAt(openBlocks.lastIndex)
    }

    private fun characters(data: String, out: Output) {
        if (insidePre) {
            out.append(data)
            return
        }
        newLineIfPreviousWasBlock(out)
        val before = out.length
        var pendingSpace = false
        for (char in data) {
            if (Character.isWhitespace(char)) {
                pendingSpace = true
                continue
            }
            if (pendingSpace && out.length > 0 && !Character.isWhitespace(out.last())) out.append(" ")
            pendingSpace = false
            out.append(char.toString())
        }
        if (pendingSpace && out.length > before) out.append(" ")
    }

    private fun newLineIfPreviousWasBlock(out: Output) {
        if (previousIsBlock) {
            out.ensureNewLine()
            previousIsBlock = false
        }
    }

    /** The text painted so far in the render, plus what this tag adds to it. */
    private class Output(private val painted: CharSequence) {
        val added = StringBuilder()
        val length: Int get() = painted.length + added.length

        fun last(): Char = if (added.isNotEmpty()) added.last() else painted.last()

        fun append(text: String) {
            added.append(text)
        }

        fun ensureNewLine() {
            if (length > 0 && last() != '\n') added.append('\n')
        }
    }

    private companion object {
        const val CDATA_OPEN = "<![CDATA["
        const val CDATA_CLOSE = "]]>"
        val OPENING_TAG = Regex("^<([A-Za-z][A-Za-z0-9-]*)")
        val CLOSING_TAG = Regex("^</([A-Za-z][A-Za-z0-9-]*)")

        // The reader's tag classes, as Markwon's HTML parser defines them.
        val INLINE_TAGS = setOf(
            "a", "abbr", "acronym", "b", "bdo", "big", "br", "button", "cite", "code", "dfn", "em", "i", "img",
            "input", "kbd", "label", "map", "object", "q", "samp", "script", "select", "small", "span",
            "strong", "sub", "sup", "textarea", "time", "tt", "var",
        )
        val VOID_TAGS = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "keygen", "link", "meta", "param",
            "source", "track", "wbr",
        )
        val BLOCK_TAGS = setOf(
            "address", "article", "aside", "blockquote", "canvas", "dd", "div", "dl", "dt", "fieldset",
            "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup",
            "hr", "li", "main", "nav", "noscript", "ol", "output", "p", "pre", "section", "table", "tfoot",
            "ul", "video",
        )

        /** What an empty or void tag paints: br a line break, img a picture, iframe a no-break space. */
        fun emptyReplacement(name: String): String = when (name) {
            "br" -> "\n"
            "img" -> "￼"
            "iframe" -> " "
            else -> ""
        }
    }
}
