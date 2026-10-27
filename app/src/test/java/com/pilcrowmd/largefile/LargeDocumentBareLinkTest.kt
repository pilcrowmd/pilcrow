// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.largefile

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pilcrowmd.domain.markdown.PlainReason
import com.pilcrowmd.domain.markdown.ReaderModel
import com.pilcrowmd.testing.LargeDocumentSuite
import com.pilcrowmd.viewmodel.NoteBareLinks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M-160 (K13/K14) on a note over the Markdown ceiling: it is shown as plain text, so the bare-link
 * pass never runs, and the link stays as written.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LargeDocumentSuite::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LargeDocumentBareLinkTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var harness: ReaderHarness

    @Before
    fun setUp() {
        harness = ReaderHarness(composeRule, tempFolder)
    }

    @After
    fun tearDown() = harness.close()

    @Test(timeout = TIMEOUT_MS)
    fun `a note over the ceiling stays plain with its bare link as written`() {
        val link = "See [two](Note 2.md).\n"
        val uri = Uri.parse("content://test/large-bare.md")
        val vm = harness.viewModel(LargeMarkdownFixture.generate(OVER_CEILING_CHARS) + "\n\n" + link)
        harness.show(vm)
        vm.loadFile(uri)
        harness.awaitReader("the plain model is shown", TIMEOUT_MS) {
            (vm.readerModel.value as? ReaderModel.Plain)?.reason == PlainReason.TOO_LARGE
        }

        val shown = vm.readerModel.value
        vm.setBareLinks(NoteBareLinks(uri, setOf("Note 2.md")))
        // A rebuild would publish a new model of the same text; it has not happened (so far) before the
        // action, so "a different model is published" is false now and true only if the set was acted on.
        val rebuilt = runCatching {
            harness.awaitReader("a rebuilt model", REBUILD_WINDOW_MS) { vm.readerModel.value !== shown }
        }.isSuccess

        assertFalse("a plain model has no tree to link: the set must not rebuild it", rebuilt)
        val model = vm.readerModel.value as ReaderModel.Plain
        assertEquals(PlainReason.TOO_LARGE, model.reason)
        assertTrue("the link is in the text as written", model.chunkLiterals.joinToString("\n").endsWith(link))
    }

    private companion object {
        const val TIMEOUT_MS = 240_000L
        const val REBUILD_WINDOW_MS = 8_000L
        const val OVER_CEILING_CHARS = 10_500_000
    }
}
