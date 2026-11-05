// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.CalloutKind
import com.pilcrowmd.domain.markdown.ReaderWords
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reader words are read from strings.xml (languages spec D2). On the default locale they must be
 * the source-language words the tests use, for every callout kind and the `<details>` summary, so a
 * kind or word missing from the resource mapping fails here.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderWordsResourcesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theDefaultLocaleReadsAsTheEnglishWords() {
        assertEquals(ReaderWords.ENGLISH, readerWords(context.resources))
    }

    @Test
    fun everyCalloutKindHasATitle() {
        assertEquals(CalloutKind.entries.toSet(), readerWords(context.resources).calloutTitles.keys)
    }
}
