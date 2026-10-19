// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.rendering.NullMermaidRenderer
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.CustomBlock
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-175: the export pools one view holder per node type. Keyed on the simple class name, two node
 * classes that share a short name share a holder; under R8 obfuscation TableBlock and CalloutBlock
 * both became "a", and every callout printed as a copy of the table. Two classes named `Block` stand
 * in for the obfuscated pair: one routes to the table entry, the other to prose.
 */
@RunWith(RobolectricTestRunner::class)
class PdfHolderPoolKeyTest {

    private object Table {
        class Block : TableBlock()
    }

    private object Callout {
        class Block : CustomBlock()
    }

    @Test
    fun nodeClassesWithTheSameSimpleNameDoNotShareAHolder() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val renderer = MarkwonRenderer(context)
        // Same reason as PdfExporterTest: a parse racing the pre-warm on the shared Markwon can throw.
        renderer.awaitFontPreWarm()
        val markwon = PdfExporter(context, renderer, NullMermaidRenderer).getMarkwon()
        val builder = PdfContentLayoutBuilder(context)
        val inflater = LayoutInflater.from(context)

        val tableView = builder.inflateMeasuredBlock(markwon, Table.Block(), inflater, WIDTH_PX, 1.0f)
        val calloutView = builder.inflateMeasuredBlock(markwon, Callout.Block(), inflater, WIDTH_PX, 1.0f)

        assertNotSame("the callout was given the table's pooled view", tableView, calloutView)
    }

    private companion object {
        const val WIDTH_PX = 1000
    }
}
