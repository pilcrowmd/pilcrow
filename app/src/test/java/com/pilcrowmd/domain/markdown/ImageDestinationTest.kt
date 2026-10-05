// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import com.pilcrowmd.domain.markdown.ImageDestination.Content
import com.pilcrowmd.domain.markdown.ImageDestination.Embedded
import com.pilcrowmd.domain.markdown.ImageDestination.Relative
import com.pilcrowmd.domain.markdown.ImageDestination.Remote
import com.pilcrowmd.domain.markdown.ImageDestination.Unsupported
import org.junit.Assert.assertEquals
import org.junit.Test

/** M-93: which image destinations the reader may load, decided from the text alone. */
class ImageDestinationTest {

    private fun classify(destination: String) = ImageDestination.classify(destination)

    @Test
    fun `network URLs are remote, in any case and without a scheme`() {
        listOf(
            "https://example.com/x.png",
            "HTTP://example.com/x.png",
            "http://192.168.0.1/x.png",
            "//cdn.example.com/x.png",
            "ftp://example.com/x.png",
        ).forEach { assertEquals(it, Remote, classify(it)) }
    }

    @Test
    fun `a content URI is kept as written`() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3ANotes%2Fa.png"
        assertEquals(Content(uri), classify(uri))
    }

    @Test
    fun `a base64 image data URI is embedded`() {
        assertEquals(Embedded("image/png", "iVBORw0K"), classify("data:image/png;base64,iVBORw0K"))
        assertEquals(Embedded("image/jpeg", "/9j/"), classify("data:IMAGE/JPEG;charset=x;BASE64,/9j/"))
    }

    @Test
    fun `a data URI that is not a base64 image is unsupported`() {
        listOf(
            "data:text/html;base64,PGI+",
            "data:image/svg+xml,<svg/>",
            "data:image/png;base64",
            "data:,",
        ).forEach { assertEquals(it, Unsupported, classify(it)) }
    }

    @Test
    fun `paths next to the document are relative`() {
        listOf("photo.png", "images/photo.png", "./images/photo.png", "../shared/photo.png", "my%20photo.png")
            .forEach { assertEquals(it, Relative(it), classify(it)) }
    }

    @Test
    fun `file, rooted, app-internal and empty destinations are unsupported`() {
        listOf(
            "file:///sdcard/Pictures/a.png",
            "/storage/emulated/0/a.png",
            "android.resource://com.pilcrowmd/drawable/x",
            "javascript:alert(1)",
            "",
            "   ",
        ).forEach { assertEquals("'$it'", Unsupported, classify(it)) }
    }
}
