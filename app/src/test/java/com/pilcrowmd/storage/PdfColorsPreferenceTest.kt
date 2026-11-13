// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.model.PdfColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** S8-A82: light by default, so nobody's PDF changes unless they ask. */
@RunWith(RobolectricTestRunner::class)
class PdfColorsPreferenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: LocalStorageManager
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("pdf_colors_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `light for printing is the default`() = runBlocking {
        assertEquals(PdfColors.LIGHT_FOR_PRINTING, storage.pdfColors.first())
    }

    @Test
    fun `the setting persists both ways`() = runBlocking {
        storage.setPdfColors(PdfColors.MATCH_APP_THEME)
        assertEquals(PdfColors.MATCH_APP_THEME, storage.pdfColors.first())
        storage.setPdfColors(PdfColors.LIGHT_FOR_PRINTING)
        assertEquals(PdfColors.LIGHT_FOR_PRINTING, storage.pdfColors.first())
    }
}
