// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import java.io.InputStream
import java.security.MessageDigest

/**
 * "Is this the same text?" without holding it twice: the length and SHA-256 of a text's UTF-8 bytes,
 * as one string. Used by the save journal (what a file was before a save began, G5) and by the draft
 * store (what a draft was typed on top of). Streams, so a 10M-character file costs a buffer, not a copy.
 */
internal object ByteFingerprint {
    private const val CHUNK = 64 * 1024

    fun of(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK)
        var length = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            length += n
        }
        return format(length, digest)
    }

    /** The fingerprint of [text] encoded as UTF-8, encoded a slice at a time. */
    fun of(text: CharSequence): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        var start = 0
        while (start < text.length) {
            var end = minOf(start + CHUNK, text.length)
            // Never cut a surrogate pair in two: each half alone would encode as '?'.
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            val bytes = text.subSequence(start, end).toString().toByteArray(Charsets.UTF_8)
            digest.update(bytes)
            length += bytes.size
            start = end
        }
        return format(length, digest)
    }

    private fun format(length: Long, digest: MessageDigest) =
        "$length:" + digest.digest().joinToString("") { "%02x".format(it) }
}
