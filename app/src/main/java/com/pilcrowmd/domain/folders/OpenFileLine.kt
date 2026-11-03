// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

/** The separator between the parts of the open-file line: "Notes › projects › plan.md". */
private const val CAPTION_SEPARATOR = " › "

/** The separator in the spoken form, which a screen reader reads as a pause rather than as a symbol. */
private const val SPOKEN_SEPARATOR = ", "

/** Right after the file name while the file has unsaved changes (GitHub #19): "plan.md*". */
private const val UNSAVED_MARK = "*"

/**
 * The text of the line that names the open file: the folder names, outermost first, then the file
 * name, joined with " › ". With no folder it is the file name alone.
 */
fun openFileCaption(folderPath: List<String>, name: String): String =
    (folderPath + name).joinToString(CAPTION_SEPARATOR)

/**
 * The line that names the open file, above the text in the reader and the editor. [folderPath] holds
 * the names of the added folder and the subfolders down to the one holding the file; it is empty for a
 * file that was not opened from My folders. [unsaved] is the document's dirty state: the line then ends
 * in "*" and TalkBack adds "unsaved changes".
 */
data class OpenFileLine(val name: String, val folderPath: List<String> = emptyList(), val unsaved: Boolean = false) {
    /** True for a note opened from My folders; its line carries a folder icon. */
    val inFolder: Boolean get() = folderPath.isNotEmpty()

    val caption: String get() = openFileCaption(folderPath, name) + if (unsaved) UNSAVED_MARK else ""

    /** The folder names for a screen reader: "Notes, projects". Empty when there is no folder. */
    val spokenFolderPath: String get() = folderPath.joinToString(SPOKEN_SEPARATOR)
}
