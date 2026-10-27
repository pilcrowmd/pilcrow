// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text of the line that names the open file: the file name alone, or the folders then the name. */
class OpenFileLineTest {

    @Test
    fun aFileWithNoFolderIsItsNameAlone() {
        assertEquals("plan.md", openFileCaption(emptyList(), "plan.md"))
    }

    @Test
    fun theFolderNamesAreJoinedWithAnAngleThenTheName() {
        assertEquals("Notes › projects › plan.md", openFileCaption(listOf("Notes", "projects"), "plan.md"))
    }

    @Test
    fun theAddedFolderAloneComesBeforeTheName() {
        assertEquals("Notes › index.md", openFileCaption(listOf("Notes"), "index.md"))
    }

    @Test
    fun theLineKnowsWhetherItIsInAFolderAndSpeaksThePathWithPauses() {
        val inFolder = OpenFileLine("plan.md", listOf("Notes", "projects"))
        assertTrue(inFolder.inFolder)
        assertEquals("Notes, projects", inFolder.spokenFolderPath)
        assertEquals("Notes › projects › plan.md", inFolder.caption)

        val plain = OpenFileLine("plan.md")
        assertFalse(plain.inFolder)
        assertEquals("", plain.spokenFolderPath)
        assertEquals("plan.md", plain.caption)
    }
}
