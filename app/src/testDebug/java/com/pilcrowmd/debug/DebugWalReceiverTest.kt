// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.debug

import com.pilcrowmd.repository.FileText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * The debug WAL hook stages a file's text and truncates the file, and recovery then writes the staged
 * text back as UTF-8. So it must stage nothing for a file that is not UTF-8 (NEW-12), and a failed
 * read must not become empty staged content that recovery would write over the file.
 */
class DebugWalReceiverTest {

    @Test
    fun aUtf8FileIsStagedWithTheMarker() {
        val read = Result.success(FileText("# A\n", isUtf8 = true))
        assertEquals("# A\n<!-- m -->", walStagedContent(read, "<!-- m -->"))
    }

    @Test
    fun aFileThatIsNotUtf8IsNotStaged() {
        assertNull(walStagedContent(Result.success(FileText("caf�\n", isUtf8 = false)), "<!-- m -->"))
    }

    @Test
    fun aFailedReadIsNotStaged() {
        assertNull(walStagedContent(Result.failure(IOException("gone")), "<!-- m -->"))
    }
}
