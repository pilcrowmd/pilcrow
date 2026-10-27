// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.CodeBlockPiece
import com.pilcrowmd.domain.markdown.CodeBlockSplitter
import com.pilcrowmd.domain.markdown.MERMAID_LANGUAGE
import com.pilcrowmd.domain.markdown.fenceLanguage
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRenderer
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRequest
import com.pilcrowmd.rendering.mermaid.MermaidStyle
import com.pilcrowmd.ui.theme.CodeSyntaxColors
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.FontSet
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.PreviewLineHeightMultiplier
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.commonmark.node.FencedCodeBlock

/**
 * MarkwonAdapter.Entry for FencedCodeBlock — renders each fenced code block into its own
 * HorizontalScrollView so long lines pan sideways while surrounding prose stays wrapped.
 * Prism4j syntax highlighting comes from the shared Markwon instance.
 *
 * Copy affordance: a "Copy" button pinned top-right OUTSIDE the HorizontalScrollView (in the
 * layout's FrameLayout), so it stays reachable while a long line is scrolled. It copies the
 * block's exact literal text. (The old span-based CodeBlockCopyPlugin was removed — it dropped
 * the code content.)
 *
 * [wrapLines] is the reader's "Wrap long lines in code blocks" setting (M-134, default off). The
 * holder is shared with the YAML and Mermaid lanes and survives an adapter swap, so every bind here
 * sets it, on or off — never only when it is on.
 *
 * [highlighting] colours the languages Prism4j has no grammar for (M-136, [TextMateCodeHighlighter]).
 * Null leaves them plain. With a scope, a block not yet in the cache shows plain at once and takes its
 * colours when the job ends; without one (the PDF) it is tokenised inline.
 *
 * A [CodeBlockPiece] (issue #17) is one piece of a long block; [CodePieceBinder] draws the pieces of
 * one block as one block. Its Copy button is on the first piece only, and copies the whole block.
 *
 * [mermaid] draws ```mermaid blocks on the device, offline (M-70, [bindMermaid]). Null draws none:
 * every diagram shows its source with the caption, as when a render fails. The PDF passes none: it
 * draws its diagrams itself, synchronously, and binds each with [bindDiagram].
 *
 * try/catch in bindHolder degrades gracefully on unexpected content (Safeguard 3).
 */
@Suppress("LongParameterList") // The reader's settings plus the M-136 highlighter and the M-70 renderer.
class FencedCodeBlockEntry(
    private val context: Context,
    private val fontScale: Float = 1.0f,
    private val fontSet: FontSet = FontSets.DEFAULT,
    private val colorScheme: PilcrowColorScheme = DarkColorScheme,
    private val searchHighlight: SearchHighlight = SearchHighlight(),
    private val wrapLines: Boolean = false,
    private val highlighting: CodeHighlighting? = null,
    private val mermaid: MermaidDiagrams? = null,
) : MarkwonAdapter.Entry<FencedCodeBlock, FencedCodeBlockEntry.Holder>() {

    // A function value, so CodePieceBinder can share it.
    private val dp: (Float) -> Int = { value ->
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
    }

    // Issue #17: the pieces of long blocks, and what each block's pieces share.
    private val pieces = CodePieceBinder(colorScheme, wrapLines, highlighting, dp)

    override fun createHolder(inflater: LayoutInflater, parent: ViewGroup): Holder {
        val root = inflater.inflate(R.layout.adapter_code_block, parent, false)
        // Let a wide code block pan sideways inside the vertical RecyclerView.
        root.findViewById<HorizontalScrollView>(R.id.code_scroll)?.enableHorizontalNestedScroll()
        val holder = Holder(root)
        // Chrome opts out of pinch scaling: neither of these takes its size from the reader font
        // scale, so nothing re-applies it on bind and a PX size written by the gesture would survive
        // at rest forever. `codeView` is NOT excluded — bindHolder re-applies its size. See M-04.
        holder.copyButton.setTag(R.id.pinch_excluded, true)
        holder.mermaidCaption.setTag(R.id.pinch_excluded, true)
        // Issue #17: a piece of a long block pans, and widens, with the block's other pieces.
        holder.codeScroll.setOnScrollChangeListener { _, x, _, _, _ ->
            holder.pieceViews?.onScrolled(holder.codeScroll, x)
        }
        holder.codeView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            // Wrapped lines do not pan, so the pieces need no common width.
            if (!holder.codeScroll.wrapLines) holder.pieceViews?.onLaidOut(view.width)
        }
        return holder
    }

    override fun bindHolder(markwon: Markwon, holder: Holder, node: FencedCodeBlock) {
        holder.cancelPendingHighlight()
        holder.resetPiece()
        val piece = node as? CodeBlockPiece
        if (piece != null) pieces.attach(holder, piece)
        // Shared-holder state: this holder is recycled across code, yaml, and mermaid blocks, so
        // restore the code views and hide the diagram and its caption. A pending Mermaid render is NOT
        // cancelled here: that is the router's one reset ([Holder.resetMermaid]), and the Mermaid
        // fallback binds through this very function from inside that render's job.
        holder.codeScroll.visibility = View.VISIBLE
        holder.copyButton.visibility = View.VISIBLE
        holder.mermaidImage.visibility = View.GONE
        holder.mermaidCaption.visibility = View.GONE
        holder.codeScroll.wrapLines = wrapLines
        // Size is re-applied on EVERY bind and BEFORE the try, not inside it. Two reasons, and the
        // second is why it moved: a reused holder must not keep the PX size the live pinch wrote
        // (createHolder does not run again for one — see M-04), and if `markwon.render` throws, the
        // catch below degrades the block WITHOUT restoring its size, so the one path that already
        // failed would be the one left permanently mis-sized. Same value as the success path sets,
        // so resting layout is unchanged.
        holder.codeView.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            PilcrowTypography.CODE_BLOCK_FONT_SIZE_SP * fontScale,
        )
        try {
            // Real code rendering (Prism4j highlighting applied by the shared Markwon instance). A
            // piece is rendered plain: its colours come from the whole block ([CodePieceBinder.highlight]).
            val rendered = if (piece == null) markwon.render(node) else pieces.render(markwon, piece)
            markwon.setParsedMarkdown(holder.codeView, rendered)
            // setParsedMarkdown installs Markwon's LinkMovementMethod (a
            // ScrollingMovementMethod) which CONSUMES touch drags on the TextView — that swallows the
            // horizontal pan before it reaches the enclosing HorizontalScrollView, so a wide code
            // block can't be panned by finger (table cells drop it the same way unless they
            // hold a link or footnote marker). Code blocks have no clickable links, so drop it.
            holder.codeView.movementMethod = null
            // Before the search highlight, which copies the text and so keeps these spans.
            if (piece == null) highlightCode(holder, node) else pieces.highlight(holder, piece)
            holder.codeView.typeface =
                ResourcesCompat.getFont(context, fontSet.monoRegular)
            holder.codeView.setLineSpacing(0f, PreviewLineHeightMultiplier)
            if (piece != null) pieces.join(holder, piece)
            // Highlight search matches inside the code (single TextView → occurrenceBase 0). Not in a
            // Mermaid fence: its code is only ever a broken diagram's source, and search does not
            // count text a diagram is drawn from (M-228), so painting it would show uncounted matches.
            // A piece of a Mermaid fence past the diagram limit is the exception: it is never drawn as a
            // diagram, search counts its source as code, so it must paint it.
            if (piece != null || fenceLanguage(node.info) != MERMAID_LANGUAGE) {
                SearchHighlighter.highlight(
                    holder.codeView,
                    searchHighlight,
                    blockIsFocused = holder.bindingAdapterPosition == searchHighlight.focusedPosition,
                    occurrenceBase = 0,
                )
            }

            bindSurface(holder, node, piece)
        } catch (e: Exception) {
            // Graceful-ignore: never crash the adapter on a bad/unsupported block (Safeguard 3).
            Log.e("FencedCodeBlockEntry", "render failed: ${e.message}", e)
            // A piece that failed shows the message as an ordinary block would, off its block's views.
            holder.resetPiece()
            holder.codeView.text = context.getString(R.string.render_code_block_failed)
            holder.codeView.setTextColor(colorScheme.secondaryText.toArgb())
        }
    }

    /** The code-block surface and the Copy button, from the token layer (no hardcoded hex). */
    private fun bindSurface(holder: Holder, node: FencedCodeBlock, piece: CodeBlockPiece?) {
        // Code-block surface: bg + subtle border. A piece of a long block joins the pieces around it.
        holder.codeScroll.background = piece?.let(pieces::surface) ?: GradientDrawable().apply {
            cornerRadius = dp(6f).toFloat()
            setColor(colorScheme.codeBlockBg.toArgb())
            setStroke(dp(1f), colorScheme.codeBlockBorder.toArgb())
        }

        // Copy affordance (pinned overlay) — copies the exact block text.
        holder.copyButton.setTextColor(colorScheme.secondaryText.toArgb())
        holder.copyButton.background = GradientDrawable().apply {
            cornerRadius = dp(4f).toFloat()
            setColor(colorScheme.secondarySurface.toArgb())
            setStroke(dp(1f), colorScheme.codeBlockBorder.toArgb())
        }
        // A long block's Copy button is on its first piece only, and copies the whole block.
        if (piece != null && !piece.isFirst) holder.copyButton.visibility = View.GONE
        val literal = piece?.group?.literal ?: node.literal
        holder.copyButton.setOnClickListener {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("code", literal))
            Toast.makeText(context, context.getString(R.string.render_code_copied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.cancelPendingHighlight()
        holder.resetPiece()
    }

    /**
     * M-136: colour a block in one of the TextMate languages. A cached result is applied now; otherwise
     * the reader tokenises off Main and applies the result only if this holder still shows this block.
     */
    private fun highlightCode(holder: Holder, node: FencedCodeBlock) {
        val code = node.literal ?: return
        val (highlighter, scopeName) =
            highlighting?.engineFor(node.info, coloursAtBind = code.length <= MAX_HIGHLIGHT_CHARS) ?: return
        val scope = highlighting?.scope
        val cached = highlighter.cachedRuns(scopeName, code)
        when {
            cached != null -> paintCode(holder.codeView, code, cached, colorScheme)
            scope == null ->
                highlighter.tokenize(scopeName, code)?.let { paintCode(holder.codeView, code, it, colorScheme) }
            else -> {
                val key = Any()
                holder.highlightKey = key
                holder.highlightJob = scope.launch {
                    val runs = highlighter.tokenizeAsync(scopeName, code) ?: return@launch
                    // M-222's stale-result class: the holder may show another block (or YAML, or a
                    // Mermaid image) by now. Every bind clears the key, so only this bind's result lands.
                    if (holder.highlightKey !== key) return@launch
                    holder.highlightKey = null
                    holder.highlightJob = null
                    paintCode(holder.codeView, code, runs, colorScheme)
                }
            }
        }
    }

    /**
     * M-70: show a ```mermaid block as its diagram, drawn on the device by [mermaid]'s renderer.
     *
     * The panel shows empty (the image's minHeight) until the render answers. A picture is shown in
     * place of the code; no picture (bad syntax, no engine, timeout) shows the [showSource] fallback.
     * The answer lands only if this holder still shows this diagram: the router's reset cancels the
     * job on every rebind, and the key check below refuses whatever outlives that. Preview-only: the
     * export path never calls this.
     */
    fun bindMermaid(markwon: Markwon, holder: Holder, node: FencedCodeBlock) {
        val mermaid = mermaid ?: return showSource(markwon, holder, node)
        val source = node.literal ?: ""
        // Past Mermaid's own limit it cannot be drawn: no key is hashed over it and no page is loaded.
        if (source.length > CodeBlockSplitter.MERMAID_MAX_CHARS) return showSource(markwon, holder, node)
        showDiagramPanel(holder)
        holder.mermaidImage.setImageDrawable(null)
        val style = MermaidStyle.from(colorScheme)
        val width = mermaidWidthPx()
        val key = MermaidKey(source, width, style)
        holder.mermaidKey = key
        // UNDISPATCHED: a renderer that already has the answer lands it in this bind, with no empty frame.
        holder.mermaidJob = mermaid.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val bitmap = mermaid.renderer.render(source, width, style)
            // The holder may show another block by now (M-222's stale-result class).
            if (holder.mermaidKey != key) return@launch
            if (bitmap == null) return@launch showSource(markwon, holder, node)
            holder.mermaidImage.setImageBitmap(bitmap)
            mermaid.onOpen?.let { holder.openOnTap(MermaidDiagramRequest(source, width), it) }
        }
    }

    /**
     * M-70, the PDF's Mermaid bind: [picture], already drawn by the caller, in the diagram panel; or,
     * when there is none, the same source fallback the reader shows.
     */
    fun bindDiagram(markwon: Markwon, holder: Holder, node: FencedCodeBlock, picture: Drawable?) {
        if (picture == null) return showSource(markwon, holder, node)
        showDiagramPanel(holder)
        holder.mermaidImage.setImageDrawable(picture)
    }

    /** The diagram panel in place of the code: the code-block surface, no Copy button, no caption. */
    private fun showDiagramPanel(holder: Holder) {
        holder.codeScroll.visibility = View.GONE
        holder.copyButton.visibility = View.GONE
        holder.mermaidCaption.visibility = View.GONE
        holder.mermaidImage.visibility = View.VISIBLE
        holder.mermaidImage.background = GradientDrawable().apply {
            cornerRadius = dp(6f).toFloat()
            setColor(colorScheme.codeBlockBg.toArgb())
            setStroke(dp(1f), colorScheme.codeBlockBorder.toArgb())
        }
    }

    /** Card content width in px (screen minus the card's horizontal margins + the image padding). */
    private fun mermaidWidthPx(): Int {
        val screen = context.resources.displayMetrics.widthPixels
        val chrome = (dp(CARD_MARGIN_DP) + dp(IMAGE_PADDING_DP)) * 2
        return (screen - chrome).coerceIn(MERMAID_MIN_W, MERMAID_MAX_W)
    }

    /**
     * The Mermaid fallback (Safeguard 3): the diagram's source as an ordinary code block, Copy button
     * included, plus a caption saying why.
     */
    private fun showSource(markwon: Markwon, holder: Holder, node: FencedCodeBlock) {
        bindHolder(markwon, holder, node)
        holder.mermaidCaption.text = context.getString(R.string.render_mermaid_fallback_caption)
        holder.mermaidCaption.setTextColor(colorScheme.secondaryText.toArgb())
        holder.mermaidCaption.visibility = View.VISIBLE
    }

    /** What a Mermaid render was asked to draw; a result lands only while the holder still holds it. */
    private data class MermaidKey(val source: String, val widthPx: Int, val style: MermaidStyle)

    /** ViewHolder: code views (shared with yaml + mermaid), plus the mermaid image and caption. */
    class Holder(itemView: View) : MarkwonAdapter.Holder(itemView) {
        val codeScroll: CodeScrollView = requireView(R.id.code_scroll)
        val codeView: TextView = requireView(R.id.code_text)
        val copyButton: TextView = requireView(R.id.code_copy)
        val mermaidImage: ImageView = requireView(R.id.mermaid_image)
        val mermaidCaption: TextView = requireView(R.id.mermaid_caption)

        // Issue #17: the layout's own margins and padding, restored on every bind ([resetPiece]).
        internal val defaultMarginTop = (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        internal val defaultMarginBottom = (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        internal val defaultPaddingTop = codeView.paddingTop
        internal val defaultPaddingBottom = codeView.paddingBottom

        // Issue #17: the piece of a long block this holder shows, and the views its block shares.
        internal var piece: CodeBlockPiece? = null
        internal var pieceViews: CodePieceViews? = null
        internal var pieceColoured = false

        // M-404: one view for code, long-block pieces and the frontmatter card; spoken as plain text.
        init {
            codeView.plainTextForAccessibility()
        }

        /**
         * Issue #17: back to an ordinary block, on every bind of every route and on recycle: off the
         * block's shared views, and the layout's own margins, padding and font padding, no minimum width.
         */
        fun resetPiece() {
            pieceViews?.detach(this)
            pieceViews = null
            piece = null
            pieceColoured = false
            codeView.includeFontPadding = true
            codeView.minWidth = 0
            codeScroll.resetScroll()
            codeView.setPaddingRelative(
                codeView.paddingStart,
                defaultPaddingTop,
                codeView.paddingEnd,
                defaultPaddingBottom,
            )
            (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                if (params.topMargin != defaultMarginTop || params.bottomMargin != defaultMarginBottom) {
                    params.topMargin = defaultMarginTop
                    params.bottomMargin = defaultMarginBottom
                    itemView.layoutParams = params
                }
            }
        }

        // M-136: the bind whose colours may still land, and its job. Cleared on every bind of any route.
        internal var highlightKey: Any? = null
        internal var highlightJob: Job? = null

        fun cancelPendingHighlight() {
            highlightJob?.cancel()
            highlightJob = null
            highlightKey = null
        }

        // M-70: the diagram this holder is waiting for, and its render. Cleared only by [resetMermaid].
        internal var mermaidKey: Any? = null
        internal var mermaidJob: Job? = null

        /**
         * The one reset of the Mermaid lane, called by the router before every bind and on recycle
         * (M-256, M-120). Cancels a render still running for the block this holder showed before (its
         * answer would otherwise draw that diagram, or write its source into [codeView], over the new
         * block: M-222), and hides the image and the caption, which no other lane hides for itself.
         */
        fun resetMermaid() {
            mermaidJob?.cancel()
            mermaidJob = null
            mermaidKey = null
            mermaidImage.setImageDrawable(null)
            mermaidImage.visibility = View.GONE
            mermaidCaption.visibility = View.GONE
            // S8-A7: only a drawn diagram opens full screen; setOnClickListener(null) leaves the view clickable.
            mermaidImage.setOnClickListener(null)
            mermaidImage.isClickable = false
            ViewCompat.replaceAccessibilityAction(mermaidImage, AccessibilityActionCompat.ACTION_CLICK, null, null)
        }

        /** S8-A7: tapping the drawn picture calls [onOpen]; TalkBack announces "double-tap to open full screen". */
        internal fun openOnTap(request: MermaidDiagramRequest, onOpen: (MermaidDiagramRequest) -> Unit) {
            mermaidImage.setOnClickListener { onOpen(request) }
            // A label with no command: the view's own click still runs. TalkBack reads it after "double-tap to".
            ViewCompat.replaceAccessibilityAction(
                mermaidImage,
                AccessibilityActionCompat.ACTION_CLICK,
                mermaidImage.context.getString(R.string.render_mermaid_open_full_screen),
                null,
            )
        }
    }

    private companion object {
        // Bounds for the requested mermaid render width (px): never absurdly small, capped so a
        // huge tablet/landscape request stays sane for the on-device renderer.
        const val MERMAID_MIN_W = 320
        const val MERMAID_MAX_W = 1600
        const val CARD_MARGIN_DP = 20f // adapter_code_block FrameLayout horizontal margin
        const val IMAGE_PADDING_DP = 12f // mermaid_image horizontal padding
    }
}

// Markwon 4.6.2's CorePlugin.visitCodeBlock writes a no-break space and a newline before the code.
private const val MARKWON_CODE_PREFIX = 2

/** [runs] painted on [view], which shows Markwon's render of [code]. */
private fun paintCode(view: TextView, code: String, runs: List<CodeRun>, colorScheme: PilcrowColorScheme) {
    val offset = codeOffset(view.text, code) ?: return
    paintRuns(view, offset, runs, colorScheme.codeSyntax, colorScheme.editorText.toArgb())
}

/**
 * M-136: add the runs' colours to [view]'s text in place, so no new layout is built and the search
 * highlight stays. The code starts at [offset] in the text (after Markwon's short prefix), and a run
 * past the end of the text is dropped (Safeguard 3). A run with no colour of its own in this scheme,
 * [CodeRole.TEXT] among them, takes [textColor]; a diff
 * line also gets its tint (M-216), as Prism4j's theme gives it at bind.
 */
internal fun paintRuns(view: TextView, offset: Int, runs: List<CodeRun>, syntax: CodeSyntaxColors, textColor: Int) {
    // Markwon sets the text as a Spannable, and SearchHighlighter keeps it one (measured); anything
    // else is left plain rather than set again.
    val target = view.text as? Spannable ?: return
    runs.forEach { run ->
        val start = offset + run.start
        val end = offset + run.end
        if (start !in 0 until end || end > target.length) return@forEach
        // A role this scheme leaves without a colour takes the text colour, as Prism4j's theme gives it.
        val color = run.role.colorIn(syntax)?.toArgb() ?: textColor
        target.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        run.role.lineBackgroundIn(syntax)?.let {
            target.setSpan(DiffLineBackgroundSpan(it.toArgb()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

/**
 * The font metrics [view]'s code is drawn with: the TextView's paint as Markwon's code span changes it
 * (typeface, size), read at the first character. What the seam arithmetic must use (issue #17).
 */
internal fun codeFontMetrics(view: TextView): Paint.FontMetricsInt {
    val paint = TextPaint(view.paint)
    (view.text as? Spanned)?.takeIf { it.isNotEmpty() }
        ?.getSpans(0, 1, MetricAffectingSpan::class.java)
        ?.forEach { it.updateMeasureState(paint) }
    return paint.fontMetricsInt
}

/** Where [code] starts in [text], or null if the text does not hold it (e.g. the failure message). */
internal fun codeOffset(text: CharSequence, code: String): Int? {
    val body = code.trimEnd('\n')
    val at = if (text.startsWith(body, MARKWON_CODE_PREFIX)) MARKWON_CODE_PREFIX else text.indexOf(body)
    return at.takeIf { it >= 0 && body.isNotEmpty() }
}

/**
 * The reader's Mermaid renderer plus the scope its renders run in, cancelled with the Preview (M-70).
 * [onOpen] is called when a drawn diagram is tapped, to open it full screen; null leaves diagrams untappable.
 */
class MermaidDiagrams(
    val renderer: MermaidDiagramRenderer,
    val scope: CoroutineScope,
    val onOpen: ((MermaidDiagramRequest) -> Unit)? = null,
)
