// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

import java.util.Locale

/** M-160: which scheme-less links the reader treats as links between notes. */
object NoteLinkPaths {
    /**
     * True for a path to a note or file (`plan.md`, `../a`, `/index.md#top`, `photo.png`); false for a
     * web address written without a scheme and for a link with no path (`?q=1`, `#top`), which keep
     * today's behaviour (the browser, through Markwon's `https://`).
     *
     * A web address is `www.` first, or a first segment with a dot followed by a path that does not end
     * in `.md` or `.markdown` (`example.com/page`). A single name (`photo.png`, `example.com`) is a
     * note or file name, so a picture or PDF at the root of the folder opens (K8); a bare host with no
     * path is therefore looked up as a note.
     */
    fun isNotePath(destination: String): Boolean {
        val target = destination.trim().removeSurrounding("<", ">")
        val path = target.substringBefore('#').substringBefore('?')
        return path.isNotEmpty() && !target.lowercase(Locale.ROOT).startsWith("www.") && !isHostWithPath(path)
    }

    private fun isHostWithPath(path: String): Boolean {
        if ('/' !in path || path.startsWith("/")) return false
        val lower = path.lowercase(Locale.ROOT)
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return false
        val first = path.substringBefore('/')
        return '.' in first && !first.startsWith(".")
    }
}
