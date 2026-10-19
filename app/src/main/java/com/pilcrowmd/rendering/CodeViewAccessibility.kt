// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.Spanned
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/**
 * M-404: gives accessibility the PLAIN text of a coloured code view. A TextView hands its Spanned text
 * to the node, and TalkBack reads out every colour span ("start color red", ...); the same characters
 * with no spans are read as just the text. The view itself keeps its spans, so the colours stay on
 * screen. Set once where the view is created, not per bind: the delegate reads the text as it is
 * when TalkBack asks.
 */
internal fun TextView.plainTextForAccessibility() {
    accessibilityDelegate = PlainTextAccessibilityDelegate
}

private object PlainTextAccessibilityDelegate : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        // Only when there is text: a view with none keeps the node exactly as the TextView left it.
        (host as TextView).text?.takeIf { it.isNotEmpty() }?.let { info.text = it.toString() }
    }

    // Text-changed and focus events carry the text too; TextView adds it Spanned.
    override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
        super.onPopulateAccessibilityEvent(host, event)
        val texts = event.text
        for (i in texts.indices) {
            val text = texts[i]
            if (text is Spanned) texts[i] = text.toString()
        }
    }
}
