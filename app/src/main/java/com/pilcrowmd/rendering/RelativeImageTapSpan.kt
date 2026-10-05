// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import com.pilcrowmd.R

/**
 * M-93: makes a relative-image placeholder tappable ("Tap to show"). The tap goes to the handler
 * the reader put on its RecyclerView ([setImageTapHandler]); whether a picker opens is decided
 * there, so a tap on a picture that already shows, or on one no grant can bring in, does nothing.
 */
internal class RelativeImageTapSpan : ClickableSpan() {

    override fun onClick(widget: View) {
        var view: View? = widget
        while (view != null) {
            @Suppress("UNCHECKED_CAST")
            (view.getTag(R.id.image_tap_handler) as? () -> Unit)?.let { return it() }
            view = view.parent as? View
        }
    }

    /** No link colour or underline: the placeholder or the picture draws over this text. */
    override fun updateDrawState(ds: TextPaint) = Unit
}

/** Where the reader's image taps go. Set on the reader's RecyclerView. */
fun View.setImageTapHandler(handler: () -> Unit) {
    setTag(R.id.image_tap_handler, handler)
}
