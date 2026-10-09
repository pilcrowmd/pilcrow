// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.ext.tables.TablePlugin
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.Node

/**
 * Lets `markwon.render(cell)` return the cell's content, so a table cell keeps its bold, links, code
 * and maths (M-17, M-32).
 *
 * The reader draws a table itself, one TextView per cell ([TableBlockEntry]), and renders each cell
 * on its own. [TablePlugin]'s cell visitor takes the cell's rendered text back out of the builder
 * (`removeFromEnd`) to keep it for its own row span, so a render whose root is the cell came back
 * empty every time and the cell fell back to unstyled text.
 *
 * Wraps the plugin instead of replacing it, the way [MathSourceFallbackPlugin] wraps the maths
 * plugin: when the render's ROOT is the cell, the cell's children are rendered in place; any other
 * cell, such as one in a table nested in a quote or a list, still goes through the plugin's own
 * visitor and is drawn by its row span exactly as before. The parsed tree is never changed.
 *
 * The root is kept per thread: the reader renders on the main thread while a PDF export can render
 * on another. One slot is enough because renders never nest (see [OrderedListRebindPlugin]).
 */
internal class TableCellRenderPlugin(private val tables: TablePlugin) : MarkwonPlugin by tables {

    private val root = ThreadLocal<Node>()

    override fun beforeRender(node: Node) {
        tables.beforeRender(node)
        if (node is TableCell) root.set(node) else root.remove()
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        tables.configureVisitor(
            object : MarkwonVisitor.Builder by builder {
                override fun <N : Node> on(
                    node: Class<N>,
                    nodeVisitor: MarkwonVisitor.NodeVisitor<in N>?,
                ): MarkwonVisitor.Builder {
                    val isCell = node == TableCell::class.java
                    builder.on(node, if (isCell) nodeVisitor?.let { standalone(it) } else nodeVisitor)
                    return this
                }
            },
        )
    }

    private fun <N : Node> standalone(table: MarkwonVisitor.NodeVisitor<in N>) =
        MarkwonVisitor.NodeVisitor<N> { visitor, cell ->
            if (cell === root.get()) {
                // Cleared here, not only in the next beforeRender: a child that throws then leaves
                // nothing behind on this thread.
                try {
                    visitor.visitChildren(cell)
                } finally {
                    root.remove()
                }
            } else {
                table.visit(visitor, cell)
            }
        }
}
