// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.HorizontalScrollView

/**
 * The code block's horizontal scroller, with an optional wrap mode (M-134).
 *
 * A stock HorizontalScrollView cannot wrap its child: it measures the child with an UNSPECIFIED
 * width whatever the child's layout params say, so even a `match_parent` TextView lays every line
 * out at full length. With [wrapLines] on, the child is measured AT_MOST the viewport width instead,
 * so the TextView wraps and there is nothing left to scroll. With it off this is the stock scroller.
 */
class CodeScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    HorizontalScrollView(context, attrs) {

    var wrapLines: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun measureChildWithMargins(
        child: View,
        parentWidthMeasureSpec: Int,
        widthUsed: Int,
        parentHeightMeasureSpec: Int,
        heightUsed: Int,
    ) {
        if (!wrapLines || MeasureSpec.getMode(parentWidthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed)
            return
        }
        val lp = child.layoutParams as MarginLayoutParams
        val horizontalUsed = paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin + widthUsed
        val available = (MeasureSpec.getSize(parentWidthMeasureSpec) - horizontalUsed).coerceAtLeast(0)
        child.measure(
            MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
            getChildMeasureSpec(
                parentHeightMeasureSpec,
                paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed,
                lp.height,
            ),
        )
    }
}
