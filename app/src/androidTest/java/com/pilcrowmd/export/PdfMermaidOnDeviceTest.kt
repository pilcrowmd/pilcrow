// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRenderer
import com.pilcrowmd.rendering.mermaid.MermaidStyle
import com.pilcrowmd.rendering.mermaid.WebViewMermaidRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M-70 end to end on a real WebView: the exporter built as DefaultAppContainer builds it prints a
 * ```mermaid block as a picture, and a diagram that cannot be drawn does not stop the export.
 *
 * The fallback for an undrawn diagram is its source on the same panel a picture sits on, so "page 1
 * has a non-white area" would pass without any picture at all. The same document is therefore also
 * exported through a renderer that draws nothing, and the real export's tallest unbroken non-white
 * band on page 1 must be clearly taller than the fallback's: only a drawn picture can make it so.
 *
 * The export runs on Dispatchers.IO, as MarkdownViewModel.exportPdf runs it; on the main thread the
 * exporter refuses to wait for the renderer and prints every diagram as source.
 */
@RunWith(AndroidJUnit4::class)
class PdfMermaidOnDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exportPrintsTheDiagramAsAPicture() {
        val drawn = export(WebViewMermaidRenderer(context), "m70")
        val fallback = export(NoPictures, "m70-fallback")
        val drawnBand = tallestInkedBand(drawn.first())
        val fallbackBand = tallestInkedBand(fallback.first())
        Log.i(TAG, "page 1 tallest band: drawn $drawnBand px, fallback $fallbackBand px; pages ${drawn.size}")
        assertTrue(
            "page 1 band with diagrams ($drawnBand px) is not taller than without ($fallbackBand px)",
            drawnBand > fallbackBand * MIN_BAND_RATIO,
        )
    }

    /** Exports [MATRIX] with [mermaid], writes cacheDir/[name].pdf and one PNG per page; returns the pages. */
    private fun export(mermaid: MermaidDiagramRenderer, name: String): List<Bitmap> {
        val exporter = PdfExporter(context, MarkwonRenderer(context), mermaid)
        val pdf = File(context.cacheDir, "$name.pdf")
        runBlocking(Dispatchers.IO) {
            val document = exporter.paginateAndRenderStreaming(MATRIX)
            try {
                pdf.outputStream().use { document.writeTo(it) }
            } finally {
                document.close()
            }
        }
        return ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page ->
                        val bitmap =
                            Bitmap.createBitmap(page.width * SCALE, page.height * SCALE, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(context.cacheDir, "$name-page-${index + 1}.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap
                    }
                }
            }
        }
    }

    /** The most consecutive rows that each hold at least one pixel that is not white. */
    private fun tallestInkedBand(page: Bitmap): Int {
        val row = IntArray(page.width)
        var best = 0
        var run = 0
        for (y in 0 until page.height) {
            page.getPixels(row, 0, page.width, 0, y, page.width, 1)
            run = if (row.any { it.isInk() }) run + 1 else 0
            best = maxOf(best, run)
        }
        return best
    }

    private fun Int.isInk() =
        Color.red(this) < WHITE_FLOOR || Color.green(this) < WHITE_FLOOR || Color.blue(this) < WHITE_FLOOR

    /** Draws nothing, so every diagram prints as its source: the exporter's fallback. */
    private object NoPictures : MermaidDiagramRenderer {
        override suspend fun render(source: String, widthPx: Int, style: MermaidStyle): Bitmap? = null
    }

    private companion object {
        const val TAG = "PdfMermaidOnDevice"
        const val SCALE = 2
        const val WHITE_FLOOR = 250
        const val MIN_BAND_RATIO = 1.5

        // .scratch/device/mermaid-matrix.md: every diagram type, and a broken one last.
        val MATRIX = """
# Mermaid matrix

Text before the first diagram.

```mermaid
flowchart TD
  A[Start] --> B{Is it working?}
  B -- Yes --> C[Ship it]
  B -- No --> D[Debug]
  D --> B
```

```mermaid
pie title Where the time goes
  "Reading" : 45
  "Editing" : 25
  "Searching" : 15
  "Exporting" : 10
  "Settings" : 5
```

```mermaid
sequenceDiagram
  Alice->>Bob: Hello Bob
  Bob-->>Alice: Hi Alice
  Alice->>Bob: Diagrams offline?
  Bob-->>Alice: Yes
```

```mermaid
classDiagram
  class Reader { +open() +render() }
  class Editor { +save() }
  Reader <|-- Editor
```

```mermaid
stateDiagram-v2
  [*] --> Closed
  Closed --> Open : open
  Open --> Closed : close
```

```mermaid
erDiagram
  NOTE ||--o{ TAG : has
  NOTE { string title }
```

```mermaid
gantt
  title Plan
  dateFormat YYYY-MM-DD
  section Work
  Build :a1, 2026-10-01, 3d
  Test  :after a1, 2d
```

```mermaid
journey
  title Reading a note
  section Open
    Find file: 4: Me
    Read: 5: Me
```

```mermaid
flowchart LR
  this is not valid ((( mermaid
```

Text after the last diagram, with a temperature word.
        """.trimIndent()
    }
}
