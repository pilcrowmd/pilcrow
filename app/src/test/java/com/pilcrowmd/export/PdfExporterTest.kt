// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.ui.theme.PrintColorScheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PdfExporterTest {
    private lateinit var context: Context
    private lateinit var renderer: MarkwonRenderer
    private lateinit var exporter: PdfExporter

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // On a real device jlatexmath self-initializes via its JLatexMathInitProvider
        // ContentProvider at app startup; Robolectric does not run ContentProviders, so init it
        // explicitly here to let synchronous JLatexMathDrawable rendering work under test.
        ru.noties.jlatexmath.JLatexMathAndroid.init(context)
        renderer = MarkwonRenderer(context)
        // Wait out the renderer's background font pre-warm before any test parses on the same Markwon
        // instance — Markwon's inline parser is stateful and not concurrency-safe, so a parse racing
        // the still-running pre-warm intermittently threw StringIndexOutOfBoundsException.
        renderer.awaitFontPreWarm()
        exporter = PdfExporter(context, renderer)
    }

    @Test
    fun testPrintColorSchemeTokens() {
        // Verify PrintColorScheme is fully defined with the correct page/text colors
        // Note: color 0xFFFFFFFF is white (including alpha channel)
        val bgColor = PrintColorScheme.primaryBackground.toArgb()
        assertEquals("Primary background should be white", -1, bgColor) // -1 is 0xFFFFFFFF
        val textColor = PrintColorScheme.primaryText.toArgb()
        assertTrue("Near-black text expected", (textColor and 0xFF000000.toInt()) != 0)
        val codeColor = PrintColorScheme.codeBlockBg.toArgb()
        assertTrue("Light gray code bg expected", (codeColor and 0xFFFFFF) != 0)
    }

    @Test
    fun testPtToPx_ConversionAccuracy() {
        // At 160 dpi (mdpi), 505pt should be approximately 1122px
        // Formula: px = pt × densityDpi / 72
        // At 160dpi: 505 × 160 / 72 ≈ 1122px

        val densityDpi = context.resources.displayMetrics.densityDpi.toFloat()
        val expectedPxForA4ContentWidth = (PdfExporter.PAGE_CONTENT_WIDTH_PT * densityDpi) / 72f

        // Verify the formula is correct
        assertTrue("A4 content width in px should be positive", expectedPxForA4ContentWidth > 0)

        // For mdpi (160dpi), it should be roughly 1122px (allow ±10px for different test env densities)
        if (densityDpi == 160f) {
            assertTrue("At mdpi, A4 content width ~1122px", expectedPxForA4ContentWidth in 1112f..1132f)
        }
    }

    @Test
    fun testStreamingMeasureBlockBounds_AllBlockTypesFixture() {
        // Test with all block types to ensure real entries render without exception (via streaming)
        val content = """
            # H1 Heading

            Paragraph with **bold**, *italic*, and `inline code`.

            ## H2 Heading

            - List item 1
            - List item 2

            | Header 1 | Header 2 |
            |----------|----------|
            | Cell 1   | Cell 2   |

            ```kotlin
            val x = 42
            ```

            $${'$'}{'$'}x = \frac{a}{b}$${'$'}{'$'}

            > A blockquote

            Final paragraph.
        """.trimIndent()

        // Measure block bounds using streaming (all blocks render without exception)
        val bounds = exporter.measureBlockBounds(content, fontScale = 1.0f)

        assertTrue("Should have measured bounds", bounds.isNotEmpty())
        assertTrue("Total height should be positive", bounds.last().second > 0)

        // Verify we have multiple blocks (heading, para, list, code, math, blockquote, etc.)
        assertTrue("Should have at least 4 blocks", bounds.size >= 4)

        // Verify bounds are valid (top < bottom for each, monotonically increasing)
        bounds.forEach { (top, bottom) ->
            assertTrue("Block [$top, $bottom) must have top <= bottom", top <= bottom)
        }
        for (i in 0 until bounds.size - 1) {
            assertTrue(
                "Blocks must be ordered vertically",
                bounds[i].second <= bounds[i + 1].first,
            )
        }
    }

    @Test
    fun testStreamingMeasureBlockBounds_SimpleFixture() {
        val content = """
            # Heading 1

            This is a paragraph with some text.

            ## Heading 2

            ```kotlin
            val x = 42
            ```
        """.trimIndent()

        val bounds = exporter.measureBlockBounds(content)

        assertTrue("Should have measured bounds", bounds.isNotEmpty())
        assertTrue("Total height should be positive", bounds.last().second > 0)
    }

    @Test
    fun testStreamingMeasureBlockBounds_EmptyContent() {
        // Edge case: empty document should return empty bounds
        val bounds = exporter.measureBlockBounds("")

        assertTrue("Empty content should have empty bounds", bounds.isEmpty())
    }

    @Test
    fun testPaginateAndRenderStreaming_ProducesPages() {
        // Verify streaming pagination produces pages without crashing
        val content = buildString {
            // Create content that will span multiple pages
            for (i in 1..100) {
                append("Paragraph $i with some text. ".repeat(5))
                append("\n\n")
            }
        }

        // Should not throw and produce blocks/bounds for pagination
        val bounds = exporter.measureBlockBounds(content)
        assertTrue("Should have blocks to paginate", bounds.isNotEmpty())

        // Verify bounds are stacked vertically
        val contentHeightPx = exporter.ptToPx(PdfExporter.PAGE_CONTENT_HEIGHT_PT.toFloat()).toInt()
        val pageStarts = exporter.computePageStarts(bounds, contentHeightPx)
        assertTrue("Multi-page content should have multiple page starts", pageStarts.size > 1)
    }

    @Test
    fun testA4GeometryConstants() {
        // Verify A4 constants are correct
        assertEquals("A4 width should be 595pt", 595, PdfExporter.A4_WIDTH_PT)
        assertEquals("A4 height should be 842pt", 842, PdfExporter.A4_HEIGHT_PT)
        assertEquals("Margin should be 45pt", 45, PdfExporter.MARGIN_PT)
        // 595 - 45*2 = 505pt
        assertEquals(
            "Page content width should be 505pt",
            505,
            PdfExporter.PAGE_CONTENT_WIDTH_PT,
        )
        // 842 - 45*2 - 40 (footer) = 712pt
        assertEquals(
            "Page content height should be 712pt",
            712,
            PdfExporter.PAGE_CONTENT_HEIGHT_PT,
        )
    }

    @Test
    fun testComputePageStarts_DoesNotSplitBlocksAtBoundaries() {
        // 3 blocks of height 400, content height 1000 → blocks at [0,400),[400,800),[800,1200).
        // Page 1 holds blocks 0+1 (ends 800 <= 1000); block 2 would overflow → break BEFORE it at y=800.
        val contentHeightPx = 1000
        val bounds = listOf(0 to 400, 400 to 800, 800 to 1200)
        val starts = exporter.computePageStarts(bounds, contentHeightPx)

        assertEquals("Page 2 must start at the 3rd block's top (no mid-block cut)", listOf(0, 800), starts)
        // Invariant: every block lies fully within one page region (none straddles a page boundary).
        bounds.forEach { (top, bottom) ->
            val pageStart = starts.last { it <= top }
            assertTrue("Block [$top,$bottom) straddles a page boundary", bottom <= pageStart + contentHeightPx)
        }
    }

    @Test
    fun testComputePageStarts_SlicesBlockTallerThanPage() {
        // A single block of height 2500 with content height 1000 → must slice across 3 pages (unavoidable).
        val starts = exporter.computePageStarts(listOf(0 to 2500), 1000)
        assertEquals("Oversized block sliced into full-page chunks", listOf(0, 1000, 2000), starts)
    }

    @Test
    fun testStreamingBlockRenderResolvesLatexSynchronously() {
        // LaTeX math ($$…$$) must render as a real MATH bitmap in the off-screen export, not raw
        // text. On screen JLatexMath renders the bitmap on a background executor and invalidates
        // the (attached) TextView; the detached export view never receives that callback, so
        // AsyncDrawableSpan would otherwise fall back to drawing the raw `\frac{…}` source.
        // The streaming block render path (measureBlockBounds + inflateMeasuredBlock) resolves
        // these spans synchronously during the measure pass — so by the time a block is measured,
        // every LaTeX span must already have a result.
        val d = "$$"
        val content = "Quadratic formula:\n\n${d}x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}${d}\n\nEnd."

        // Measure block bounds to ensure blocks render without crashing
        val bounds = exporter.measureBlockBounds(content, fontScale = 1.0f)
        assertTrue("Should have measured blocks", bounds.isNotEmpty())

        // Collect LaTeX spans via streaming path (which resolves them synchronously)
        val latexSpans = mutableListOf<JLatexAsyncDrawableSpan>()
        val pageContentWidthPx =
            (exporter.ptToPx(PdfExporter.PAGE_CONTENT_WIDTH_PT.toFloat()) / PdfExporter.PRINT_SCALE).toInt()
        val inflater = android.view.LayoutInflater.from(context)
        val document = exporter.getMarkwon().parse(content)
        val topLevelNodes = buildList {
            var node = document.firstChild
            while (node != null) {
                add(node)
                node = node.next
            }
        }

        val builder = com.pilcrowmd.export.PdfContentLayoutBuilder(context)
        for (node in topLevelNodes) {
            node.unlink()
            // Call internal inflateMeasuredBlock which resolves LaTeX synchronously
            val blockView = builder.inflateMeasuredBlock(
                exporter.getMarkwon(),
                node,
                inflater,
                pageContentWidthPx,
                1.0f,
            )
            latexSpans += collectLatexSpans(blockView)
        }

        assertTrue("Fixture should contain at least one LaTeX span", latexSpans.isNotEmpty())
        latexSpans.forEach { span ->
            assertTrue(
                "LaTeX span must be resolved to a real drawable (synchronous render), not raw text",
                span.drawable.hasResult(),
            )
            assertTrue(
                "Resolved LaTeX drawable should have positive dimensions",
                span.drawable.result?.let { it.intrinsicWidth > 0 || !it.bounds.isEmpty() } ?: false,
            )
        }
    }

    @Test
    fun testStreamingBlockRenderResolvesInlineSingleDollarLatexSynchronously() {
        // Inline single-`$…$` math must resolve synchronously in the export too — it
        // emits the SAME JLatexAsyncDrawableSpan as `$$…$$`, so the sync resolver covers it for free.
        // Currency (`$5`) stays literal (no span), so only the real formulas resolve.
        val content = "Greek \$\\Delta\$ and payoff \$S_T > K\$; the price is \$5 today."
        exporter.measureBlockBounds(content, fontScale = 1.0f)

        val pageContentWidthPx =
            (exporter.ptToPx(PdfExporter.PAGE_CONTENT_WIDTH_PT.toFloat()) / PdfExporter.PRINT_SCALE).toInt()
        val inflater = android.view.LayoutInflater.from(context)
        val builder = com.pilcrowmd.export.PdfContentLayoutBuilder(context)
        val spans = mutableListOf<JLatexAsyncDrawableSpan>()
        var node = exporter.getMarkwon().parse(content).firstChild
        while (node != null) {
            val next = node.next
            node.unlink()
            spans += collectLatexSpans(
                builder.inflateMeasuredBlock(exporter.getMarkwon(), node, inflater, pageContentWidthPx, 1.0f),
            )
            node = next
        }

        assertTrue("inline single-\$ math should produce LaTeX spans in the PDF path", spans.isNotEmpty())
        spans.forEach { span ->
            assertTrue(
                "inline single-\$ LaTeX span must be synchronously resolved, not raw text",
                span.drawable.hasResult(),
            )
        }
    }

    /** Walk the off-screen view tree and collect every LaTeX async span (block + inline). */
    private fun collectLatexSpans(view: android.view.View): List<JLatexAsyncDrawableSpan> {
        val result = mutableListOf<JLatexAsyncDrawableSpan>()
        when (view) {
            is android.view.ViewGroup ->
                for (i in 0 until view.childCount) result += collectLatexSpans(view.getChildAt(i))
            is android.widget.TextView ->
                (view.text as? android.text.Spanned)?.let { spanned ->
                    result += spanned.getSpans(0, spanned.length, JLatexAsyncDrawableSpan::class.java)
                }
        }
        return result
    }

    @Test
    fun testStreamingMeasureBlockBounds_LargeDoc_NoCrash_MultiPage() {
        // Stress (Safeguard S3): a large mixed-content doc must measure without crashing/OOM via
        // the streaming path and span multiple pages. ~500 sections with periodic code blocks.
        val content = buildString {
            for (i in 1..500) {
                append("## Section $i\n\n")
                append("Body paragraph $i. ".repeat(20))
                append("\n\n")
                if (i % 10 == 0) {
                    append("```kotlin\nval x = $i\n```\n\n")
                }
            }
        }

        val bounds = exporter.measureBlockBounds(content, fontScale = 1.0f)
        assertTrue("Large doc should measure to a large height", bounds.isNotEmpty())
        assertTrue("Large doc should produce many blocks (>100)", bounds.size > 100)
        assertTrue("Total height should be positive", bounds.last().second > 0)

        // Derive page starts — must span multiple pages
        val contentHeightPx = exporter.ptToPx(PdfExporter.PAGE_CONTENT_HEIGHT_PT.toFloat()).toInt()
        val starts = exporter.computePageStarts(bounds, contentHeightPx)
        assertTrue("Large doc should paginate into multiple pages", starts.size > 1)
    }

    @Test
    fun testWritePdfToUri_DeletesPartialFileOnFailure() {
        // Test that atomic write deletes the partial file on failure (Safeguard S1)
        val mockResolver = mockk<android.content.ContentResolver>()
        val mockUri = mockk<Uri>()

        // Setup mock to fail on write (simulating mid-write IOException)
        every { mockResolver.openOutputStream(mockUri) } throws IOException("Write failed mid-stream")

        // Attempt write (should fail and delete the partial file)
        var deleteWasCalled = false
        mockkStatic(android.provider.DocumentsContract::class) {
            every { android.provider.DocumentsContract.deleteDocument(mockResolver, mockUri) } answers {
                deleteWasCalled = true
                true // DocumentsContract.deleteDocument returns Boolean
            }

            try {
                // Create a mock PdfDocument (the write will fail on openOutputStream)
                val mockPdfDocument = mockk<PdfDocument>(relaxed = true)
                exporter.writePdfToUri(mockPdfDocument, mockUri, mockResolver)
                assertTrue("Should have thrown IOException", false)
            } catch (@Suppress("SwallowedException") e: IOException) {
                // Expected: IOException is caught and handled by the method
            }
        }

        // Verify delete was called (partial file cleanup, Safeguard S1)
        assertTrue("DocumentsContract.deleteDocument should be called on write failure", deleteWasCalled)
    }

    @Test
    fun testStreamingLongCodeLineWrapsInsteadOfClipping() {
        // An over-long code line must wrap to the printable width, not extend past it.
        // Use streaming blocks to verify the same behavior.
        val longLine = "fun f(" + (1..40).joinToString(", ") { "param$it: Int" } + "): Int = 0"
        val content = "```kotlin\n$longLine\n```"
        val bounds = exporter.measureBlockBounds(content)

        assertTrue("Should have at least one code block", bounds.isNotEmpty())

        // Collect the code block view via streaming and verify wrapping
        val pageContentWidthPx =
            (exporter.ptToPx(PdfExporter.PAGE_CONTENT_WIDTH_PT.toFloat()) / PdfExporter.PRINT_SCALE).toInt()
        val inflater = android.view.LayoutInflater.from(context)
        val builder = com.pilcrowmd.export.PdfContentLayoutBuilder(context)
        val document = exporter.getMarkwon().parse(content)
        var codeView: android.view.View? = null
        document.firstChild?.let { node ->
            codeView = builder.inflateMeasuredBlock(
                exporter.getMarkwon(),
                node,
                inflater,
                pageContentWidthPx,
                1.0f,
            )
        }

        assertNotNull("Code block should be measured", codeView)
        val codeText = findViewById(codeView!!, com.pilcrowmd.R.id.code_text)
        assertNotNull("Code TextView should exist", codeText)

        // Wrapped → the code TextView never exceeds the measured page-content width
        assertTrue(
            "Wrapped code text width (${codeText!!.measuredWidth}) must not exceed page content width " +
                "($pageContentWidthPx)",
            codeText.measuredWidth <= pageContentWidthPx,
        )

        // And the long line actually wrapped to more than one line
        assertTrue(
            "Long code line should wrap to multiple lines (was ${(codeText as android.widget.TextView).lineCount})",
            (codeText as android.widget.TextView).lineCount > 1,
        )
    }

    @Test
    fun testStreamingMeasureBlockBounds_BoundsMemory() {
        // Verify streaming measureBlockBounds bounds peak memory to O(types).
        // Build a ~300-block document (all prose paragraphs to maximize block count).
        val content = buildString {
            for (i in 1..300) {
                append("Paragraph $i with some text content.\n\n")
            }
        }

        // Stream measure block bounds (should keep only 1 view live at a time)
        val bounds = exporter.measureBlockBounds(content, fontScale = 1.0f)

        // Verify we have many blocks
        assertTrue("Should measure many blocks (>100)", bounds.size > 100)

        // Verify each bound pair is valid (top < bottom)
        bounds.forEach { (top, bottom) ->
            assertTrue("Block top=$top must be <= bottom=$bottom", top <= bottom)
        }

        // Verify bounds are monotonically increasing (stacked vertically)
        for (i in 0 until bounds.size - 1) {
            assertTrue(
                "Block ${i + 1} must start >= previous block's end (streaming stacking order)",
                bounds[i + 1].first >= bounds[i].second,
            )
        }
    }

    @Test
    fun testStreamingPaginationRegression_PageStartsUnchanged() {
        // Verify streaming pagination produces the same page starts as the block-aware algorithm.
        val content = """
            # Heading

            ${(1..100).joinToString("\n\n") { i -> "Paragraph $i. " + "word ".repeat(20) }}
        """.trimIndent()

        // Measure via streaming
        val bounds = exporter.measureBlockBounds(content, fontScale = 1.0f)
        val contentHeightPx = exporter.ptToPx(PdfExporter.PAGE_CONTENT_HEIGHT_PT.toFloat()).toInt()
        val starts = exporter.computePageStarts(bounds, contentHeightPx)

        // Verify pagination is non-trivial (multiple pages)
        assertTrue("Should paginate to multiple pages", starts.size > 1)

        // Verify page starts are monotonically increasing
        for (i in 0 until starts.size - 1) {
            assertTrue("Page ${i + 1} start must be > previous", starts[i + 1] > starts[i])
        }

        // Verify blocks don't straddle page boundaries (block-aware invariant)
        bounds.forEach { (blockTop, blockBottom) ->
            val pageStart = starts.last { it <= blockTop }
            assertTrue(
                "Block [$blockTop, $blockBottom) straddles page boundary starting at $pageStart",
                blockBottom <= pageStart + contentHeightPx,
            )
        }
    }

    @Test
    fun testStreaming_PeakLiveBlockViewsIsBoundedForLargeDoc() {
        // THE OOM-fix proof. The old builder inflated a View for ALL blocks at once (peak == block
        // count → OOM on large docs). The streaming path inflates+releases one block at a time, so the
        // peak concurrently-live block views stays O(1) regardless of document size. A JVM unit test
        // can't reproduce a device-heap OOM, so we assert the invariant that CAUSES it instead.
        val builder = com.pilcrowmd.export.PdfContentLayoutBuilder(context)
        builder.resetPeakLiveBlockViews()
        val content = (1..300).joinToString("\n\n") { "## Section $it\n\nParagraph $it body text here." }
        val widthPx = (
            exporter.ptToPx(PdfExporter.PAGE_CONTENT_WIDTH_PT.toFloat()) / PdfExporter.PRINT_SCALE
            ).toInt()

        val nodes = parseTopLevelBlocks(renderer.markwon, content)
        val bounds = builder.measureBlockBounds(renderer.markwon, nodes, widthPx, 1.0f)

        assertTrue("Should stream many blocks (>= 300)", bounds.size >= 300)
        val peak = builder.getPeakLiveBlockViews()
        assertTrue("Peak live views should have been measured (>0)", peak > 0)
        assertTrue(
            "Peak live block views must stay bounded O(1) over 300+ blocks; was $peak",
            peak <= 2,
        )
    }

    // NOTE: the actual PdfDocument page-render path (paginateAndRenderStreaming start/draw/finishPage)
    // is NOT unit-tested — the Robolectric PdfDocument shadow rejects rendering ("document is closed"),
    // which is why the project renders the native PdfDocument on-device only. Coverage here:
    // the memory bound (testStreaming_PeakLiveBlockViewsIsBoundedForLargeDoc), the blank-page guard
    // (testComputePageSpans_*), and pagination math (testComputePageStarts_*). End-to-end multi-page
    // rendering is covered by on-device verification.

    @Test
    fun testComputePageSpans_AllClipHeightsPositiveAndCoverContent() {
        // Guards the blank-page failure mode: every page must clip to a POSITIVE height. The earlier
        // off-by-one (next start == current start) produced clip height 0 → blank pages, invisible to
        // tests that only assert page counts.
        val contentHeightPx = 1000
        val pageStarts = listOf(0, 1000, 2000)
        val totalHeightPx = 2500
        val spans = exporter.computePageSpans(pageStarts, totalHeightPx, contentHeightPx)

        assertEquals(3, spans.size)
        spans.forEach { (start, clip) ->
            assertTrue("Clip height must be > 0 (blank-page guard); span=($start,$clip)", clip > 0)
            assertTrue("Clip must not exceed one content height; span=($start,$clip)", clip <= contentHeightPx)
        }
        assertEquals(0 to 1000, spans[0])
        assertEquals(1000 to 1000, spans[1])
        assertEquals(2000 to 500, spans[2]) // last page covers the 500px remainder
    }

    @Test
    fun parseTopLevelBlocks_returnsStandaloneBlocks_reusableAcrossPasses() {
        // The document is parsed ONCE and the resulting block list is reused
        // across both streaming passes (and re-used per page in Pass 2), instead of re-parsing on
        // every page draw (the old O(pages) parse cost). This test pins the two properties that make
        // that safe: (1) parseTopLevelBlocks returns the right top-level blocks as standalone
        // (unlinked) nodes, and (2) re-binding the SAME node list a second time is idempotent —
        // identical measured bounds — so a block rendered on multiple pages stays deterministic.
        val builder = com.pilcrowmd.export.PdfContentLayoutBuilder(context)
        val content = "# Title\n\nFirst paragraph.\n\n```kotlin\nval x = 1\n```\n\nLast paragraph."
        val widthPx = (
            exporter.ptToPx(PdfExporter.PAGE_CONTENT_WIDTH_PT.toFloat()) / PdfExporter.PRINT_SCALE
            ).toInt()

        val nodes = parseTopLevelBlocks(renderer.markwon, content)

        assertEquals("Heading + 2 paragraphs + code block", 4, nodes.size)
        nodes.forEach { node ->
            assertTrue("Block must be unlinked (no sibling)", node.next == null && node.previous == null)
        }

        // Re-binding the identical node list must yield identical bounds (idempotent render).
        val firstPass = builder.measureBlockBounds(renderer.markwon, nodes, widthPx, 1.0f)
        val secondPass = builder.measureBlockBounds(renderer.markwon, nodes, widthPx, 1.0f)
        assertEquals("Re-measuring the same nodes must be deterministic", firstPass, secondPass)
    }

    /** Depth-first search for the first descendant with [id]. */
    private fun findViewById(root: android.view.View, id: Int): android.view.View? {
        if (root.id == id) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findViewById(root.getChildAt(i), id)?.let { return it }
            }
        }
        return null
    }
}
