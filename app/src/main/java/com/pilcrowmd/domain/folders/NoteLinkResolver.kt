// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale

/** One entry of a folder as the provider lists it. [id] is opaque (a document id on Android). */
data class DirEntry(val id: String, val name: String, val isFolder: Boolean)

/**
 * Lists one folder of the added folder by id; null when it cannot be read. Called synchronously; the
 * caller runs the resolver off the main thread.
 */
fun interface DirLister {
    fun children(folderId: String): List<DirEntry>?
}

/** Where a link in a note inside an added folder goes (notes-folder spec, section 4). */
sealed interface LinkTarget {
    /**
     * K1-K6: a note in the added folder. [heading] is the RAW fragment as typed (still percent-encoded),
     * null when none. It is meant for `HeadingAnchors.blockIndexOf(targets, "#" + heading)`, which decodes
     * it once and returns null for a missing heading, so the note opens at the top (K3).
     */
    data class Note(val id: String, val name: String, val heading: String?) : LinkTarget

    /** K8: a non-note file inside the folder, handed to the system. */
    data class OtherFile(val id: String, val name: String) : LinkTarget

    /** K9: http, https, mailto, unchanged from today. */
    data class Web(val url: String) : LinkTarget

    /** K7: "Note not found: <name>". [name] = the last path segment of the decoded target, fragment removed. */
    data class NotFound(val name: String) : LinkTarget

    /** Not this resolver's business, keep today's behaviour: same-note `#heading` links, other schemes. */
    data object Unhandled : LinkTarget
}

/**
 * M-160: resolves a link in a note inside an added folder against the folder's real listing (K1-K11,
 * K13-K16; K12 wiki-links are not in v1). Pure: no Android, and all I/O goes through [lister].
 * Never throws for any destination.
 */
class NoteLinkResolver(private val lister: DirLister) {

    /**
     * [rootId]: the added folder that applies (the caller picks the OUTERMOST one, K2/P14).
     * [currentFolderChain]: folder ids from rootId (first element) down to the folder holding the current note.
     * [destination]: the link destination as the parser gives it (title already dropped), or a
     * [BareLink.destination] (title already dropped, may still carry `<...>`).
     */
    fun resolve(rootId: String, currentFolderChain: List<String>, destination: String): LinkTarget {
        val target = clean(destination)
        val scheme = SCHEME.find(target)?.value?.dropLast(1)?.lowercase(Locale.ROOT)
        return when {
            scheme == null -> resolvePath(rootId, currentFolderChain, target)
            scheme in WEB_SCHEMES -> LinkTarget.Web(target)
            else -> LinkTarget.Unhandled
        }
    }

    private fun resolvePath(rootId: String, chain: List<String>, target: String): LinkTarget {
        val hash = target.indexOf('#')
        val rawPath = if (hash < 0) target else target.substring(0, hash)
        if (rawPath.isEmpty()) return LinkTarget.Unhandled
        val heading = if (hash < 0) null else target.substring(hash + 1).ifEmpty { null }
        val path = nfc(decode(rawPath))
        val start = if (path.startsWith('/')) listOf(rootId) else chain
        val segments = path.split('/')
        val parts = segments.filter { it.isNotEmpty() && it != CURRENT }
        // A trailing "/" or "/." names a folder, never a note.
        val found = if (segments.last().let { it.isEmpty() || it == CURRENT }) null else locate(start, parts)
        return when {
            found == null -> LinkTarget.NotFound(parts.lastOrNull() ?: path)
            isNote(found.name) -> LinkTarget.Note(found.id, found.name, heading)
            else -> LinkTarget.OtherFile(found.id, found.name)
        }
    }

    /** K1/K2/K7: walks [parts] from [start]; every segment but the last names a folder, the last a file. */
    private fun locate(start: List<String>, parts: List<String>): DirEntry? {
        val last = parts.lastOrNull()
        if (last == null || last == PARENT) return null
        var chain: List<String>? = start
        for (part in parts.dropLast(1)) chain = chain?.let { descend(it, part) }
        return chain?.lastOrNull()?.let { findFile(it, last) }
    }

    /** One middle segment: `..` pops (popping the root leaves the folder, K7), anything else enters a folder. */
    private fun descend(chain: List<String>, part: String): List<String>? = if (part == PARENT) {
        chain.takeIf { it.size > 1 }?.dropLast(1)
    } else {
        chain.lastOrNull()?.let { listed(it, folders = true) }?.let { match(it, part) }?.let { chain + it.id }
    }

    /** K6: a last segment with no extension tries `.md`, then `.markdown`; a folder never matches (P9). */
    private fun findFile(folderId: String, name: String): DirEntry? {
        val files = listed(folderId, folders = false) ?: return null
        return if (name.indexOf('.', 1) >= 0) {
            match(files, name)
        } else {
            match(files, "$name.md") ?: match(files, "$name.markdown")
        }
    }

    /** The listing, one kind only, hidden entries removed (L3: the list never shows them). */
    private fun listed(folderId: String, folders: Boolean): List<DirEntry>? =
        lister.children(folderId)?.filter { it.isFolder == folders && !it.name.startsWith('.') }

    /** K5 + K15: exact match after NFC first; else exactly one case-insensitive match, more is no match. */
    private fun match(entries: List<DirEntry>, wanted: String): DirEntry? {
        entries.firstOrNull { nfc(it.name) == wanted }?.let { return it }
        val lower = wanted.lowercase(Locale.ROOT)
        return entries.filter { nfc(it.name).lowercase(Locale.ROOT) == lower }.singleOrNull()
    }

    private companion object {
        val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        val WEB_SCHEMES = setOf("http", "https", "mailto")
        val NOTE_EXTENSION = Regex("\\.(?:md|markdown)$", RegexOption.IGNORE_CASE)
        const val PARENT = ".."
        const val CURRENT = "."

        fun isNote(name: String) = NOTE_EXTENSION.containsMatchIn(name)

        fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

        /**
         * Trimmed, and K4's one pair of `<...>` removed. A K16 title is never stripped here: the parser
         * and [BareNoteLinks] already drop it, so a destination like `Plan 'v2'` stays whole.
         */
        fun clean(destination: String): String {
            val trimmed = destination.trim()
            val unwrapped = trimmed.length >= 2 && trimmed.startsWith('<') && trimmed.endsWith('>')
            return if (unwrapped) trimmed.substring(1, trimmed.length - 1) else trimmed
        }

        /** K4: percent-decoding as UTF-8; a literal `+` stays `+`, a malformed escape leaves the text as typed. */
        fun decode(raw: String): String = try {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") // the Charset overload is API 33
        } catch (ignored: IllegalArgumentException) {
            raw
        }
    }
}
