// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.testing

import io.github.rosemoe.sora.text.CharSequenceIterator
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.text.CharacterIterator

/**
 * Makes Sora's `CharSequenceIterator.previous()` keep the `CharacterIterator` contract in unit
 * tests: at the begin index it returns `DONE`. Sora's returns the first character again.
 *
 * Only the test JVM needs this. On a phone `java.text.BreakIterator` is ICU, which checks the index
 * before stepping back and never relies on `DONE`. The JDK's line iterator does rely on it, so
 * without this a word-wrap break after the first character of a line scans backwards for ever,
 * holding a thread of Sora's shared layout pool for the rest of the run. Applied to every
 * Robolectric test by `robolectric.properties`; `EditorWrapPassTerminatesTest` fails without it.
 */
@Implements(value = CharSequenceIterator::class, isInAndroidSdk = false)
class ShadowSoraCharSequenceIterator {

    @RealObject
    private lateinit var realIterator: CharSequenceIterator

    @Implementation
    fun previous(): Char {
        val index = realIterator.index
        if (index <= realIterator.beginIndex) return CharacterIterator.DONE
        return realIterator.setIndex(index - 1)
    }
}
