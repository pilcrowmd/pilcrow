// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.screenshot.MarkdownSampleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M-07: [warmedMarkwonRenderer] hands back a renderer that no other thread is parsing on.
 *
 * Until M-114 the renderer's init{} pre-warm parsed on the reader's Dark 100% instance from a
 * background thread. A screenshot test that rendered while that parse was still running shared
 * Markwon's stateful inline processors with it, which is how `chemistry_mhchem` came out wrong (M-07)
 * and `latex_inline` threw (M-114). Since M-114 the pre-warm builds the instance without parsing on
 * it (pinned by [MarkwonRendererPreWarmTest]); this test still holds that the helper waits for it.
 * Measured once with a thread looping the pre-warm's `$$1$$` parse: on a shared instance 80 of 500
 * parses of the chemistry sample came out different or threw, the first at parse 3, one with
 * `Range [1, 23) out of bounds for length 5`; on two separate instances, 0 of 500.
 *
 * The pre-warm parses only once, so its window is too narrow to hit on demand. This test holds the
 * pre-warm thread mid-flight instead: [HoldingContext] parks it for [HOLD_MS] on its first context
 * read, which is inside the build of the instance it is about to parse on. Without the wait in the
 * helper, that thread is still alive when the helper returns, every run.
 */
@RunWith(RobolectricTestRunner::class)
class MarkwonPreWarmRaceTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun initJLatexMath() {
        // Robolectric never runs jlatexmath's init provider (see MarkdownScreenshotTest).
        ru.noties.jlatexmath.JLatexMathAndroid.init(app)
    }

    @Test
    fun theHelperReturnsOnlyOnceThePreWarmThreadHasFinished() {
        val context = HoldingContext(app, Thread.currentThread())

        val renderer = warmedMarkwonRenderer(context)

        // Checked before anything touches renderer.markwon: a parse here would wait on the instance's
        // lazy lock until the pre-warm had built it, and so hide the overlap this test is about.
        assertTrue("the pre-warm never read the context", context.arrived.await(HOLD_MS * 10, TimeUnit.MILLISECONDS))
        assertFalse("the pre-warm thread is still running after the helper returned", context.preWarm!!.isAlive)

        val chemistry = MarkdownSampleProvider().values.first { it.name == "chemistry_mhchem" }.markdown
        val reference = buildPilcrowMarkwon(app).toMarkdown(chemistry).toString()
        assertEquals(reference, renderer.markwon.toMarkdown(chemistry).toString())
    }

    /** Parks the first thread other than [caller] that reads resources, for [HOLD_MS], and records it. */
    private class HoldingContext(base: Context, private val caller: Thread) : ContextWrapper(base) {
        val arrived = CountDownLatch(1)

        @Volatile
        var preWarm: Thread? = null

        override fun getResources(): Resources {
            val current = Thread.currentThread()
            if (current !== caller && preWarm == null) {
                preWarm = current
                arrived.countDown()
                Thread.sleep(HOLD_MS)
            }
            return super.getResources()
        }
    }

    private companion object {
        const val HOLD_MS = 500L
    }
}
