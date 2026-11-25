// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.R
import com.pilcrowmd.ui.components.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Polish: the Language picker offers it, a Polish device resolves to `values-pl`, and the plurals
 * pick the right case for 1, 2, 5, 12, 22 and 25 (one, few, many, many, few, many).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pl")
class PolishLocaleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun plural(id: Int, n: Int) = context.resources.getQuantityString(id, n, n)

    @Test
    fun `the picker offers Polish under its own name`() {
        assertEquals("pl", AppLanguage.POLISH.tag)
        assertEquals("Polski", AppLanguage.POLISH.nativeName)
        assertEquals(AppLanguage.POLISH, AppLanguage.fromTag("pl"))
        assertEquals(AppLanguage.POLISH, AppLanguage.fromTag("pl-PL"))
    }

    @Test
    fun `a Polish locale reads values-pl`() {
        assertEquals("Zapisz", context.getString(R.string.main_action_save))
        assertEquals("Ustawienia", context.getString(R.string.settings_title))
    }

    @Test
    fun `plurals take the case the number needs`() {
        val minutes = mapOf(
            1 to "1 minutę",
            2 to "2 minuty",
            5 to "5 minut",
            12 to "12 minut",
            22 to "22 minuty",
            25 to "25 minut",
        )
        minutes.forEach { (n, text) -> assertEquals(text, plural(R.plurals.files_minutes_long, n)) }

        val notes = mapOf(
            1 to "1 oznaczona notatka",
            2 to "2 oznaczone notatki",
            5 to "5 oznaczonych notatek",
            12 to "12 oznaczonych notatek",
            22 to "22 oznaczone notatki",
            25 to "25 oznaczonych notatek",
        )
        notes.forEach { (n, text) -> assertEquals(text, plural(R.plurals.files_marked_notes, n)) }

        val pictures = mapOf(
            1 to "jest 1 obraz.",
            2 to "są 2 obrazy.",
            5 to "jest 5 obrazów.",
            12 to "jest 12 obrazów.",
            22 to "są 22 obrazy.",
            25 to "jest 25 obrazów.",
        )
        pictures.forEach { (n, text) ->
            assertEquals("W folderze tej notatki $text", plural(R.plurals.banner_note_pictures_in_folder, n))
        }
    }
}
