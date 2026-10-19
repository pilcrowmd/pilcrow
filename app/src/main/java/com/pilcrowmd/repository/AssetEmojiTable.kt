// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.util.Log
import com.pilcrowmd.domain.markdown.EmojiShortcodes
import com.pilcrowmd.domain.markdown.EmojiTable
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * M-247: the gemoji alias table, read from the bundled `emoji/shortcodes.tsv` (built by
 * `docs/tools/gemoji-to-shortcodes.py`), narrowed to the emoji [canDraw] says this device can draw.
 *
 * The file is read once, on the first lookup, and each emoji is checked with [canDraw] once. A
 * missing, unreadable or malformed file yields an empty table: every `:name:` stays literal and
 * nothing throws (Safeguard 3). A [canDraw] that throws counts as "cannot draw".
 */
class AssetEmojiTable(private val open: () -> InputStream, private val canDraw: (String) -> Boolean) : EmojiTable {

    private val aliases: Map<String, String> by lazy { read(open) }
    private val drawable = ConcurrentHashMap<String, Boolean>()

    override fun emojiFor(alias: String): String? {
        val emoji = aliases[alias] ?: return null
        val ok = drawable.getOrPut(emoji) { runCatching { canDraw(emoji) }.getOrDefault(false) }
        return emoji.takeIf { ok }
    }

    companion object {
        const val ASSET_PATH = "emoji/shortcodes.tsv"

        /**
         * `alias<TAB>emoji` per line; a line starting with `#` is a comment. A line whose alias the
         * rule could never match, or with no emoji, is skipped rather than trusted.
         */
        fun read(open: () -> InputStream): Map<String, String> = runCatching {
            open().bufferedReader().useLines { lines ->
                lines.filterNot { it.isEmpty() || it.startsWith("#") }
                    .mapNotNull { line ->
                        val tab = line.indexOf('\t')
                        val alias = if (tab > 0) line.substring(0, tab) else return@mapNotNull null
                        val emoji = line.substring(tab + 1)
                        (alias to emoji).takeIf { emoji.isNotEmpty() && alias.all(EmojiShortcodes::isAliasChar) }
                    }
                    .toMap()
            }
        }.getOrElse { e ->
            Log.w("AssetEmojiTable", "emoji shortcodes unavailable: ${e.message}")
            emptyMap()
        }
    }
}
