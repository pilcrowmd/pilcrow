// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Lets a horizontally-scrolling view (a wide table or code block) pan sideways even though it lives
 * inside the vertically-scrolling preview RecyclerView. It claims the gesture from the parent chain
 * ONLY once the drag is confirmed predominantly horizontal; a vertical (or not-yet-decided) drag is
 * left for the RecyclerView to scroll the page as normal. Without the horizontal claim, the
 * RecyclerView would steal the horizontal drag and the inner view would never pan.
 *
 * IMPORTANT (real-device scroll-lock fix): the previous version disallowed parent interception
 * eagerly on ACTION_DOWN — before any direction was known. An ACTION_DOWN has zero magnitude, so
 * that blindly handed the whole touch stream to the inner HorizontalScrollView and froze vertical
 * page scrolling whenever a drag happened to start on a table/code block. Deferring the claim to
 * a confirmed-horizontal ACTION_MOVE keeps the RecyclerView's vertical-scroll intercept window
 * open for vertical drags.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.enableHorizontalNestedScroll() {
    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    var downX = 0f
    var downY = 0f
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                // Do NOT disallow yet — direction is unknown on DOWN.
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(e.x - downX)
                val dy = abs(e.y - downY)
                // Predominantly horizontal drag → claim it from the RecyclerView so the inner view
                // pans. Vertical drags are never claimed, so the page keeps scrolling.
                if (dx > dy && dx > touchSlop) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        false // never consume — the scroll view handles its own scrolling
    }
}
