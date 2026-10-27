// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-160: the reader takes a scheme-less link as a note link only when it is a path. */
class NoteLinkPathsTest {

    @Test
    fun paths_to_notes_and_files_are_taken() {
        for (link in listOf("plan.md", "../a", "/index.md#top", "projects/img.png", "<Note 2.md>", "a?x=1")) {
            assertTrue(link, NoteLinkPaths.isNotePath(link))
        }
    }

    @Test
    fun a_host_with_a_path_is_a_web_address_and_a_note_or_file_name_is_not() {
        // First segment with a dot, a path after it, and not a .md/.markdown note: the browser, as before.
        for (link in listOf("example.com/page", "docs.example.org/a/b.html", "Example.COM/x?y=1")) {
            assertFalse(link, NoteLinkPaths.isNotePath(link))
        }
        // Notes, files and relative paths stay note paths, whatever dots are in their names.
        val names = listOf(
            "Note 2.md",
            "sub/Note.md",
            "my.folder/Note.md",
            "photo.png",
            "./a.b/c",
            "../a.b/c",
            "/a.b/c",
        )
        for (link in names) {
            assertTrue(link, NoteLinkPaths.isNotePath(link))
        }
    }

    @Test
    fun web_addresses_and_links_with_no_path_keep_todays_behaviour() {
        for (link in listOf("www.example.com", "WWW.Example.com/page.md", "?q=1", "#top", "", "  ")) {
            assertFalse(link, NoteLinkPaths.isNotePath(link))
        }
    }
}
