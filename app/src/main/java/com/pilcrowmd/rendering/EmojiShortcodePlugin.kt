// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.Paint
import com.pilcrowmd.domain.markdown.EmojiShortcode
import com.pilcrowmd.domain.markdown.EmojiShortcodes
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.inlineparser.InlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import org.commonmark.node.Node

/**
 * M-247: `:shortcode:` in prose paints as its emoji. The rule lives in [EmojiShortcodes] (domain),
 * shared with search, so the two replace exactly the same names; this only hooks it into Markwon.
 *
 * Parse-time only: the processor emits an [EmojiShortcode] node and never touches the source
 * (Safeguard 2). It runs on `:` inside the inline parser, so code spans, link destinations, HTML,
 * autolinks and maths, which other processors consume whole, never reach it, and `\:` is consumed
 * by the backslash processor first. With [EmojiShortcodes.NONE] it declines every `:`.
 */
class EmojiShortcodePlugin(private val shortcodes: EmojiShortcodes) : AbstractMarkwonPlugin() {

    override fun configure(registry: MarkwonPlugin.Registry) {
        registry.require(MarkwonInlineParserPlugin::class.java) {
            it.factoryBuilder().addInlineProcessor(EmojiShortcodeInlineProcessor(shortcodes))
        }
    }

    /** The emoji alone is painted, which is exactly what search models for the node. */
    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(EmojiShortcode::class.java) { visitor, node -> visitor.builder().append(node.emoji) }
    }
}

/** The inline half of [EmojiShortcodePlugin]: one `:alias:` becomes one [EmojiShortcode]. */
class EmojiShortcodeInlineProcessor(private val shortcodes: EmojiShortcodes) : InlineProcessor() {

    override fun specialCharacter(): Char = ':'

    override fun parse(): Node? {
        val match = shortcodes.matchAt(input, index) ?: return null
        index = match.range.last + 1 // consume through the closing `:`
        return EmojiShortcode(match.alias, match.emoji)
    }
}

/**
 * M-247: whether this device's fonts draw an emoji, as one glyph (`Paint.hasGlyph`, API 23+). Older
 * Android versions lack newer emoji, which would otherwise paint as empty boxes, so such a name stays
 * literal instead. The reading fonts carry no emoji, so the platform's fallback emoji font decides
 * for every font set alike, and the default paint asks the same question the reader's would.
 * Synchronized because a [Paint] is not safe to share across the parse and search threads.
 */
class EmojiGlyphs {
    private val paint = Paint()

    @Synchronized
    fun canDraw(emoji: String): Boolean = paint.hasGlyph(emoji)
}
