// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.domain.model.SearchMatch
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.rendering.RecyclerAdapterEntries
import com.pilcrowmd.rendering.SearchHighlight
import com.pilcrowmd.storage.ScrollAnchor
import com.pilcrowmd.ui.theme.FontSet
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.mdColors
import io.noties.markwon.recycler.MarkwonAdapter

/**
 * Block-level Markdown preview.
 *
 * Each top-level Markdown block is its own RecyclerView item (markwon-recycler / MarkwonAdapter):
 * prose blocks wrap to the viewport, while wide tables and fenced code blocks each render into
 * their own HorizontalScrollView and pan sideways independently. View recycling keeps
 * very large files (~5,000 lines) scrolling smoothly.
 *
 * Scroll preservation: the RecyclerView owns vertical scrolling. Position is
 * reported as a [ScrollAnchor] (first-visible-item index + that item's pixel offset) through
 * `onScrollChanged`, and restored via `LinearLayoutManager.scrollToPositionWithOffset` — robust to
 * block heights changing between sessions, unlike a single absolute pixel offset.
 *
 * IMPORTANT: `update` runs on EVERY recomposition (including every scroll, since `scrollPosition`
 * is a parameter). So every side effect here is guarded to fire ONLY when its own input actually
 * changes — otherwise normal scrolling would re-run setMarkdown / smoothScroll every frame and
 * destroy the scroll experience.
 *
 * Search highlighting is applied at BIND time by ProseBlockEntry via a shared
 * [SearchHighlight] (survives recycling); this composable just updates it and calls
 * notifyDataSetChanged when the search state changes.
 */
@Composable
fun MarkdownPreview(
    modifier: Modifier = Modifier,
    content: String,
    renderer: MarkwonRenderer,
    fontScale: Float = 1.0f,
    fontSet: FontSet = FontSets.DEFAULT,
    mermaidCloudEnabled: Boolean = false,
    scrollPosition: ScrollAnchor = ScrollAnchor(),
    onScrollChanged: (ScrollAnchor) -> Unit = {},
    onFontScaleChange: (Float) -> Unit = {},
    searchMatches: List<SearchMatch> = emptyList(),
    currentMatchIndex: Int = 0,
    jumpPosition: Int = -1,
    jumpSeq: Int = 0,
) {
    // Captured once per composition-entry, so re-entering Reader mode (or rotating) restores the
    // saved offset without fighting live scroll updates.
    val initialScroll = remember { scrollPosition }
    val lastContent = remember { mutableStateOf<String?>(null) }

    // Shared with ProseBlockEntry; colors come from the token layer (Safeguard 4).
    val c = mdColors()
    val searchHighlight = remember {
        SearchHighlight(
            otherColor = c.searchHighlight.toArgb(),
            focusedColor = c.searchHighlightFocused.toArgb(),
        )
    }
    val lastSearchKey = remember { mutableStateOf<String?>(null) }
    val lastJumpSeq = remember { mutableStateOf(0) }
    // Tracks the font/scale/mermaid config the current adapter was built with, so the adapter
    // is rebuilt if a preference loads/changes after the file opened (cold-start race: prefs
    // arrive from DataStore slightly after the auto-reopened file renders).
    val lastConfig = remember { mutableStateOf<String?>(null) }

    // Handle to the RecyclerView so the jump-to-top/bottom controls can scroll it.
    val recyclerView = remember { mutableStateOf<RecyclerView?>(null) }
    // Only show the jump controls when the content overflows the viewport (not on short docs).
    val canScroll = remember { mutableStateOf(false) }

    // The scale the adapter is built at. The live pinch reflows the visible TextViews directly (no
    // rebuild mid-gesture); this is bumped ONCE on gesture end to trigger the single crisp rebuild,
    // and synced from the `fontScale` param when it changes externally (Settings slider). The adapter
    // config-key (below) reads THIS, not the param, so the end-of-pinch commit re-renders every block.
    val liveFontScale = remember { mutableStateOf(fontScale) }
    // Last `fontScale` param value seen, so we sync it INTO liveFontScale only when the persisted
    // scale changes externally (Settings A−/A+ slider, or the round-trip after a pinch persists) —
    // never overwriting an in-flight pinch, since the param stays constant during the gesture.
    val lastParamScale = remember { mutableStateOf(fontScale) }

    Box(modifier = modifier.fillMaxSize().background(c.primaryBackground)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                RecyclerView(context).apply {
                    recyclerView.value = this // Capture the handle once (factory runs once)
                    layoutManager = LinearLayoutManager(context)
                    setBackgroundColor(c.primaryBackground.toArgb())
                    // No item add/remove/change animations: a live pinch-zoom swaps the adapter many
                    // times a second, and the default cross-fade would read as a flicker/blink.
                    itemAnimator = null
                    adapter = RecyclerAdapterEntries.buildMarkdownAdapter(
                        context,
                        renderer.markwon,
                        fontScale,
                        fontSet,
                        mermaidCloudEnabled,
                        searchHighlight,
                        c,
                    )
                    addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                            onScrollChanged(rv.currentScrollAnchor())
                            canScroll.value = rv.computeVerticalScrollRange() > rv.height
                        }
                    })

                    // Pinch-to-zoom (Fix #6). ScaleGestureDetector fires only on a two-finger gesture,
                    // so one-finger vertical scroll is untouched. The TEXT resizes and reflows LIVE and
                    // smoothly during the pinch — no bitmap/view zoom — by setting `textSize` directly on
                    // the visible TextViews each frame (cheap: a native reflow, NO Markwon re-parse; the
                    // adapter is NOT rebuilt mid-gesture). Markwon heading spans are RelativeSizeSpans, so
                    // they scale off the new base automatically. The pinch FOCAL point is held stationary
                    // (anchor the block under the fingers by its fractional offset, re-scroll after the
                    // reflow lays out) so the text grows/shrinks around the fingers instead of jumping.
                    // The gesture is DAMPED (ReaderZoom.dampedScale) so the zoom is gradual across the
                    // narrow 0.85–1.6 range; the value is continuous (un-quantised) for a smooth resize.
                    // On gesture END the real font scale (quantised) is applied ONCE — a single crisp
                    // adapter rebuild (`liveFontScale`, see `update`) re-renders every block perfectly and
                    // is PERSISTED via onFontScaleChange → the same setting the Settings slider writes
                    // (single source of truth). View-only — never touches saved content (Safeguard 2).
                    val scaleDetector = ScaleGestureDetector(
                        context,
                        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            private var gestureScale = 1f
                            private var startScale = 1f

                            // The scale the visible TextViews currently SHOW (continuous), advanced each
                            // frame by the applied ratio so it tracks the live (un-rebuilt) view state.
                            private var visualScale = 1f

                            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                                gestureScale = 1f
                                startScale = liveFontScale.value
                                visualScale = startScale
                                return true
                            }

                            override fun onScale(detector: ScaleGestureDetector): Boolean {
                                gestureScale *= detector.scaleFactor
                                val target = ReaderZoom.dampedScale(startScale, gestureScale)
                                val ratio = target / visualScale
                                if (ratio == 1f) return true
                                val rv = recyclerView.value ?: return true
                                // Capture the block under the fingers and how far down it the focal point
                                // sits, BEFORE the reflow changes its height.
                                val focusY = detector.focusY
                                val focusChild = rv.findChildViewUnder(detector.focusX, focusY)
                                val focusPos = focusChild?.let { rv.getChildAdapterPosition(it) }
                                    ?: RecyclerView.NO_POSITION
                                val focusFraction = if (focusChild != null && focusChild.height > 0) {
                                    (focusY - focusChild.top) / focusChild.height.toFloat()
                                } else {
                                    0f
                                }
                                scaleVisibleTextViews(rv, ratio)
                                visualScale = target
                                // After the reflow lays the focal block out at its new height, scroll so
                                // that same fractional point is back under the fingers.
                                if (focusChild != null && focusPos != RecyclerView.NO_POSITION) {
                                    focusChild.doOnNextLayout { laidOut ->
                                        val newTop = (focusY - laidOut.height * focusFraction).toInt()
                                        (rv.layoutManager as? LinearLayoutManager)
                                            ?.scrollToPositionWithOffset(focusPos, newTop)
                                    }
                                }
                                return true
                            }

                            override fun onScaleEnd(detector: ScaleGestureDetector) {
                                // Commit the final scale ONCE (quantised to the Settings slider's 1% step):
                                // a single crisp adapter rebuild re-renders every block at the new size and
                                // persists it. The live-scaled views ≈ the rebuilt size, so the reset is
                                // seamless.
                                val finalScale = ReaderZoom.clampScale(ReaderZoom.dampedScale(startScale, gestureScale))
                                liveFontScale.value = finalScale
                                if (finalScale != startScale) onFontScaleChange(finalScale)
                            }
                        },
                    )
                    addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                        // Feed every event to the detector; intercept (steal from scrolling) only while
                        // an actual scale gesture is in progress, so single-finger scroll still works.
                        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                            scaleDetector.onTouchEvent(e)
                            return scaleDetector.isInProgress
                        }

                        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                            scaleDetector.onTouchEvent(e)
                        }
                    })
                }
            },
            update = { rv ->
                // Sync the persisted scale INTO the live render scale ONLY when the param itself
                // changes (Settings slider, or the round-trip after a pinch persists). During a pinch
                // the param is constant, so this never overwrites the gesture-driven liveFontScale.
                if (fontScale != lastParamScale.value) {
                    lastParamScale.value = fontScale
                    liveFontScale.value = fontScale
                }

                // (0) Rebuild the adapter if the font set, scale, mermaid toggle, or theme changed since it
                // was built (e.g. after file open, user toggled theme, or a pinch moved the live scale).
                // Font size is baked into each holder at createHolder, so a scale change needs new holders
                // — i.e. a fresh adapter. To do that WITHOUT a flicker during a live pinch: parse the
                // content into the new adapter BEFORE attaching it, swap it in already-populated
                // (swapAdapter keeps the recycled-view pool), then restore the anchor synchronously so the
                // first layout of the new adapter lands in place — no blank frame, no reposition flash.
                val configKey = "${liveFontScale.value}|${fontSet.id}|$mermaidCloudEnabled|${c.primaryBackground}"
                if (configKey != lastConfig.value) {
                    // Keep the user's place across the rebuild: the live anchor mid-reading (e.g. the
                    // commit at the end of a pinch lands where the live reflow left the viewport), or the
                    // entry-time initialScroll on the very first build (no prior place yet).
                    val firstBuild = lastConfig.value == null
                    val restore = if (firstBuild) initialScroll else rv.currentScrollAnchor()
                    lastConfig.value = configKey
                    val newAdapter = RecyclerAdapterEntries.buildMarkdownAdapter(
                        rv.context,
                        renderer.markwon,
                        liveFontScale.value,
                        fontSet,
                        mermaidCloudEnabled,
                        searchHighlight,
                        c,
                    )
                    newAdapter.setMarkdown(renderer.markwon, content) // populate before attaching → no empty frame
                    lastContent.value = content // content is now rendered; the (1) re-render is skipped this pass
                    rv.swapAdapter(newAdapter, false)
                    (rv.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(restore.index, restore.offset)
                    // Re-evaluate scrollability after the new content lays out (short-doc guard).
                    rv.post { canScroll.value = rv.computeVerticalScrollRange() > rv.height }
                }

                val adapter = rv.adapter as MarkwonAdapter

                // (1) Re-render when the document text changes WITHOUT a config change (e.g. returning
                // from the editor) — the config-change path above already rendered new content. Restore
                // the entry-time anchor after layout settles; scrollToPositionWithOffset survives
                // block-height changes that an absolute pixel offset could not.
                if (content != lastContent.value) {
                    lastContent.value = content
                    adapter.setMarkdown(renderer.markwon, content)
                    val lm = rv.layoutManager as? LinearLayoutManager
                    rv.post { lm?.scrollToPositionWithOffset(initialScroll.index, initialScroll.offset) }
                    // Re-evaluate scrollability after the new content lays out (short-doc guard).
                    rv.post { canScroll.value = rv.computeVerticalScrollRange() > rv.height }
                }

                // (2) Update search highlights only when the search state changes.
                val focusedMatch = searchMatches.getOrNull(currentMatchIndex)
                val query = searchMatches.firstOrNull()?.content ?: ""
                val focusedPos = focusedMatch?.adapterPosition ?: -1
                val focusedOccurrence = focusedMatch?.occurrenceInBlock ?: 0
                val searchKey = "$query|${searchMatches.size}|$currentMatchIndex"
                if (searchKey != lastSearchKey.value) {
                    lastSearchKey.value = searchKey
                    searchHighlight.query = query
                    searchHighlight.focusedPosition = focusedPos
                    searchHighlight.focusedOccurrence = focusedOccurrence
                    adapter.notifyDataSetChanged()
                    // Bring the focused match into view. scrollToPositionWithOffset only tops
                    // the BLOCK, so a match deep in a tall block stays below the fold — after the block
                    // is at the top, scroll down to the focused occurrence's line (nested post: the
                    // holder must be laid out before its TextView line geometry can be measured).
                    if (focusedPos >= 0) {
                        val lm = rv.layoutManager as? LinearLayoutManager
                        rv.post {
                            lm?.scrollToPositionWithOffset(focusedPos, 0)
                            rv.post {
                                val delta = rv.intraBlockMatchDelta(focusedPos, query, focusedOccurrence)
                                if (delta > 0) rv.scrollBy(0, delta)
                            }
                        }
                    }
                }

                // (3) Jump to a heading only on a NEW tap. Guarding on jumpSeq (not the position)
                // lets the user re-tap the same heading after scrolling away.
                // Position the heading at the TOP of the viewport, not the bottom.
                if (jumpSeq != lastJumpSeq.value && jumpPosition >= 0) {
                    lastJumpSeq.value = jumpSeq
                    rv.post {
                        val layoutManager = rv.layoutManager as? LinearLayoutManager
                        if (layoutManager != null) {
                            // scrollToPositionWithOffset places the item at a pixel offset from the top
                            layoutManager.scrollToPositionWithOffset(jumpPosition, 0)
                        } else {
                            rv.smoothScrollToPosition(jumpPosition)
                        }
                    }
                }
            },
        )

        // Floating jump-to-top / jump-to-bottom controls (preview only). Subtle, token-colored.
        JumpControls(
            recyclerView = recyclerView.value,
            visible = canScroll.value,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}

/**
 * Multiply the `textSize` of every TextView currently attached to [rv] (recursing into nested
 * containers — a table's/code block's HorizontalScrollView) by [ratio]. Used for live pinch-zoom:
 * scaling the paint size in place reflows each block instantly with no Markwon re-parse, and
 * Markwon's relative heading spans scale off the new base automatically. Off-screen blocks are
 * fixed up by the single adapter rebuild on gesture end.
 */
private fun scaleVisibleTextViews(rv: RecyclerView, ratio: Float) {
    fun scale(view: View) {
        if (view is TextView) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * ratio)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) scale(view.getChildAt(i))
        }
    }
    for (i in 0 until rv.childCount) scale(rv.getChildAt(i))
}

/**
 * Stacked up/down buttons that jump the preview to the very top / bottom. Subtle and
 * token-colored (Safeguard 4); preview-only (this composable is only used by [MarkdownPreview]).
 * Owns the scroll actions so [MarkdownPreview] stays simple.
 */
@Composable
private fun JumpControls(recyclerView: RecyclerView?, visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return // hidden on short docs that don't scroll
    Column(modifier = modifier) {
        JumpButton(icon = Icons.Filled.KeyboardArrowUp, description = "Scroll to top") {
            (recyclerView?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        }
        Spacer(modifier = Modifier.height(10.dp))
        JumpButton(icon = Icons.Filled.KeyboardArrowDown, description = "Scroll to bottom") {
            recyclerView?.let { rv ->
                val last = (rv.adapter?.itemCount ?: 0) - 1
                if (last >= 0) rv.scrollToPosition(last)
            }
        }
    }
}

@Composable
private fun JumpButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val c = mdColors()
    Surface(
        shape = CircleShape,
        color = c.secondarySurface.copy(alpha = 0.85f),
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = c.secondaryText,
            modifier = Modifier.padding(8.dp),
        )
    }
}
