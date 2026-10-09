// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.Prop
import io.noties.markwon.core.CoreProps
import io.noties.markwon.core.SimpleBlockNodeVisitor
import io.noties.markwon.core.spans.OrderedListItemSpan
import org.commonmark.node.ListItem
import org.commonmark.node.OrderedList

/**
 * Draws a numbered list with its own delimiter, so `1)` stays `1)` (M-232).
 *
 * Markwon 4.6.2's `ListItemSpanFactory` always builds `n + "." + NBSP`: the factory sees only the
 * render props, never the node, and nothing reads `OrderedList.getDelimiter()`. This plugin puts
 * the delimiter into the props while a list's children are visited, and builds the ordered span
 * from it. Everything else is left to `CorePlugin`: the `ListItem` visitor (and so the number and
 * its per-item increment that [OrderedListRebindPlugin] compensates for) is untouched, and bullet
 * items go to the library's own factory.
 *
 * The outer value is restored after the list, so a nested list of the other kind cannot leak its
 * delimiter into its parent. The props are read when a `ListItem` sets its span, which `CorePlugin`
 * does after visiting the item's children, i.e. after any nested list has restored the outer value.
 *
 * Must be registered after `CorePlugin`, whose `OrderedList` visitor and `ListItem` factory it replaces.
 */
internal class OrderedListDelimiterPlugin : AbstractMarkwonPlugin() {

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        val block = SimpleBlockNodeVisitor() // what CorePlugin registers for OrderedList
        builder.on(OrderedList::class.java) { visitor, list ->
            val props = visitor.renderProps()
            val outer = DELIMITER.get(props)
            DELIMITER.set(props, list.delimiter)
            block.visit(visitor, list)
            DELIMITER.set(props, outer)
        }
    }

    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        val library = builder.requireFactory(ListItem::class.java)
        builder.setFactory(ListItem::class.java) SpanFactory@{ configuration, props ->
            if (CoreProps.LIST_ITEM_TYPE.require(props) != CoreProps.ListItemType.ORDERED) {
                return@SpanFactory library.getSpans(configuration, props)
            }
            val number = CoreProps.ORDERED_LIST_ITEM_NUMBER.require(props)
            val delimiter = DELIMITER.get(props, '.')
            OrderedListItemSpan(configuration.theme(), "$number$delimiter ")
        }
    }

    private companion object {
        val DELIMITER: Prop<Char> = Prop.of("pilcrow-ordered-list-delimiter")
    }
}
