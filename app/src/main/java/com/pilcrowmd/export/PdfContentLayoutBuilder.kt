// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.pilcrowmd.R
import com.pilcrowmd.rendering.SearchHighlight
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.PrintColorScheme
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * Parse [content] ONCE and return its top-level Markdown blocks as standalone (unlinked) nodes.
 * Unlinking detaches each block from its siblings/parent so rendering a node touches only its own
 * subtree (mirrors the per-block isolation the previous per-page re-parse produced). Parsing once
 * and reusing this list across both streaming passes avoids re-parsing the whole document on every
 * page draw. A top-level function (not a class member) so it adds no measurement/inflation surface
 * to the builder; binding a returned node is a read-only render, hence reusing the list is idempotent.
 */
internal fun parseTopLevelBlocks(markwon: Markwon, content: String): List<org.commonmark.node.Node> {
    val document = markwon.parse(content)
    val nodes = buildList {
        var node = document.firstChild
        while (node != null) {
            add(node)
            node = node.next
        }
    }
    nodes.forEach { it.unlink() }
    return nodes
}

/**
 * Builds the off-screen content layout for PDF export: a vertical [LinearLayout] holding one view per
 * top-level Markdown block, rendered with [PrintColorScheme] and measured at the page-content width.
 *
 * The RecyclerView-free path: parse via Markwon's plugin-aware parser, then for each top-level node
 * instantiate the matching block entry (Prose/Table/FencedCode/Frontmatter) and create+bind its holder
 * directly (MarkwonAdapter holders don't require a RecyclerView parent here). Mirrors the live preview's
 * node→entry routing (notably YAML frontmatter → FrontmatterBlockEntry).
 *
 * **Streaming & memory bound:** an earlier approach measured the entire content tree at once, holding
 * all block views alive for pagination + draw → OOM on large docs. The streaming path (forEachBlock,
 * inflateMeasuredBlock) holds O(1) block views at a time via a type-pooled holder cache and processes
 * the content in two passes: Pass 1 (measureBlockBounds) computes block bounds, Pass 2 (forEachBlock
 * during draw) re-binds only blocks visible on that page. This bounds peak memory to a small constant
 * regardless of document size.
 */
internal class PdfContentLayoutBuilder(private val context: Context) {

    /**
     * Pool of reusable holders keyed by block type. Reusing holders (rebind per node) instead of
     * re-inflating cuts GC churn and keeps peak memory O(types), typically 3-4 types.
     */
    private val holderPool = mutableMapOf<String, io.noties.markwon.recycler.MarkwonAdapter.Holder>()

    /**
     * Test visibility: track the peak number of concurrently-live block views.
     * Incremented when a block view is created/in-use, decremented on release.
     * Used to verify the streaming architecture bounds memory to O(types).
     */
    private var currentLiveBlockViews = 0
    private var peakLiveBlockViews = 0

    /** Expose the peak for testing. */
    fun getPeakLiveBlockViews(): Int = peakLiveBlockViews

    /** Reset peak counter for a new export. */
    fun resetPeakLiveBlockViews() {
        peakLiveBlockViews = 0
        currentLiveBlockViews = 0
    }

    /**
     * Render every LaTeX async drawable SYNCHRONOUSLY for the off-screen export (covers both block
     * `$$…$$` and inline `$$…$$` math — the inline span type extends [JLatexAsyncDrawableSpan]).
     *
     * On screen, JLatexMathPlugin builds each formula's bitmap on a background executor, then
     * `invalidate()`s the (attached) TextView when it is ready. The export is fully synchronous and
     * its TextViews are DETACHED (no window), so that callback never arrives — [AsyncDrawableSpan]
     * then draws its replacement text, i.e. the raw `\frac{…}` source. We resolve each span here on
     * the calling thread: `JLatexMathDrawable.builder(latex).build()` renders synchronously (it is
     * exactly what the plugin's async loader calls), and feeding the result back via
     * `AsyncDrawable.setResult(...)` flips `hasResult()` true so the existing span draws real math
     * and reports its true size. Per-formula `runCatching` keeps a malformed formula from aborting
     * the export — it simply retains its raw-text fallback (Safeguard 3).
     *
     * The textSize/color mirror the on-screen render: the static body size in px (the JLatexMath
     * plugin's size is not font-scaled) and [PrintColorScheme] near-black text (Safeguard 4).
     */
    private fun resolveLatexSynchronously(view: View) {
        when (view) {
            is ViewGroup ->
                for (i in 0 until view.childCount) resolveLatexSynchronously(view.getChildAt(i))
            is TextView -> {
                val spanned = view.text as? Spanned ?: return
                val spans = spanned.getSpans(0, spanned.length, JLatexAsyncDrawableSpan::class.java)
                if (spans.isEmpty()) return
                val density = context.resources.displayMetrics.density
                val textSizePx = PilcrowTypography.PROSE_BODY_FONT_SIZE_SP * density
                val textColor = PrintColorScheme.primaryText.toArgb()
                for (span in spans) {
                    val asyncDrawable = span.drawable
                    if (asyncDrawable.hasResult()) continue
                    runCatching {
                        val math = JLatexMathDrawable.builder(asyncDrawable.destination)
                            .textSize(textSizePx)
                            .color(textColor)
                            .build()
                        math.setBounds(0, 0, math.intrinsicWidth, math.intrinsicHeight)
                        asyncDrawable.setResult(math)
                    }.onFailure { e ->
                        android.util.Log.w("PdfExporter", "Synchronous LaTeX render failed: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Per-view print fix-ups, applied to the bound tree once before measure (EXPORT ONLY):
     *  - Hide the interactive "Copy" button so it is never painted into the PDF (the
     *    full-width dark code surface comes from the container background, so no width fix-up needed).
     *  - Bound every code `TextView` to the printable text width via `maxWidth` so long
     *    lines WRAP instead of clipping at the right margin. On screen `code_text` is
     *    `wrap_content` inside a HorizontalScrollView (panning); print has no scrolling, so we cap the
     *    width and the TextView wraps. Syntax highlight is span-based on the characters, so it is
     *    preserved across the wrap for free. `maxWidth` = content width minus the code block's 20dp
     *    horizontal margins (the inner scroll-view width); the TextView's own 12dp padding then insets
     *    the text inside the dark surface, mirroring the left padding.
     */
    private fun applyPrintFixups(rootView: View, pageContentWidthPx: Int) {
        val marginPx = CODE_BLOCK_HORIZONTAL_MARGIN_DP * context.resources.displayMetrics.density
        val maxTextWidthPx = (pageContentWidthPx - 2 * marginPx).toInt()

        // Depth-first walk applying fixups to every view
        fun walkAndFix(view: View) {
            when {
                view.id == R.id.code_copy -> view.visibility = View.GONE
                view.id == R.id.code_text && view is TextView && maxTextWidthPx > 0 ->
                    view.maxWidth = maxTextWidthPx
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walkAndFix(view.getChildAt(i))
            }
        }
        walkAndFix(rootView)
    }

    /** Create a holder (view container) for the given node type. */
    private fun createHolderForNode(
        node: org.commonmark.node.Node,
        inflater: LayoutInflater,
        parent: ViewGroup,
        fontScale: Float,
    ): io.noties.markwon.recycler.MarkwonAdapter.Holder {
        val entry = when (node) {
            is org.commonmark.ext.gfm.tables.TableBlock -> com.pilcrowmd.rendering.TableBlockEntry(
                context,
                fontScale,
                FontSets.DEFAULT,
                PrintColorScheme,
            )
            is org.commonmark.node.FencedCodeBlock -> {
                // YAML frontmatter → styled FrontmatterBlockEntry; otherwise normal code block.
                // Both extend MarkwonAdapter.Entry<FencedCodeBlock, Holder>, mirroring the live
                // preview's ConditionalFencedCodeBlockEntry. Mermaid stays a code block (no sync cloud).
                if (node.info?.trim()?.lowercase() == "yaml") {
                    com.pilcrowmd.rendering.FrontmatterBlockEntry(
                        context,
                        fontScale,
                        FontSets.DEFAULT,
                        PrintColorScheme,
                    )
                } else {
                    com.pilcrowmd.rendering.FencedCodeBlockEntry(
                        context,
                        fontScale,
                        FontSets.DEFAULT,
                        PrintColorScheme,
                    )
                }
            }
            else -> {
                // Default prose entry for headings, paragraphs, lists, blockquotes, etc.
                com.pilcrowmd.rendering.ProseBlockEntry(
                    context,
                    fontScale,
                    FontSets.DEFAULT,
                    SearchHighlight(),
                    PrintColorScheme,
                )
            }
        }
        return entry.createHolder(inflater, parent)
    }

    /** Bind a node to its corresponding holder. */
    @Suppress("UNCHECKED_CAST")
    private fun bindHolderForNode(
        node: org.commonmark.node.Node,
        markwon: Markwon,
        holder: io.noties.markwon.recycler.MarkwonAdapter.Holder,
        fontScale: Float,
    ) {
        when (node) {
            is org.commonmark.ext.gfm.tables.TableBlock -> {
                val entry = com.pilcrowmd.rendering.TableBlockEntry(
                    context,
                    fontScale,
                    FontSets.DEFAULT,
                    PrintColorScheme,
                )
                entry.bindHolder(
                    markwon,
                    holder as com.pilcrowmd.rendering.TableBlockEntry.Holder,
                    node,
                )
            }
            is org.commonmark.node.FencedCodeBlock -> {
                // Same logic as createHolderForNode: YAML frontmatter vs. normal code block
                val entry = if (node.info?.trim()?.lowercase() == "yaml") {
                    com.pilcrowmd.rendering.FrontmatterBlockEntry(
                        context,
                        fontScale,
                        FontSets.DEFAULT,
                        PrintColorScheme,
                    )
                } else {
                    com.pilcrowmd.rendering.FencedCodeBlockEntry(
                        context,
                        fontScale,
                        FontSets.DEFAULT,
                        PrintColorScheme,
                    )
                }
                entry.bindHolder(
                    markwon,
                    holder as com.pilcrowmd.rendering.FencedCodeBlockEntry.Holder,
                    node,
                )
            }
            else -> {
                val entry = com.pilcrowmd.rendering.ProseBlockEntry(
                    context,
                    fontScale,
                    FontSets.DEFAULT,
                    SearchHighlight(),
                    PrintColorScheme,
                )
                entry.bindHolder(
                    markwon,
                    holder as com.pilcrowmd.rendering.ProseBlockEntry.Holder,
                    node,
                )
            }
        }
    }

    /**
     * Streaming Pass 1: Measure block bounds WITHOUT holding all views in memory.
     * Iterates each pre-parsed top-level block, inflates/measures/releases it, and returns the
     * (top, bottom) Y-bounds for each block relative to a root LinearLayout at y=0.
     *
     * @param markwon The Markwon renderer
     * @param nodes Pre-parsed top-level blocks (see [parseTopLevelBlocks])
     * @param pageContentWidthPx Page content width (exactly)
     * @param fontScale Font scale multiplier
     * @return List of (top, bottom) Y-bounds in pixels for each top-level block
     */
    fun measureBlockBounds(
        markwon: Markwon,
        nodes: List<org.commonmark.node.Node>,
        pageContentWidthPx: Int,
        fontScale: Float,
    ): List<Pair<Int, Int>> {
        val bounds = mutableListOf<Pair<Int, Int>>()
        var currentY = 0

        // Replicate vertical LinearLayout stacking: each child's top/bottom includes its layout
        // margins (LinearLayout does not collapse margins). Omitting them would lose the inter-block
        // spacing the on-screen path has and shift every block, breaking print fidelity + pagination.
        forEachBlock(ParseSpec(markwon, nodes, pageContentWidthPx, fontScale)) { _, _, blockView ->
            val lp = blockView.layoutParams as? ViewGroup.MarginLayoutParams
            val topMargin = lp?.topMargin ?: 0
            val bottomMargin = lp?.bottomMargin ?: 0
            val top = currentY + topMargin
            val bottom = top + blockView.measuredHeight
            bounds.add(top to bottom)
            currentY = bottom + bottomMargin
        }

        return bounds
    }

    /**
     * Streaming core: Iterate the pre-parsed top-level [ParseSpec.nodes]: inflate via pooled
     * holder, bind, apply print fixups, resolve LaTeX, measure, invoke [action] with the measured
     * view, and release (pool the holder for reuse). One view is live at a time; the pool keeps peak
     * memory O(types). Nodes are parsed+unlinked once up front ([parseTopLevelBlocks]) and reused
     * across passes, so this never re-parses — binding a node is idempotent (read-only render).
     *
     * @param spec Pre-parsed blocks + render inputs
     * @param inflateOnly If non-null, only these block indices are inflated/measured (the draw pass
     *   passes the blocks on the current page → ~one inflation per block, not per page)
     * @param action Callback invoked for each measured block: (index, node, view) -> Unit
     */

    /**
     * Stable inputs for one streaming render pass over pre-parsed blocks (keeps call sites small).
     */
    class ParseSpec(
        val markwon: Markwon,
        val nodes: List<org.commonmark.node.Node>,
        val pageContentWidthPx: Int,
        val fontScale: Float,
    )

    fun forEachBlock(
        spec: ParseSpec,
        inflateOnly: Set<Int>? = null,
        action: (index: Int, node: org.commonmark.node.Node, view: View) -> Unit,
    ) {
        val inflater = LayoutInflater.from(context)
        for ((index, node) in spec.nodes.withIndex()) {
            if (inflateOnly != null && index !in inflateOnly) continue
            val blockView =
                inflateMeasuredBlock(spec.markwon, node, inflater, spec.pageContentWidthPx, spec.fontScale)
            action(index, node, blockView)
            releaseBlockView(node)
        }
    }

    /**
     * Streaming primitive: Create a holder for a single node (reusing the pool), bind it,
     * apply print fixups and resolve LaTeX, measure, and return the measured view ready to draw or inspect.
     *
     * @param markwon The Markwon renderer
     * @param node The Markdown node to render
     * @param inflater LayoutInflater for creating views
     * @param pageContentWidthPx Page content width (exactly)
     * @param fontScale Font scale multiplier
     * @return A single measured block view
     */
    fun inflateMeasuredBlock(
        markwon: Markwon,
        node: org.commonmark.node.Node,
        inflater: LayoutInflater,
        pageContentWidthPx: Int,
        fontScale: Float,
    ): View {
        // Increment live-view counter for testing
        currentLiveBlockViews++
        peakLiveBlockViews = maxOf(peakLiveBlockViews, currentLiveBlockViews)

        // Create or reuse a holder for this block type
        val typeKey = node::class.simpleName ?: "unknown"
        val holder = if (holderPool.containsKey(typeKey)) {
            holderPool[typeKey]!!
        } else {
            // Dummy parent for holder creation (transient, not actually attached)
            val parent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            val newHolder = createHolderForNode(node, inflater, parent, fontScale)
            holderPool[typeKey] = newHolder
            newHolder
        }

        // Bind the node to the holder
        runCatching { bindHolderForNode(node, markwon, holder, fontScale) }
            .onFailure { e -> android.util.Log.w("PdfExporter", "Failed to bind block: ${e.message}") }

        // Apply print fixups and resolve LaTeX
        applyPrintFixups(holder.itemView, pageContentWidthPx)
        resolveLatexSynchronously(holder.itemView)

        // Measure the holder's view
        val widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(
            pageContentWidthPx,
            View.MeasureSpec.EXACTLY,
        )
        val heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        holder.itemView.measure(widthMeasureSpec, heightMeasureSpec)
        holder.itemView.layout(0, 0, pageContentWidthPx, holder.itemView.measuredHeight)

        return holder.itemView
    }

    /**
     * Release a block view back to its holder pool (detach from any parent, making it ready for reuse).
     * This is a soft reset; the next forEachBlock/inflateMeasuredBlock will rebind the holder to a new node.
     * Decrements the live-view counter for testing.
     */
    private fun releaseBlockView(node: org.commonmark.node.Node) {
        currentLiveBlockViews--
        val typeKey = node::class.simpleName ?: "unknown"
        holderPool[typeKey]?.let { holder ->
            (holder.itemView.parent as? ViewGroup)?.removeView(holder.itemView)
        }
    }

    companion object {
        // Horizontal margin of the code block layout (adapter_code_block.xml), used to bound the
        // printable code-text width for line wrapping.
        private const val CODE_BLOCK_HORIZONTAL_MARGIN_DP = 20f
    }
}
