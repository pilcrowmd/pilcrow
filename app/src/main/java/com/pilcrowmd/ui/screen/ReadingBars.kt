// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.app.Activity
import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pilcrowmd.viewmodel.ViewMode
import kotlinx.coroutines.delay

/** The swipe that brings the bars in also drags the text a little; that must not hide them again. */
private const val SCROLL_GRACE_MILLIS = 600L

/** How long a swiped-in bar stays before it hides again (S8-A41). */
private const val REVEAL_MILLIS = 3_000L

/**
 * S8-A41: full-screen reading hides the top bar and the system bars only while a document is being
 * READ with the setting on. Edit mode, the welcome screen and every overlay (Settings, search, the
 * contents panel, dialogs) show the bars as always.
 */
internal fun readingBarsWanted(
    fullScreen: Boolean,
    mode: ViewMode,
    hasDocument: Boolean,
    overlayOpen: Boolean,
): Boolean = fullScreen && mode == ViewMode.READER && hasDocument && !overlayOpen

/**
 * The bars' state while [wanted] is true: hidden, or [revealed] for a moment after the reader
 * swiped in from an edge. The top bar follows the system bars.
 */
@Stable
internal class ReadingBars(private val controller: WindowInsetsControllerCompat?) {
    var revealed by mutableStateOf(false)
        private set
    private var revealedAt = 0L

    /** Counts touches on the revealed top bar, so each one restarts the 3 s. */
    var touches by mutableStateOf(0)
        private set

    /** True while the top bar's ⋮ menu is open: the bar stays, or its popup would close with it. */
    var menuOpen by mutableStateOf(false)
        private set

    fun onMenuOpenChanged(open: Boolean) {
        menuOpen = open
    }

    fun onToolbarTouched() {
        touches++
    }

    /** The reader dragged the text: hide the bars, unless this is the swipe that just brought them. */
    fun onReaderScrolled() {
        if (SystemClock.uptimeMillis() - revealedAt >= SCROLL_GRACE_MILLIS) hideAgain()
    }

    /** The reader swiped in from the top or bottom edge: show the bars now, whatever the system does. */
    fun onEdgeSwipe() {
        if (revealed) return
        revealed = true
        revealedAt = SystemClock.uptimeMillis()
        controller?.show(WindowInsetsCompat.Type.systemBars())
    }

    /** The bars are shown for another reason now (Edit, an overlay): nothing is left to hide. */
    fun forget() {
        revealed = false
        menuOpen = false
    }

    /** Hide the system bars again, now (the 3 s are up). */
    fun hideAgain() {
        if (!revealed) return
        revealed = false
        controller?.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
internal fun rememberReadingBars(wanted: Boolean): ReadingBars {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    val controller = remember(window, view) {
        window?.let { WindowCompat.getInsetsController(it, view) }
    }
    val bars = remember(controller) { ReadingBars(controller) }

    // Only undo what this effect did: with the switch off nothing here touches the window.
    var hidSystemBars by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(wanted, controller) {
        if (wanted) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            hidSystemBars = true
        } else if (hidSystemBars) {
            bars.forget()
            controller?.show(WindowInsetsCompat.Type.systemBars())
            hidSystemBars = false
        }
    }

    // The 3 s start when the menu closes, not when the bar came in.
    LaunchedEffect(bars.revealed, bars.touches, bars.menuOpen) {
        if (bars.revealed && !bars.menuOpen) {
            delay(REVEAL_MILLIS)
            bars.hideAgain()
        }
    }
    return bars
}

/** How far inward a drag from the screen edge must go to count as the reveal swipe. */
private val EDGE_SWIPE_DISTANCE = 8.dp

/**
 * True when a drag that started in the top or bottom edge strip moved inward far enough: the
 * same gesture the system uses to bring its bars back. Android does not tell the app about its
 * transient bars, so the reader's swipe is recognised here too. [startY] and [endY] are in pixels.
 */
internal fun isEdgeSwipe(startY: Float, endY: Float, height: Float, strips: EdgeStrips, distance: Float): Boolean =
    (startY <= strips.top && endY - startY >= distance) ||
        (startY >= height - strips.bottom && startY - endY >= distance)

/** Heights, in pixels, of the strips along the top and bottom screen edges where a reveal swipe may start. */
internal data class EdgeStrips(val top: Float, val bottom: Float)

/** Watches (never consumes) touches and calls [onSwipe] for an edge swipe, only while [enabled]. */
@OptIn(ExperimentalLayoutApi::class) // the *IgnoringVisibility insets
@Composable
internal fun Modifier.edgeSwipeReveal(enabled: Boolean, onSwipe: () -> Unit): Modifier {
    if (!enabled) return this
    val density = LocalDensity.current
    val topEdge = WindowInsets.statusBarsIgnoringVisibility.getTop(density).toFloat()
    val bottomEdge = WindowInsets.navigationBarsIgnoringVisibility.getBottom(density).toFloat()
    val distance = with(density) { EDGE_SWIPE_DISTANCE.toPx() }
    return pointerInput(topEdge, bottomEdge, onSwipe) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start: Offset = down.position
            var done = false
            while (!done) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val strips = EdgeStrips(topEdge, bottomEdge)
                if (isEdgeSwipe(start.y, change.position.y, size.height.toFloat(), strips, distance)) {
                    onSwipe()
                    done = true
                }
                if (!change.pressed) done = true
            }
        }
    }
}

/** Reports every touch on the revealed top bar to [bars], never consuming it. */
internal fun Modifier.restartsRevealOnTouch(bars: ReadingBars): Modifier = pointerInput(bars) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        bars.onToolbarTouched()
    }
}
