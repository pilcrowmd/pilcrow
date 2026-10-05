// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Base64
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
import coil.dispose
import coil.load
import com.pilcrowmd.R
import com.pilcrowmd.ui.theme.CodeSyntaxColors
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.FontSet
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.PreviewLineHeightMultiplier
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
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
 * try/catch in bindHolder degrades gracefully on unexpected content (Safeguard 3).
 */
@Suppress("LongParameterList") // The reader's settings plus the M-136 highlighter.
class FencedCodeBlockEntry(
    private val context: Context,
    private val fontScale: Float = 1.0f,
    private val fontSet: FontSet = FontSets.DEFAULT,
    private val colorScheme: PilcrowColorScheme = DarkColorScheme,
    private val searchHighlight: SearchHighlight = SearchHighlight(),
    private val wrapLines: Boolean = false,
    private val highlighting: CodeHighlighting? = null,
) : MarkwonAdapter.Entry<FencedCodeBlock, FencedCodeBlockEntry.Holder>() {

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics,
    ).toInt()

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
        return holder
    }

    override fun bindHolder(markwon: Markwon, holder: Holder, node: FencedCodeBlock) {
        holder.cancelPendingHighlight()
        // Reset shared-holder state: this holder is recycled across code, yaml, and mermaid
        // blocks, so restore the code views, hide the mermaid image, and hide the caption.
        holder.cancelPendingMermaid()
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
            // Real code rendering (Prism4j highlighting applied by the shared Markwon instance).
            markwon.setParsedMarkdown(holder.codeView, markwon.render(node))
            // setParsedMarkdown installs Markwon's LinkMovementMethod (a
            // ScrollingMovementMethod) which CONSUMES touch drags on the TextView — that swallows the
            // horizontal pan before it reaches the enclosing HorizontalScrollView, so a wide code
            // block can't be panned by finger (tables scroll because their cells use plain setText
            // with no movement method). Code blocks have no clickable links, so drop it.
            holder.codeView.movementMethod = null
            // Before the search highlight, which copies the text and so keeps these spans.
            highlightCode(holder, node)
            holder.codeView.typeface =
                ResourcesCompat.getFont(context, fontSet.monoRegular)
            holder.codeView.setLineSpacing(0f, PreviewLineHeightMultiplier)
            // Highlight search matches inside the code (single TextView → occurrenceBase 0).
            SearchHighlighter.highlight(
                holder.codeView,
                searchHighlight,
                blockIsFocused = holder.bindingAdapterPosition == searchHighlight.focusedPosition,
                occurrenceBase = 0,
            )

            // Code-block surface: bg + subtle border, from the token layer (no hardcoded hex).
            holder.codeScroll.background = GradientDrawable().apply {
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
            val literal = node.literal
            holder.copyButton.setOnClickListener {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("code", literal))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            // Graceful-ignore: never crash the adapter on a bad/unsupported block (Safeguard 3).
            Log.e("FencedCodeBlockEntry", "render failed: ${e.message}", e)
            holder.codeView.text = "[code block could not be rendered]"
            holder.codeView.setTextColor(colorScheme.secondaryText.toArgb())
        }
    }

    override fun onViewRecycled(holder: Holder) = holder.cancelPendingHighlight()

    /**
     * M-136: colour a block in one of the TextMate languages. A cached result is applied now; otherwise
     * the reader tokenises off Main and applies the result only if this holder still shows this block.
     */
    private fun highlightCode(holder: Holder, node: FencedCodeBlock) {
        val highlighting = highlighting ?: return
        val scopeName = TextMateCodeHighlighter.scopeForFence(node.info) ?: return
        val code = node.literal ?: return
        val highlighter = highlighting.highlighter
        val scope = highlighting.scope
        val cached = highlighter.cachedRuns(scopeName, code)
        when {
            cached != null -> paintRuns(holder.codeView, code, cached, colorScheme.codeSyntax)
            scope == null -> highlighter.tokenize(scopeName, code)?.let {
                paintRuns(holder.codeView, code, it, colorScheme.codeSyntax)
            }
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
                    paintRuns(holder.codeView, code, runs, colorScheme.codeSyntax)
                }
            }
        }
    }

    /**
     * Render a ```mermaid block as raw source (normal code block) PLUS a small caption telling the
     * user how to get a rendered chart. Used when Mermaid cloud rendering is OFF — the
     * default. The caption is honest about the cloud dependency (the only non-WebView render path
     * sends the source to mermaid.ink). Preview-only: the export path never calls this.
     */
    fun bindMermaidOff(markwon: Markwon, holder: Holder, node: FencedCodeBlock) {
        bindHolder(markwon, holder, node) // render the source as a normal (highlighted) code block
        holder.mermaidCaption.text = MERMAID_OFF_CAPTION
        holder.mermaidCaption.setTextColor(colorScheme.secondaryText.toArgb())
        holder.mermaidCaption.visibility = View.VISIBLE
    }

    /**
     * Render a ```mermaid block as an image from the mermaid.ink service, reusing the
     * shared code-block holder. Only called when the user has opted into cloud rendering.
     *
     * There is no native Mermaid engine (it needs a JS/DOM runtime), so the only non-WebView
     * path is server-side rendering: the source is base64url-encoded and requested as a PNG.
     * Enabling this sends the diagram source to a third-party server and needs network; on any
     * failure (offline/error) it falls back to the raw source as code text (Safeguard 3).
     */
    fun bindMermaid(markwon: Markwon, holder: Holder, node: FencedCodeBlock) {
        val source = node.literal ?: ""
        // Show the diagram surface, hide the code text + copy button.
        holder.codeScroll.visibility = View.GONE
        holder.copyButton.visibility = View.GONE
        holder.mermaidImage.visibility = View.VISIBLE
        holder.mermaidCaption.visibility = View.GONE
        // The code views are hidden here, but a failed fetch shows them again (fallbackToSource).
        holder.codeScroll.wrapLines = wrapLines
        holder.mermaidImage.background = GradientDrawable().apply {
            cornerRadius = dp(6f).toFloat()
            setColor(colorScheme.codeBlockBg.toArgb())
            setStroke(dp(1f), colorScheme.codeBlockBorder.toArgb())
        }
        try {
            val encoded = Base64.encodeToString(
                source.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            val bgHex = String.format("%06X", colorScheme.primaryBackground.toArgb() and 0xFFFFFF)
            // Request the PNG pre-sized to the card content width so it arrives crisp and
            // fills the width (mermaid.ink's default render is low-res → tiny). `width` preserves
            // the diagram's aspect ratio, so the ImageView (match_parent + fitCenter) shows it
            // full-width and as tall as needed (scrolls), not capped/shrunk.
            val url = "https://mermaid.ink/img/$encoded?type=png&theme=dark&bgColor=$bgHex&width=${mermaidWidthPx()}"
            holder.mermaidImage.load(url) {
                crossfade(true)
                listener(onError = { _, result ->
                    Log.w("FencedCodeBlockEntry", "mermaid fetch failed: ${result.throwable.message}")
                    fallbackToSource(holder, node)
                })
            }
        } catch (e: Exception) {
            Log.e("FencedCodeBlockEntry", "mermaid render failed: ${e.message}", e)
            fallbackToSource(holder, node)
        }
    }

    /** Card content width in px (screen minus the card's horizontal margins + the image padding). */
    private fun mermaidWidthPx(): Int {
        val screen = context.resources.displayMetrics.widthPixels
        val chrome = (dp(CARD_MARGIN_DP) + dp(IMAGE_PADDING_DP)) * 2
        return (screen - chrome).coerceIn(MERMAID_MIN_W, MERMAID_MAX_W)
    }

    /** When the diagram can't be fetched, show the raw Mermaid source as a normal code block. */
    private fun fallbackToSource(holder: Holder, node: FencedCodeBlock) {
        holder.mermaidImage.visibility = View.GONE
        holder.codeScroll.visibility = View.VISIBLE
        holder.codeView.typeface = ResourcesCompat.getFont(context, fontSet.monoRegular)
        val scaledCodeSize = PilcrowTypography.CODE_BLOCK_FONT_SIZE_SP * fontScale
        holder.codeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledCodeSize)
        holder.codeView.setLineSpacing(0f, PreviewLineHeightMultiplier)
        holder.codeView.setTextColor(colorScheme.secondaryText.toArgb())
        holder.codeView.text = node.literal ?: ""
        holder.codeView.movementMethod = null // Keep horizontal pan reaching the HScrollView
        holder.codeScroll.background = GradientDrawable().apply {
            cornerRadius = dp(6f).toFloat()
            setColor(colorScheme.codeBlockBg.toArgb())
            setStroke(dp(1f), colorScheme.codeBlockBorder.toArgb())
        }
    }

    /** ViewHolder: code views (shared with yaml + mermaid), plus the mermaid image. */
    class Holder(itemView: View) : MarkwonAdapter.Holder(itemView) {
        val codeScroll: CodeScrollView = requireView(R.id.code_scroll)
        val codeView: TextView = requireView(R.id.code_text)
        val copyButton: TextView = requireView(R.id.code_copy)
        val mermaidImage: ImageView = requireView(R.id.mermaid_image)
        val mermaidCaption: TextView = requireView(R.id.mermaid_caption)

        // M-136: the bind whose colours may still land, and its job. Cleared on every bind of any route.
        internal var highlightKey: Any? = null
        internal var highlightJob: Job? = null

        fun cancelPendingHighlight() {
            highlightJob?.cancel()
            highlightJob = null
            highlightKey = null
        }

        /**
         * Call at the start of every bind that does not start a Mermaid request (a new `load` on
         * [mermaidImage] replaces the old request by itself). Cancels a Mermaid request still running
         * for the block this holder showed before: its error path writes that diagram's source into
         * [codeView], so a late failure would replace the new block's text (M-222).
         */
        fun cancelPendingMermaid() = mermaidImage.dispose()
    }

    private companion object {
        // Shown under a ```mermaid block when cloud rendering is off. Honest about the cloud.
        const val MERMAID_OFF_CAPTION =
            "Mermaid chart not rendered — enable Mermaid charts in Settings (renders via the mermaid.ink cloud)."

        // Bounds for the requested mermaid render width (px): never absurdly small, capped so a
        // huge tablet/landscape request stays sane for mermaid.ink.
        const val MERMAID_MIN_W = 320
        const val MERMAID_MAX_W = 1600
        const val CARD_MARGIN_DP = 20f // adapter_code_block FrameLayout horizontal margin
        const val IMAGE_PADDING_DP = 12f // mermaid_image horizontal padding
    }
}

// Markwon 4.6.2's CorePlugin.visitCodeBlock writes a no-break space and a newline before the code.
private const val MARKWON_CODE_PREFIX = 2

/**
 * M-136: add the runs' colours to [view]'s text in place, so no new layout is built and the search
 * highlight stays. The text is Markwon's render of [code] (a short prefix, then the code), so each run
 * is shifted by that prefix, and a run past the end of the text is dropped (Safeguard 3).
 */
private fun paintRuns(view: TextView, code: String, runs: List<CodeRun>, syntax: CodeSyntaxColors) {
    // Markwon sets the text as a Spannable, and SearchHighlighter keeps it one (measured); anything
    // else is left plain rather than set again.
    val target = view.text as? Spannable ?: return
    val offset = codeOffset(target, code) ?: return
    runs.forEach { run ->
        val color = run.role.colorIn(syntax)
        val start = offset + run.start
        val end = offset + run.end
        val inBounds = start in 0 until end && end <= target.length
        if (color != null && inBounds) {
            target.setSpan(ForegroundColorSpan(color.toArgb()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

/** Where [code] starts in [text], or null if the text does not hold it (e.g. the failure message). */
private fun codeOffset(text: CharSequence, code: String): Int? {
    val body = code.trimEnd('\n')
    val at = if (text.startsWith(body, MARKWON_CODE_PREFIX)) MARKWON_CODE_PREFIX else text.indexOf(body)
    return at.takeIf { it >= 0 && body.isNotEmpty() }
}
