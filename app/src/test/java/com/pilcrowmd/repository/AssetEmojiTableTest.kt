// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.components.licenseTextFor
import com.pilcrowmd.ui.components.loadDependencies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * M-247: the bundled gemoji table loads whole, and a table that cannot be read is empty, never a
 * crash (Safeguard 3). Also that the data's MIT notice is in Settings, Licences.
 */
@RunWith(RobolectricTestRunner::class)
class AssetEmojiTableTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val asset = { context.assets.open(AssetEmojiTable.ASSET_PATH) }

    @Test
    fun `the bundled table has every gemoji v4_1_0 alias`() {
        val aliases = AssetEmojiTable.read(asset)
        assertEquals(GEMOJI_ALIASES, aliases.size)
        assertEquals("😄", aliases["smile"])
        assertEquals("👍", aliases["+1"])
        assertEquals("👎", aliases["-1"])
    }

    @Test
    fun `a missing asset is an empty table`() {
        val table = AssetEmojiTable({ throw IOException("no such asset") }, canDraw = { true })
        assertNull(table.emojiFor("smile"))
    }

    @Test
    fun `a stream that fails half way is an empty table`() {
        val broken = object : InputStream() {
            private var sent = 0
            override fun read(): Int = if (sent++ < 10) 'a'.code else throw IOException("truncated")
        }
        assertEquals(emptyMap<String, String>(), AssetEmojiTable.read { broken })
    }

    @Test
    fun `malformed lines are skipped, the rest still load`() {
        val text = "# header\nsmile\t😄\nno tab here\nBAD\tX\n\tnoalias\nempty\t\ntada\t🎉\n"
        val aliases = AssetEmojiTable.read { ByteArrayInputStream(text.toByteArray()) }
        assertEquals(setOf("smile", "tada"), aliases.keys)
    }

    @Test
    fun `an emoji the device cannot draw is not offered`() {
        assertNull(AssetEmojiTable(asset, canDraw = { false }).emojiFor("smile"))
        assertNull(AssetEmojiTable(asset, canDraw = { error("no paint") }).emojiFor("smile"))
        assertEquals("😄", AssetEmojiTable(asset, canDraw = { true }).emojiFor("smile"))
    }

    @Test
    fun `the gemoji notice is in Licences with its copyright and the MIT text`() {
        val entry = loadDependencies(context).single { it.textAsset == "licenses/MIT-Gemoji.txt" }
        assertEquals("MIT", entry.license)
        val text = licenseTextFor(context, entry)
        assertTrue(text.contains("Shipped as app/src/main/assets/${AssetEmojiTable.ASSET_PATH},"))
        assertTrue(text.contains("Copyright (c) 2019 GitHub, Inc."))
        assertTrue(text.contains("Permission is hereby granted, free of charge"))
    }

    private companion object {
        /**
         * github/gemoji v4.1.0 (5476a66), db/emoji.json: 1,870 emoji, 1,913 aliases, less the one
         * U+264A alias the table leaves out.
         */
        const val GEMOJI_ALIASES = 1912
    }
}
