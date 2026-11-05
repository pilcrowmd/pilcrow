// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Languages spec D1: the store sets AppCompat's per-app locales and reports back what AppCompat holds. */
// API 32: from 33 AppCompat reads the framework LocaleManager through a live activity delegate, and
// these tests have none; below 33 it holds the choice itself, which is the path under test.
@Config(sdk = [32])
@RunWith(RobolectricTestRunner::class)
class AppCompatLanguageStoreTest {

    @After
    fun resetLocales() = AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())

    @Test
    fun `a chosen language is applied to AppCompat and reported`() {
        val store = AppCompatLanguageStore()
        assertNull(store.languageTag.value)

        store.setLanguageTag("de")

        assertEquals("de", store.languageTag.value)
        assertEquals("de", AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }

    @Test
    fun `refresh picks up a language changed behind the store's back`() {
        val store = AppCompatLanguageStore()
        assertNull(store.languageTag.value)

        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr"))
        assertNull(store.languageTag.value)

        store.refresh()

        assertEquals("fr", store.languageTag.value)
    }

    @Test
    fun `choosing the system language clears the per-app language`() {
        val store = AppCompatLanguageStore()
        store.setLanguageTag("fr")

        store.setLanguageTag(null)

        assertNull(store.languageTag.value)
        assertEquals("", AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }
}
