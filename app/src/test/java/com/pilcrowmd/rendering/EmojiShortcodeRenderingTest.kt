// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.EmojiShortcode
import com.pilcrowmd.domain.markdown.EmojiShortcodes
import com.pilcrowmd.domain.markdown.HeadingAnchors
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.repository.AssetEmojiTable
import com.pilcrowmd.testing.readerModelFor
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonVisitor
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.CustomNode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-247: `:shortcode:` against the real renderer config, with the real gemoji table. What is painted
 * is asserted, not only that a node exists: a node with no visitor would paint nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
class EmojiShortcodeRenderingTest {

    private lateinit var context: Context
    private lateinit var drawn: Markwon
    private lateinit var notDrawn: Markwon

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val asset = { context.assets.open(AssetEmojiTable.ASSET_PATH) }
        drawn = buildPilcrowMarkwon(context, emoji = EmojiShortcodes(AssetEmojiTable(asset, canDraw = { true })))
        notDrawn = buildPilcrowMarkwon(context, emoji = EmojiShortcodes(AssetEmojiTable(asset, canDraw = { false })))
    }

    private fun painted(markwon: Markwon, markdown: String): String =
        markwon.render(ReaderDocument.transform(markwon.parse(markdown)).firstChild!!).toString().trim()

    private fun shortcodeNodes(markwon: Markwon, markdown: String): Int {
        var count = 0
        markwon.parse(markdown).accept(object : AbstractVisitor() {
            override fun visit(customNode: CustomNode) {
                if (customNode is EmojiShortcode) count++
                visitChildren(customNode)
            }
        })
        return count
    }

    @Test
    fun `a known shortcode paints as its emoji`() {
        assertEquals("Hi $SMILE there", painted(drawn, "Hi :smile: there"))
        assertEquals("$SMILE$TADA $PLUS_ONE", painted(drawn, ":smile::tada: :+1:"))
    }

    @Test
    fun `a shortcode the device cannot draw stays literal`() {
        assertEquals("Hi :smile: there", painted(notDrawn, "Hi :smile: there"))
    }

    @Test
    fun `times, unknown names and escaped colons stay literal`() {
        assertEquals(
            "at 10:30:45, a:b:c, :nosuch:, :smile:",
            painted(drawn, "at 10:30:45, a:b:c, :nosuch:, \\:smile:"),
        )
    }

    @Test
    fun `code, link destinations, HTML, autolinks and maths keep the name literal`() {
        // The code span is padded on screen, so only its content is compared.
        assertEquals(":smile:", painted(drawn, "a `:smile:` b").removePrefix("a").removeSuffix("b").trim())
        assertEquals("x", painted(drawn, "[x](https://e.test/:smile:)"))
        assertEquals(0, shortcodeNodes(drawn, "<span title=\":smile:\">x</span>"))
        assertEquals("https://e.test/:smile:", painted(drawn, "<https://e.test/:smile:>"))
        assertEquals(0, shortcodeNodes(drawn, "\$a :smile: b\$"))
        assertEquals(":smile:", painted(drawn, "```\n:smile:\n```"))
    }

    @Test
    fun `link text and emphasis do paint the emoji`() {
        assertEquals("go $TADA now", painted(drawn, "[go :tada: now](https://e.test) "))
        assertEquals("so $SMILE", painted(drawn, "*so :smile:*"))
    }

    @Test
    fun `the PDF instance paints it too`() {
        val asset = { context.assets.open(AssetEmojiTable.ASSET_PATH) }
        val print = buildPrintMarkwon(context, emoji = EmojiShortcodes(AssetEmojiTable(asset, canDraw = { true })))
        assertEquals("Hi $SMILE", painted(print, "Hi :smile:"))
    }

    @Test
    fun `a heading keeps the name in its anchor, as GitHub does`() {
        val document = ReaderDocument.transform(drawn.parse("# Done :tada:\n\nbody"))
        assertEquals("Done $TADA", drawn.render(document.firstChild!!).toString().trim())
        assertEquals(mapOf("done-tada" to 0), HeadingAnchors.targets(document))
    }

    /**
     * The reader screen parses on [MarkwonRenderer.parseForReader]'s own instance (M-172), not on the
     * instances above: it must carry the table too, and the contents list keeps the name as text.
     */
    @Test
    fun `the reader's own parse paints it and the contents list keeps the name`() {
        val asset = { context.assets.open(AssetEmojiTable.ASSET_PATH) }
        val renderer = MarkwonRenderer(context, emoji = EmojiShortcodes(AssetEmojiTable(asset, canDraw = { true })))
        val model = renderer.readerModelFor("# Done :tada:\n\nbody :smile:")
        val body = model.blocks[1]
        assertEquals("body $SMILE", renderer.markwon.render(body).toString().trim())
        assertEquals(listOf("Done :tada:"), model.tocHeadings.map { it.text })
    }

    /** Parse with the emoji-enabled instance, then bind the table as the reader does, through [binder]. */
    private fun tableTexts(binder: Markwon, markdown: String): List<String> {
        val table = ReaderDocument.transform(drawn.parse(markdown)).firstChild as TableBlock
        val entry = TableBlockEntry(context)
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        entry.bindHolder(binder, holder, table)
        val texts = mutableListOf<String>()
        fun collect(view: View) {
            if (view is TextView) texts.add(view.text.toString())
            if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        collect(holder.table)
        return texts
    }

    @Test
    fun `a table cell paints the emoji`() {
        assertEquals(listOf("H", "ok $PLUS_ONE"), tableTexts(drawn, "| H |\n|---|\n| ok :+1: |\n"))
    }

    /**
     * A cell Markwon fails to render is drawn by TableBlockEntry's plain-text fallback (Safeguard 3).
     * That fallback must paint the emoji too, the string search models; without its shortcode case
     * the emoji would vanish from the cell. The binding instance throws on every cell to reach it.
     */
    @Test
    fun `a table cell that fails to render still paints the emoji`() {
        val failing = Markwon.builder(context)
            .usePlugin(
                object : AbstractMarkwonPlugin() {
                    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                        builder.on(TableCell::class.java) { _, _ -> error("cell render failed") }
                    }
                },
            )
            .build()
        assertEquals(listOf("H", "ok $PLUS_ONE"), tableTexts(failing, "| H |\n|---|\n| ok :+1: |\n"))
    }

    private companion object {
        const val SMILE = "😄"
        const val TADA = "🎉"
        const val PLUS_ONE = "👍"
    }
}
