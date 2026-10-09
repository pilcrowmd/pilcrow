// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * The app's [CodeEditor]: Sora's editor, plus one rule on the way text enters it.
 *
 * A CRLF file is held in memory with LF only and written back with "\r\n" by the ViewModel
 * (Safeguard 2), but Sora keeps a separator per line, so a pasted "\r\n" would stay in the text and
 * be saved as "\r\r\n". Every user insertion reaches the document through [commitText] with
 * three arguments: the editor's own Paste (`pasteText` goes through the input connection) and an
 * input method's commit alike. Folding there is one insert, so one undo still takes the whole paste
 * back. A lone "\r" is user content (M-353) and is left alone.
 */
open class PilcrowCodeEditor(context: Context) : CodeEditor(context) {
    override fun commitText(text: CharSequence, applyAutoIndent: Boolean, applySymbolCompletion: Boolean) {
        super.commitText(foldCrlf(text), applyAutoIndent, applySymbolCompletion)
    }

    /**
     * An accessibility service's ACTION_SET_TEXT reaches Sora's `setText`, never [commitText] (M-353),
     * so its text is folded here, on a copy of the caller's [arguments].
     */
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val text = arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
        if (arguments != null && text != null && action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
            val folded = Bundle(arguments).apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, foldCrlf(text))
            }
            return super.performAccessibilityAction(action, folded)
        }
        return super.performAccessibilityAction(action, arguments)
    }
}

private fun foldCrlf(text: CharSequence): CharSequence =
    if (text.contains('\r')) text.toString().replace("\r\n", "\n") else text
