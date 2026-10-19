// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.ViewGroup
import com.pilcrowmd.domain.markdown.CodeBlockGroup
import com.pilcrowmd.domain.markdown.CodeBlockPiece
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.commonmark.node.FencedCodeBlock
import java.util.WeakHashMap

/**
 * Issue #17: how [FencedCodeBlockEntry] binds a [CodeBlockPiece], one piece of a long code block cut by
 * [com.pilcrowmd.domain.markdown.CodeBlockSplitter]. The pieces of one block are drawn as one block:
 * no gap, border or padding where two meet, the line pitch unbroken across the join, rounded corners
 * and padding only at the block's top and bottom, and one sideways offset and width for all of them
 * ([CodePieceViews]). The whole block is coloured once, off Main, and each piece paints its own share
 * of the runs when bound. A highlighter that colours the front of a block first (json,
 * [Prism4jCodeHighlighter]) lets the pieces it has covered paint while the rest is still being coloured.
 *
 * One per entry, so per adapter: a rebuilt adapter (theme, pinch) starts with no shared views, and the
 * colour runs come back from the highlighter's cache.
 */
internal class CodePieceBinder(
    private val colorScheme: PilcrowColorScheme,
    private val wrapLines: Boolean,
    private val highlighting: CodeHighlighting?,
    private val dp: (Float) -> Int,
) {
    /**
     * The views the pieces of each block share in this adapter. Weak: the adapter outlives the models it
     * shows (each edit makes new blocks), and a block no longer shown must not be kept. A group is
     * compared by identity, as it does not override `equals`.
     */
    private val pieceViews = WeakHashMap<CodeBlockGroup, CodePieceViews>()

    /** Attach [holder] to its piece's block. Called before anything paints the piece. */
    fun attach(holder: FencedCodeBlockEntry.Holder, piece: CodeBlockPiece) {
        val views = pieceViews.getOrPut(piece.group) { CodePieceViews() }
        views.attach(holder)
        holder.pieceViews = views
        holder.piece = piece
    }

    /**
     * A piece as Markwon renders a code block, with no language (so nothing is coloured at bind) and
     * without the blank line Markwon adds above and below a block where the piece meets another, so
     * the lines run on across the join.
     */
    fun render(markwon: Markwon, piece: CodeBlockPiece): Spanned {
        val plain = FencedCodeBlock().apply {
            fenceChar = '`'
            fenceLength = PIECE_FENCE_LENGTH
            literal = piece.literal
        }
        val text = SpannableStringBuilder(markwon.render(plain))
        if (!piece.isLast && text.endsWith(MARKWON_CODE_TAIL)) {
            text.delete(text.length - MARKWON_CODE_TAIL.length, text.length)
        }
        if (!piece.isFirst && text.startsWith(MARKWON_CODE_HEAD)) text.delete(0, MARKWON_CODE_HEAD.length)
        return text
    }

    /**
     * Colour a piece with its share of the whole block's runs. The block is tokenised once, off Main,
     * whichever piece is bound first; every piece shown then paints its own share, one per main-thread
     * turn, and a piece bound later paints at bind. A piece the colouring has already covered (json, a
     * window at a time) paints at bind even while the rest is still coming. Without a scope (no reader)
     * it is tokenised inline.
     */
    fun highlight(holder: FencedCodeBlockEntry.Holder, piece: CodeBlockPiece) {
        val views = holder.pieceViews ?: return
        val engine = highlighting?.engineFor(piece.info, coloursAtBind = false)
        val known = views.slices
            ?: engine?.let { piece.readyRuns(it) }?.let { runs -> sliceRuns(runs, piece.group) }
        if (known != null) {
            views.slices = known
            paint(holder, known)
        } else if (engine != null) {
            views.partialSlices?.takeIf { piece.index < views.coveredPieces }?.let { paint(holder, it) }
            if (views.job == null) colourLater(views, piece.group, engine)
        }
    }

    /** The block's runs if they need no wait: cached, or tokenised inline when there is no scope. */
    private fun CodeBlockPiece.readyRuns(engine: Pair<CodeHighlighter, String>): List<CodeRun>? {
        val (highlighter, scopeName) = engine
        return highlighter.cachedRuns(scopeName, group.literal)
            ?: highlighter.takeIf { highlighting?.scope == null }?.tokenize(scopeName, group.literal)
    }

    private fun colourLater(views: CodePieceViews, group: CodeBlockGroup, engine: Pair<CodeHighlighter, String>) {
        val scope = highlighting?.scope ?: return
        val (highlighter, scopeName) = engine
        views.job = scope.launch {
            // Where the job was launched: each window's pieces are handed back to it from the tokenising thread.
            val home = currentCoroutineContext().minusKey(Job)
            val slices = withContext(Dispatchers.Default) {
                var whole: List<List<CodeRun>>? = null
                val runs = highlighter.tokenizeProgressively(scopeName, group.literal) { runsSoFar, coveredChars ->
                    // Cut here, off Main; only the painting is for Main.
                    val partial = sliceRuns(runsSoFar, group)
                    val covered = piecesCoveredBy(group, coveredChars)
                    if (covered == group.pieceCount) whole = partial
                    withContext(home) {
                        views.partialSlices = partial
                        views.coveredPieces = covered
                        paintAttached(views, partial, covered)
                    }
                }
                // The last window covered the block: its cut is the answer, not to be cut again.
                runs?.let { whole ?: sliceRuns(it, group) }
            }
            if (slices == null) {
                views.job = null
                return@launch
            }
            views.slices = slices
            views.partialSlices = null
            paintAttached(views, slices, group.pieceCount)
        }
    }

    /**
     * Paint every piece shown that is not yet coloured and is among the first [covered], each in its own
     * turn, so a frame can be drawn between two of them.
     */
    private suspend fun paintAttached(views: CodePieceViews, slices: List<List<CodeRun>>, covered: Int) {
        fun CodePieceViews.waitsFor(holder: FencedCodeBlockEntry.Holder) =
            holder.pieceViews === this && !holder.pieceColoured && (holder.piece?.index ?: Int.MAX_VALUE) < covered
        for (shown in views.attached) {
            if (!views.waitsFor(shown)) continue
            yield()
            if (views.waitsFor(shown)) paint(shown, slices)
        }
    }

    private fun paint(holder: FencedCodeBlockEntry.Holder, slices: List<List<CodeRun>>) {
        if (holder.pieceColoured) return
        val piece = holder.piece ?: return
        val offset = codeOffset(holder.codeView.text, piece.literal) ?: return
        paintRuns(holder.codeView, offset, slices[piece.index], colorScheme.codeSyntax, colorScheme.editorText.toArgb())
        holder.pieceColoured = true
    }

    /**
     * Lay a piece out so it joins the pieces around it with no seam. Where it meets another: no item
     * margin and no vertical padding, and the line pitch across the join made equal to the pitch inside
     * a block ([seamBottomPadding], from the metrics Markwon's code span gives the text). It also takes
     * the block's sideways offset and, when lines do not wrap, the block's width.
     */
    fun join(holder: FencedCodeBlockEntry.Holder, piece: CodeBlockPiece) {
        val view = holder.codeView
        val seamBottom =
            seamBottomPadding(codeFontMetrics(view), view.lineSpacingMultiplier, !piece.isFirst, !piece.isLast)
        view.includeFontPadding = piece.isFirst
        view.setPaddingRelative(
            view.paddingStart,
            if (piece.isFirst) holder.defaultPaddingTop else 0,
            view.paddingEnd,
            (if (piece.isLast) holder.defaultPaddingBottom else 0) + seamBottom,
        )
        (holder.itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.topMargin = if (piece.isFirst) holder.defaultMarginTop else 0
            params.bottomMargin = if (piece.isLast) holder.defaultMarginBottom else 0
            holder.itemView.layoutParams = params
        }
        val views = holder.pieceViews
        if (views != null && !wrapLines) {
            view.minWidth = views.widthAt(view.textSize)
            holder.codeScroll.scrollToAfterLayout(views.scrollX)
        }
    }

    /**
     * The code-block surface for a piece: rounded only at the block's top and bottom, and with no border
     * line where it meets another piece (the shape is drawn past the view's edge there, and clipped).
     */
    fun surface(piece: CodeBlockPiece): Drawable {
        val radius = dp(CORNER_DP).toFloat()
        val stroke = dp(1f)
        val top = if (piece.isFirst) radius else 0f
        val bottom = if (piece.isLast) radius else 0f
        val shape = GradientDrawable().apply {
            cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
            setColor(colorScheme.codeBlockBg.toArgb())
            setStroke(stroke, colorScheme.codeBlockBorder.toArgb())
        }
        return InsetDrawable(shape, 0, if (piece.isFirst) 0 else -stroke, 0, if (piece.isLast) 0 else -stroke)
    }

    private companion object {
        // Markwon 4.6.2's CorePlugin.visitCodeBlock writes a no-break space and a newline before the
        // code, and (the code ending in its own newline) a no-break space after it.
        const val MARKWON_CODE_HEAD = " \n"
        const val MARKWON_CODE_TAIL = "\n "
        const val PIECE_FENCE_LENGTH = 3
        const val CORNER_DP = 6f
    }
}
