// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.Context
import android.graphics.Rect
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.R
import com.pilcrowmd.domain.folders.OpenFileLine
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PilcrowTypography

/**
 * The reader's list with the line that names the open file above its first block. The line scrolls
 * away with the text, yet it is not a row of the list: every adapter position stays the block index
 * it always was (headings, search, details, anchors and the saved [com.pilcrowmd.storage.ScrollAnchor]
 * all count blocks). Two parts make that work:
 * - [OpenFileHeaderDecoration] leaves room above block 0 only, so the list itself scrolls the room;
 * - [OpenFileCaptionView] sits over that room, and is moved to the top of block 0 each time the frame
 *   after every scroll of the list and after every layout of it, so it follows a drag, a fling, a
 *   smooth jump, a restore and a rebuild alike. Not from `dispatchDraw`: with hardware drawing a
 *   scroll re-records only the list, so the frame's draw does not run per scroll.
 */
internal class ReaderFrame(context: Context, val list: RecyclerView) : FrameLayout(context) {

    private val header = OpenFileHeaderDecoration()
    val caption = OpenFileCaptionView(context)

    init {
        if (list.id == View.NO_ID) list.id = View.generateViewId()
        list.addItemDecoration(header)
        addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(
            caption,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )
        // Over the list so it is seen, but read first: the list's children come before it otherwise.
        caption.accessibilityTraversalBefore = list.id
        caption.visibility = GONE
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = trackFirstBlock()
        })
    }

    /** Shows [line] above the first block, or no line at all when it is null. */
    fun showOpenFile(line: OpenFileLine?, colors: PilcrowColorScheme, fontScale: Float) {
        val height = caption.bind(line, colors, fontScale)
        caption.visibility = if (height > 0) VISIBLE else GONE
        if (header.heightPx != height) {
            header.heightPx = height
            list.invalidateItemDecorations()
        }
    }

    /**
     * Puts the line at the top of block 0, or above the screen while block 0 is not laid out. Moves
     * nothing when it is already there.
     */
    fun trackFirstBlock() {
        if (caption.visibility != VISIBLE) return
        val layoutManager = list.layoutManager as? LinearLayoutManager ?: return
        val first = layoutManager.findViewByPosition(0)
        val top = if (first == null) {
            -caption.height.toFloat()
        } else {
            (list.top + first.top - layoutManager.getTopDecorationHeight(first)).toFloat()
        }
        if (caption.translationY != top) caption.translationY = top
    }

    /**
     * A layout of the list (a restore, a jump to a block, a rebuild, a change of the line's room) asks
     * for a layout of this frame too, because a request for layout reaches every parent. By then the
     * list has placed its children, so block 0 is where it is going to be drawn.
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        trackFirstBlock()
    }
}

/** Makes room above the list's first block, and only there, for the line that names the open file. */
internal class OpenFileHeaderDecoration : RecyclerView.ItemDecoration() {
    var heightPx = 0

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        if (heightPx > 0 && parent.getChildAdapterPosition(view) == 0) outRect.top = heightPx
    }
}

/**
 * The line that names the open file, as a view: the same look as [OpenFileCaption] (an icon, then the
 * text, 12 sp, secondary text colour), shortened in the middle so the ".md" stays. Not tappable.
 * TalkBack reads [OpenFileLine] once, as "File: Folder, sub, name.md". Its height is worked out from the
 * text size and fixed, so the room reserved above block 0 is exactly the line's height.
 */
internal class OpenFileCaptionView(context: Context) : TextView(context) {

    private var shown: Triple<OpenFileLine, Int, Float>? = null
    private var heightPx = 0

    init {
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.MIDDLE
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        isClickable = false
        isFocusable = false
    }

    /** Applies [line] and returns the height the line takes, 0 for none. Does nothing if nothing changed. */
    fun bind(line: OpenFileLine?, colors: PilcrowColorScheme, fontScale: Float): Int {
        if (line == null) {
            shown = null
            heightPx = 0
            return 0
        }
        val color = colors.secondaryText.toArgb()
        val key = Triple(line, color, fontScale)
        if (shown == key) return heightPx
        shown = key

        val metrics = resources.displayMetrics
        val density = metrics.density
        setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                PilcrowTypography.CAPTION_FONT_SIZE_SP * fontScale,
                metrics,
            ),
        )
        setTextColor(color)
        text = line.caption
        contentDescription = if (line.inFolder) {
            context.getString(R.string.main_open_file_description, line.spokenFolderPath, line.name)
        } else {
            context.getString(R.string.main_open_file_description_plain, line.name)
        }
        val side = (SIDE_DP * density).toInt()
        setPaddingRelative(side, 0, side, 0)
        val icon = ContextCompat.getDrawable(
            context,
            if (line.inFolder) R.drawable.ic_open_file_folder else R.drawable.ic_open_file_document,
        )?.mutate()
        icon?.setTint(color)
        val iconSize = (ICON_DP * fontScale * density).toInt()
        icon?.setBounds(0, 0, iconSize, iconSize)
        setCompoundDrawablesRelative(icon, null, null, null)
        compoundDrawablePadding = (ICON_GAP_DP * density).toInt()
        // One line of the text at its size (or the icon, if taller), with the same room above and below it
        // as the pinned line has.
        val line = paint.fontMetricsInt.let { it.descent - it.ascent }
        heightPx = maxOf(line, iconSize) + (2 * VERTICAL_DP * density).toInt()
        setHeight(heightPx)
        return heightPx
    }

    private companion object {
        const val SIDE_DP = 20f // adapter_default_prose.xml's side padding, so the line starts where the text does
        const val VERTICAL_DP = 6f
        const val ICON_DP = 14f
        const val ICON_GAP_DP = 6f
    }
}
