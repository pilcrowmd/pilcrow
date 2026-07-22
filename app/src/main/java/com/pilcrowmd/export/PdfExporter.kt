// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.graphics.pdf.PdfDocument
import com.pilcrowmd.rendering.MarkwonRenderer
import io.noties.markwon.Markwon
import kotlin.runCatching

/**
 * Off-screen PDF renderer. Builds a full-content layout (LinearLayout of all block views),
 * measures it at page-content width (derived from device density), and provides page-by-page canvas draw capability.
 * No pagination logic here — just layout + measurement with concrete A4 geometry and px↔pt mapping.
 *
 * **A4 page geometry:**
 * - Page size: 595×842 pt (1 pt = 1/72 inch)
 * - Margins: 45pt each (~16mm) on all sides
 * - Content area: 505×752 pt (reserve ~40pt bottom for footer → ~712pt usable height)
 *
 * **Pixel↔Point conversion:**
 * - Off-screen View is measured in PIXELS on device screen
 * - PdfDocument page is in POINTS (PDF coordinate system)
 * - Conversion: px = pt × densityDpi / 72
 * - Example: @160dpi (mdpi), 505pt ≈ 1122px
 * - Draw scaling: canvas.scale((72f / densityDpi) × PRINT_SCALE) maps px-measured layout onto pt page
 * - PRINT_SCALE: content is measured narrower (÷PRINT_SCALE) and drawn larger (×PRINT_SCALE)
 *   so body type prints at ~11pt; page geometry and the on-screen reader are unchanged.
 */
class PdfExporter(private val context: Context, private val markwonRenderer: MarkwonRenderer) {

    // Builds the off-screen block-view tree (SRP split: layout-building vs pagination/PDF write).
    private val layoutBuilder = PdfContentLayoutBuilder(context)

    companion object {
        // A4 page dimensions (in points; 1/72 inch per point)
        const val A4_WIDTH_PT = 595
        const val A4_HEIGHT_PT = 842

        // Margins (in points; ~16mm ≈ 45pt each)
        const val MARGIN_PT = 45

        // Usable content area (reserve ~40pt bottom for footer)
        const val PAGE_CONTENT_WIDTH_PT = 505 // 595 - 45*2
        const val PAGE_CONTENT_HEIGHT_PT = 712 // 842 - 45*2 - 40 (footer)

        // Points-to-pixels conversion factor (1 point = 1/72 inch)
        const val POINTS_PER_INCH = 72

        // Footer page-number rendering (points)
        const val PAGE_NUMBER_TEXT_SIZE_PT = 12f
        const val PAGE_NUMBER_BASELINE_OFFSET_PT = 15 // below the content box, within the bottom margin

        // PDF body-type scale. The reader's 17sp body collapses to ~7.65pt when mapped to
        // print (17 × 72/160), which reads small for a printed A4 page. Scale the whole layout up so
        // body text lands at ~11pt (a natural document size) by measuring NARROWER and drawing LARGER
        // — page geometry (A4, margins) is unchanged and the on-screen reader is untouched.
        // 1.44 × 7.65pt ≈ 11pt. Applied at the 3 coordinated px↔pt sites: measure width, draw scale,
        // page content height (all stay consistent, so block-aware pagination is unaffected).
        const val PRINT_SCALE = 1.44f
    }

    /**
     * Compute device pixel width from PDF points, accounting for screen density.
     *
     * **Formula:** px = pt × densityDpi / 72
     *
     * @param pt Point value (PDF coordinate)
     * @return Pixel value at current device density
     *
     * **Example:** @160dpi (mdpi):
     * - 505pt (content width) ≈ 505 × 160 / 72 ≈ 1122px
     */
    fun ptToPx(pt: Float): Float {
        val densityDpi = context.resources.displayMetrics.densityDpi
        return (pt * densityDpi) / POINTS_PER_INCH
    }

    /**
     * **Streaming Pass 1:** Measure block bounds WITHOUT holding all views in memory.
     * Returns a list of (top, bottom) Y-bounds for each top-level block in pixels.
     * This is memory-bounded O(1) and used before paginateAndRenderStreaming().
     *
     * @param content Markdown source text
     * @param fontScale Font scale multiplier
     * @return List of (top, bottom) Y-bounds in pixels for each top-level block
     */
    fun measureBlockBounds(content: String, fontScale: Float = 1.0f): List<Pair<Int, Int>> = runCatching {
        val pageContentWidthPx = (ptToPx(PAGE_CONTENT_WIDTH_PT.toFloat()) / PRINT_SCALE).toInt()
        val nodes = parseTopLevelBlocks(markwonRenderer.markwon, content)
        layoutBuilder.measureBlockBounds(markwonRenderer.markwon, nodes, pageContentWidthPx, fontScale)
    }.getOrThrow()

    /**
     * Helper: get the Markwon instance from the renderer singleton.
     * (The renderer is usually accessed via AppContainer; this is a convenience.)
     */
    internal fun getMarkwon(): Markwon = markwonRenderer.markwon

    /**
     * Test visibility: get the peak number of concurrently-live block views from the builder.
     * Used to verify streaming bounds memory to O(types).
     */
    internal fun getPeakLiveBlockViews(): Int = layoutBuilder.getPeakLiveBlockViews()

    /**
     * **Streaming Pass 2:** Paginate block bounds and render the PDF in a streaming fashion,
     * holding only O(1) block views at a time. This is the memory-efficient export path for large docs.
     *
     * @param content Markdown source text
     * @param fontScale Font scale multiplier
     * @return PdfDocument with all pages rendered
     */
    fun paginateAndRenderStreaming(content: String, fontScale: Float = 1.0f): PdfDocument = runCatching {
        val pdfDocument = PdfDocument()
        layoutBuilder.resetPeakLiveBlockViews() // measure the peak across this whole export
        val pageContentWidthPx = (ptToPx(PAGE_CONTENT_WIDTH_PT.toFloat()) / PRINT_SCALE).toInt()
        val contentHeightPx = (ptToPx(PAGE_CONTENT_HEIGHT_PT.toFloat()) / PRINT_SCALE).toInt()

        // Parse the document ONCE and reuse the block list across Pass 1 (measure) and Pass 2
        // (per-page render), so a P-page export parses once — not P+1 times. The same node objects
        // are bound multiple times (a block spanning two pages renders on both); binding is a
        // read-only render, so it is idempotent and heights stay deterministic across passes.
        val nodes = parseTopLevelBlocks(markwonRenderer.markwon, content)

        // Pass 1: Compute block bounds (streaming, O(1) live views)
        val blockBounds =
            layoutBuilder.measureBlockBounds(markwonRenderer.markwon, nodes, pageContentWidthPx, fontScale)
        if (blockBounds.isEmpty()) {
            // Empty document: still produce a blank page
            val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val whitePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.FILL
            }
            page.canvas.drawRect(0f, 0f, A4_WIDTH_PT.toFloat(), A4_HEIGHT_PT.toFloat(), whitePaint)
            // Footer page number
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = PAGE_NUMBER_TEXT_SIZE_PT
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val x = A4_WIDTH_PT / 2f
            val y = A4_HEIGHT_PT.toFloat() - MARGIN_PT + PAGE_NUMBER_BASELINE_OFFSET_PT
            page.canvas.drawText("1", x, y, paint)
            pdfDocument.finishPage(page)
            return@runCatching pdfDocument
        }

        val pageStarts = computePageStarts(blockBounds, contentHeightPx)
        val totalHeightPx = blockBounds.last().second
        val pageSpans = computePageSpans(pageStarts, totalHeightPx, contentHeightPx)
        val marginPt = MARGIN_PT.toFloat()
        val densityDpi = context.resources.displayMetrics.densityDpi
        val scaleFactor = (POINTS_PER_INCH.toFloat() / densityDpi) * PRINT_SCALE

        // Pass 2: render each page, inflating ONLY the blocks that fall on it (streaming, O(1) views).
        fun renderStreamingPage(pageNumber: Int, pageStartY: Int, pageClipHeightPx: Int) {
            val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, pageNumber).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            // White page background
            canvas.drawRect(
                0f,
                0f,
                A4_WIDTH_PT.toFloat(),
                A4_HEIGHT_PT.toFloat(),
                android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    style = android.graphics.Paint.Style.FILL
                },
            )

            canvas.save()
            canvas.translate(marginPt, marginPt)
            canvas.scale(scaleFactor, scaleFactor)
            canvas.clipRect(0, 0, pageContentWidthPx, pageClipHeightPx)
            canvas.translate(0f, -pageStartY.toFloat())

            // Only the blocks intersecting this page are inflated + drawn (bounds known from Pass 1).
            val pageEndY = pageStartY + pageClipHeightPx
            val visibleIndices = blockBounds.indices.filter { i ->
                val (top, bottom) = blockBounds[i]
                bottom > pageStartY && top < pageEndY
            }.toSet()
            layoutBuilder.forEachBlock(
                PdfContentLayoutBuilder.ParseSpec(markwonRenderer.markwon, nodes, pageContentWidthPx, fontScale),
                visibleIndices,
            ) { index, _, blockView ->
                canvas.save()
                canvas.translate(0f, blockBounds[index].first.toFloat())
                blockView.draw(canvas)
                canvas.restore()
            }

            canvas.restore()

            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = PAGE_NUMBER_TEXT_SIZE_PT
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val x = A4_WIDTH_PT / 2f
            val y = A4_HEIGHT_PT.toFloat() - MARGIN_PT + PAGE_NUMBER_BASELINE_OFFSET_PT
            canvas.drawText(pageNumber.toString(), x, y, paint)
            pdfDocument.finishPage(page)
        }

        pageSpans.forEachIndexed { pageIndex, (pageStartY, pageClipHeightPx) ->
            renderStreamingPage(pageIndex + 1, pageStartY, pageClipHeightPx)
        }

        pdfDocument
    }.getOrThrow()

    /**
     * Block-aware page-start offsets (PURE + unit-testable — no View/Android deps). Given each block's
     * (top, bottom) Y-bounds in the measured layout and the usable content height per page (px), returns
     * the start-Y of each page. A page breaks BEFORE any block that would overflow it (so blocks are
     * never cut mid-block); a single block taller than contentHeightPx is sliced across pages (the only
     * unavoidable split). Always returns at least [0].
     */
    internal fun computePageStarts(blockBounds: List<Pair<Int, Int>>, contentHeightPx: Int): List<Int> {
        val pageStarts = mutableListOf(0)
        var pageStartY = 0
        for ((top, bottom) in blockBounds) {
            if (bottom - pageStartY > contentHeightPx && top > pageStartY) {
                pageStartY = top
                pageStarts.add(pageStartY)
            }
            while (bottom - pageStartY > contentHeightPx) {
                pageStartY += contentHeightPx
                pageStarts.add(pageStartY)
            }
        }
        return pageStarts
    }

    /**
     * Per-page (startY, clipHeightPx) spans (PURE + unit-testable). Each clip height is the gap to the
     * next page start, capped at one content height; the last page runs to [totalHeightPx]. Page starts
     * strictly increase, so every clip height is > 0 for a non-empty document — guarding against the
     * blank-page failure mode of clipping a page to zero height.
     */
    internal fun computePageSpans(
        pageStarts: List<Int>,
        totalHeightPx: Int,
        contentHeightPx: Int,
    ): List<Pair<Int, Int>> = pageStarts.mapIndexed { i, startY ->
        val nextStartY = pageStarts.getOrNull(i + 1) ?: totalHeightPx
        startY to minOf(contentHeightPx, nextStartY - startY)
    }

    /**
     * Write the PdfDocument to a SAF Uri atomically (no partial/corrupt files).
     * For CREATE_DOCUMENT (new empty file), opens the Uri OutputStream, writes the full PDF, flushes, closes.
     * On ANY failure (write, flush, or close), calls DocumentsContract.deleteDocument() to remove the partial file,
     * then rethrows the exception. This ensures no corrupt/partial files are left behind.
     *
     * @param pdfDocument The PdfDocument to write
     * @param uri The SAF Uri (CREATE_DOCUMENT result — pointing to a new empty file)
     * @param contentResolver ContentResolver for writing and deletion
     * @throws Exception if write fails (partial file is deleted before throwing)
     */
    fun writePdfToUri(
        pdfDocument: PdfDocument,
        uri: android.net.Uri,
        contentResolver: android.content.ContentResolver,
    ) = runCatching {
        // For CREATE_DOCUMENT, uri points to a new empty file (nothing to backup)
        contentResolver.openOutputStream(uri)?.use { outputStream ->
            pdfDocument.writeTo(outputStream)
            outputStream.flush()
            // Successfully written and flushed
        } ?: error("Could not open output stream for URI: $uri")
    }.onFailure { exception ->
        // On ANY failure, delete the partial file to prevent corrupt PDFs.
        // The cleanup is best-effort: a delete failure must not mask the original write error.
        runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
            .onFailure { deleteError ->
                android.util.Log.e("PdfExporter", "Failed to delete partial file after write error", deleteError)
            }
        // Rethrow the original exception
        throw exception
    }.getOrThrow()

    /**
     * One-shot export of [content] to [uri]: measure → paginate → write atomically, using this
     * exporter's own (application) ContentResolver. Lets the caller (ViewModel) trigger an export
     * without holding a Context/ContentResolver. The PdfDocument is always closed (even on failure).
     *
     * Uses the streaming path (paginateAndRenderStreaming) to minimize memory usage on large docs.
     */
    fun exportToUri(content: String, fontScale: Float, uri: android.net.Uri) {
        layoutBuilder.resetPeakLiveBlockViews()
        val pdfDocument = paginateAndRenderStreaming(content, fontScale)
        try {
            writePdfToUri(pdfDocument, uri, context.contentResolver)
        } finally {
            pdfDocument.close()
        }
    }
}
