// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.TransactionTooLargeException
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/**
 * S8-A66: Copy on a code block of several MB crashed the app. The system refuses a clip that large
 * (ClipboardManager.setPrimaryClip throws a RuntimeException caused by TransactionTooLargeException), and
 * nothing caught it. Now the reader says "Too large to copy" and goes on. A fake clipboard that throws
 * as the system's does stands in for a real multi-MB block, which Robolectric's clipboard would accept.
 */
@RunWith(RobolectricTestRunner::class)
class CodeBlockCopyTooLargeTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private val refusing = mockk<ClipboardManager>().also {
        every { it.setPrimaryClip(any()) } throws
            RuntimeException(TransactionTooLargeException("data parcel size 10485844 bytes"))
    }

    private val context = object : ContextWrapper(app) {
        override fun getSystemService(name: String): Any? =
            if (name == Context.CLIPBOARD_SERVICE) refusing else super.getSystemService(name)
    }

    @Test
    fun `a clip the system refuses as too large shows the message and does not crash`() {
        boundCodeBlock().copyButton.performClick()

        verify { refusing.setPrimaryClip(any()) }
        assertEquals("Too large to copy", app.getString(R.string.render_code_too_large_to_copy))
        assertEquals("Too large to copy", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `any other clipboard failure is not swallowed as too large`() {
        every { refusing.setPrimaryClip(any()) } throws IllegalStateException("not the binder's refusal")
        val holder = boundCodeBlock()

        val thrown = runCatching { holder.copyButton.performClick() }.exceptionOrNull()

        assertTrue("rethrown: $thrown", thrown is IllegalStateException)
    }

    private fun boundCodeBlock(): FencedCodeBlockEntry.Holder {
        val markwon = buildPilcrowMarkwon(context)
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(context, markwon)
        adapter.setParsedMarkdown(markwon, markwon.parse("```\nsome code\n```\n"))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        return holder as FencedCodeBlockEntry.Holder
    }
}
