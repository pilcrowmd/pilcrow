// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

/**
 * M-93: what a markdown image's destination points at, decided from the text alone — no I/O.
 *
 * The reader loads only what it can read with no network and no new permission: an image embedded
 * in the document ([Embedded]), an absolute `content://` URI ([Content], which still fails, and
 * shows the placeholder, unless the app already holds a grant for it), and a [Relative] path inside
 * a granted folder. Everything else is never loaded and shows the placeholder:
 * - [Remote]: http(s) and the like. Never fetched: a remote path would
 *   keep the INTERNET permission alive that M-70 means to drop.
 * - [Relative]: a path next to the document. It loads only through a folder the user granted, by
 *   walking that folder by name (`LocalFolderAccessRepository`); without one it is the placeholder.
 * - [Unsupported]: `file:`, `android.resource:`, a rooted path and anything malformed. A `file:` path
 *   cannot be read without a storage permission, and would otherwise reach the app's own files.
 */
sealed interface ImageDestination {

    /** A `data:image/…;base64,…` URI. [base64] is decoded later, off the main thread. */
    data class Embedded(val mimeType: String, val base64: String) : ImageDestination

    /** An absolute `content://` URI, passed to the content resolver as written. */
    data class Content(val uri: String) : ImageDestination

    /** A path relative to the document, e.g. `images/photo.png`. */
    data class Relative(val path: String) : ImageDestination

    /** A network URL. Never fetched. */
    data object Remote : ImageDestination

    /** Anything else. Never loaded. */
    data object Unsupported : ImageDestination

    companion object {
        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
        private val REMOTE_SCHEMES = setOf("http", "https", "ftp", "ftps", "ws", "wss")

        fun classify(destination: String): ImageDestination {
            val text = destination.trim()
            if (text.isEmpty()) return Unsupported
            // `//host/x.png` is protocol-relative: a network URL with the scheme left out.
            if (text.startsWith("//")) return Remote
            val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase()
            return when {
                scheme == null && text.startsWith("/") -> Unsupported
                scheme == null -> Relative(text)
                scheme in REMOTE_SCHEMES -> Remote
                scheme == "content" -> Content(text)
                scheme == "data" -> embedded(text.substring("data:".length))
                else -> Unsupported
            }
        }

        /** `data:[<mediatype>][;base64],<data>` — only base64 images are accepted. */
        private fun embedded(body: String): ImageDestination {
            val comma = body.indexOf(',')
            if (comma < 0) return Unsupported
            val params = body.substring(0, comma).split(';').map { it.trim().lowercase() }
            val mimeType = params.first()
            if (!mimeType.startsWith("image/") || "base64" !in params.drop(1)) return Unsupported
            return Embedded(mimeType, body.substring(comma + 1))
        }
    }
}
