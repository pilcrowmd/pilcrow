// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * S8-A37 — every EPL-2.0 library on the Open source licenses screen shows the EPL-2.0 text, and the
 * screen carries the GPLv3 section 7 permission exactly as the repository's `LICENSE-EXCEPTION` words it.
 */
@RunWith(RobolectricTestRunner::class)
class LicensesEplEntriesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `each EPL library shows the EPL text`() {
        val epl = loadDependencies(context).filter { it.license.contains("EPL", ignoreCase = true) }
        val names = epl.map { it.name }
        assertTrue("jdt annotation missing from $names", names.any { it.contains("JDT Annotations") })
        assertTrue("language-textmate missing from $names", "language-textmate" in names)
        epl.forEach { lib ->
            val text = licenseTextFor(context, lib)
            assertTrue("${lib.name} shows: ${text.take(60)}", text.startsWith("Eclipse Public License - v 2.0"))
        }
    }

    @Test
    fun `the exception text is the repository file`() {
        val repoFile = repositoryFile("LICENSE-EXCEPTION").trim()
        assertEquals(repoFile, loadLicenseException(context))
    }

    private fun repositoryFile(path: String) = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, path) }
        .first { it.isFile }
        .readText()
}
