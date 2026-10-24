// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The bytes of one draft file: a header (magic, URI, line ending, baseline, time, length), then the
 * text as UTF-8 to the end of the file. Written temp + fsync + atomic rename, so a reader sees the
 * old file or the new one. `java.io` / `java.nio` only.
 */
internal object DraftFile {
    private const val MAGIC = 0x50444654 // "PDFT"
    private const val MAX_FIELD_BYTES = 64 * 1024
    private const val SLICE = 64 * 1024

    fun write(target: File, draft: Draft) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            val out = DataOutputStream(BufferedOutputStream(fos))
            out.writeInt(MAGIC)
            out.writeText(draft.uri.toString())
            out.writeText(draft.lineEnding)
            out.writeText(draft.baseline)
            out.writeLong(draft.savedAt)
            out.writeInt(draft.text.length)
            out.flush()
            // The text last, to the end of the file, streamed so a 10M-character draft is not copied whole.
            val writer = OutputStreamWriter(out, Charsets.UTF_8)
            var from = 0
            while (from < draft.text.length) {
                val to = minOf(from + SLICE, draft.text.length)
                writer.write(draft.text, from, to - from)
                from = to
            }
            writer.flush()
            out.flush()
            fos.fd.sync()
        }
        // ATOMIC_MOVE replaces atomically on the same mount (minSdk 26): no delete-then-rename window.
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    /** The draft in [file] (null text when [withText] is false) and its summary, or null if unreadable. */
    fun read(file: File, withText: Boolean): Pair<Draft?, DraftInfo>? {
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC) return null
                val uri = Uri.parse(input.readText())
                val lineEnding = input.readText()
                val baseline = input.readText()
                val savedAt = input.readLong()
                val chars = input.readInt()
                val info = DraftInfo(uri, baseline, savedAt, chars)
                if (!withText) return Pair(null, info)
                val text = input.readBytes().toString(Charsets.UTF_8)
                Pair(Draft(uri, text, lineEnding, baseline, savedAt), info)
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        if (size < 0 || size > MAX_FIELD_BYTES) throw IOException("bad draft header")
        return ByteArray(size).also { readFully(it) }.toString(Charsets.UTF_8)
    }

    fun keyOf(uri: Uri): String = MessageDigest.getInstance("SHA-256")
        .digest(uri.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
