// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pilcrowmd.domain.markdown.ReaderWords
import com.pilcrowmd.domain.model.PdfColors
import com.pilcrowmd.domain.model.RenderMode
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.rendering.mermaid.WebViewMermaidRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * S8-A82 on a real device: one export per choice, with a diagram, leaves cacheDir/s8a82-<name>-page-N.png
 * to look at, and the corners of every page in the page colour. Also logs the file size and time of a
 * long document for each choice (tag S8A82).
 */
@RunWith(AndroidJUnit4::class)
class PdfThemeExportOnDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val exporter =
        PdfExporter(context, MarkwonRenderer(context, ReaderWords.ENGLISH), WebViewMermaidRenderer(context))

    private val choices = listOf(
        "light" to PdfPalette.of(PdfColors.LIGHT_FOR_PRINTING, ThemeMode.DARK),
        "dark" to PdfPalette.of(PdfColors.MATCH_APP_THEME, ThemeMode.DARK),
        "amoled" to PdfPalette.of(PdfColors.MATCH_APP_THEME, ThemeMode.AMOLED),
        "theme-light" to PdfPalette.of(PdfColors.MATCH_APP_THEME, ThemeMode.LIGHT),
    )

    private fun write(content: String, palette: PdfPalette, file: File) = runBlocking(Dispatchers.IO) {
        val document = exporter.paginateAndRenderStreaming(content, 1f, RenderMode.MARKDOWN, palette)
        try {
            file.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    @Suppress("NestedBlockDepth") // Four use-blocks around one page check.
    @Test
    fun everyChoiceColoursTheWholePage() {
        choices.forEach { (name, palette) ->
            val pdf = File(context.cacheDir, "s8a82-$name.pdf")
            write(SAMPLE, palette, pdf)
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.MAGENTA) // Whatever the page does not cover stays magenta.
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            File(context.cacheDir, "s8a82-$name-page-${index + 1}.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            val expected = palette.page.primaryBackground.toArgb()
                            assertEquals(name, expected, bitmap.getPixel(0, 0))
                            assertEquals(name, expected, bitmap.getPixel(bitmap.width - 1, bitmap.height - 1))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun logsSizeAndTimeOfALongDocument() {
        val long = (1..LONG_SECTIONS).joinToString("\n\n") { "## Section $it\n\n$LONG_SECTION" }
        // Twice round: the first exports warm the code paths, so the second round is the fair comparison.
        repeat(2) { round ->
            choices.forEach { (name, palette) ->
                val pdf = File(context.cacheDir, "s8a82-long-$name.pdf")
                val start = SystemClock.elapsedRealtime()
                write(long, palette, pdf)
                val ms = SystemClock.elapsedRealtime() - start
                Log.i("S8A82", "round=${round + 1} choice=$name bytes=${pdf.length()} ms=$ms")
            }
        }
    }

    private companion object {
        const val LONG_SECTIONS = 150
        val LONG_SECTION = """
            Body text with **bold**, `inline code` and a [link](https://example.com). Enough words to fill a line or two.

            > A quote.

            | a | b |
            |---|---|
            | 1 | 2 |

            ```kotlin
            fun main() = println("hello")
            ```
        """.trimIndent()
        val SAMPLE = PDF_SAMPLE + "\n\n```mermaid\nflowchart TD\n  A[Start] --> B{Choice}\n  B --> C[End]\n```\n"
    }
}

private val PDF_SAMPLE = """
---
title: Sample
---

# Heading one

## Heading two

Body with **bold**, *italic*, `inline code`, a [link](https://example.com) and ${'$'}${'$'}x^2${'$'}${'$'} maths.

> A quote.

> [!NOTE]
> A callout.

> [!WARNING]
> Another callout.

- [x] Done
- [ ] Open

| Name | Value |
|------|-------|
| a    | 1     |

```kotlin
fun main() { val s = "text"; println(s + 1) } // comment
```

---

A note.[^1]

[^1]: The footnote.
""".trim()
