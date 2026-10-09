// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.noties.markwon.Markwon
import io.noties.markwon.inlineparser.InlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-114: the renderer's start-up warm-up must never parse Markdown on the reader's instance.
 *
 * Markwon hands one set of inline processors to every parse on an instance, and each keeps the text of
 * the parse in progress in its `input` field, never cleared afterwards. A parse on the warm-up thread
 * could swap that text under a reader parse on the main thread: the one CI failure was a
 * StringIndexOutOfBounds in [SingleDollarMathInlineProcessor] on a length-5 text, the `$$1$$` the
 * warm-up used to parse. So the shared processors are read directly: after the warm-up has finished,
 * none of them may hold any text. Deterministic, no timing: the warm-up thread is joined first.
 */
@RunWith(RobolectricTestRunner::class)
class MarkwonRendererPreWarmTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The inline processors every parse on [markwon] shares, read from the plugin that holds them. */
    private fun sharedProcessors(markwon: Markwon): List<InlineProcessor> {
        val builder = markwon.requirePlugin(MarkwonInlineParserPlugin::class.java).factoryBuilder()
        val field = builder.javaClass.getDeclaredField("inlineProcessors").apply { isAccessible = true }
        return (field.get(builder) as List<*>).filterIsInstance<InlineProcessor>()
    }

    /** The text of the last parse [processor] took part in, or null if it has never run. */
    private fun lastInput(processor: InlineProcessor): String? {
        val field = InlineProcessor::class.java.getDeclaredField("input").apply { isAccessible = true }
        return field.get(processor) as String?
    }

    @Test
    fun warmUpNeverParsesOnTheReadersInstance() {
        val renderer = MarkwonRenderer(context)
        renderer.awaitFontPreWarm()
        val processors = sharedProcessors(renderer.markwon)
        assertTrue(
            "no single-dollar processor found; the probe reads the wrong list",
            processors.any { it is SingleDollarMathInlineProcessor },
        )

        val touched = processors.filter { lastInput(it) != null }
            .map { "${it.javaClass.simpleName}: ${lastInput(it)}" }
        assertEquals("the warm-up parsed on the reader's instance", emptyList<String>(), touched)

        // Control: a parse on this instance does leave its text in the shared processor, so an empty
        // result above means no parse ran, not that the probe cannot see one.
        renderer.markwon.parse(CONTROL)
        val single = processors.single { it is SingleDollarMathInlineProcessor }
        assertEquals(CONTROL, lastInput(single))
    }

    private companion object {
        const val CONTROL = "Let \$x\$ be."
    }
}
