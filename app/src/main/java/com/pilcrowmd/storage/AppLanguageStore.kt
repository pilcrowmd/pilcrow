// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's own language choice (languages spec D1): a BCP-47 tag from locales_config.xml, or null
 * for the system language.
 */
interface AppLanguageStore {
    val languageTag: StateFlow<String?>

    /** Applies [tag] app-wide; AppCompat recreates the open activities. Main thread. */
    fun setLanguageTag(tag: String?)

    /** Re-reads the choice: Android 13+ can change it in system settings. */
    fun refresh()

    /** A store that always follows the system language: for a ViewModel built without one. */
    object None : AppLanguageStore {
        override val languageTag: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

        override fun setLanguageTag(tag: String?) = Unit

        override fun refresh() = Unit
    }
}

/** AppCompat per-app locales: the framework setting on Android 13+, AppCompat's own store (autoStoreLocales) below. */
class AppCompatLanguageStore : AppLanguageStore {
    private val _languageTag = MutableStateFlow(current())
    override val languageTag: StateFlow<String?> = _languageTag.asStateFlow()

    override fun setLanguageTag(tag: String?) {
        val locales = if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
        _languageTag.value = current()
    }

    override fun refresh() {
        _languageTag.value = current()
    }

    private fun current(): String? = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { null }
}
