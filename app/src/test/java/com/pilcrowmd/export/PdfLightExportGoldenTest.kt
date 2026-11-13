// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.ThresholdValidator
import com.github.takahirom.roborazzi.captureRoboImage
import com.pilcrowmd.rendering.buildPrintMarkwon
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S8-A82: the default PDF must not change. The golden was recorded from `main` BEFORE the "PDF colors"
 * setting existed, so verifying it is the before/after comparison of a light export. Do not re-record
 * it to make this feature pass; only for a deliberate change to the light PDF.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xhdpi")
class PdfLightExportGoldenTest {

    @Test
    fun theDefaultExportLooksAsItDidBeforeTheSetting() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val markwon = buildPrintMarkwon(context)
        val builder = PdfContentLayoutBuilder(context)
        val nodes = parseTopLevelBlocks(markwon, PDF_COLOUR_SAMPLE)
        val spec = PdfContentLayoutBuilder.ParseSpec(markwon, nodes, PAGE_WIDTH_PX, 1f)
        val bounds = builder.measureBlockBounds(markwon, nodes, PAGE_WIDTH_PX, 1f)
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH_PX, bounds.last().second, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        builder.forEachBlock(spec) { index, _, view ->
            canvas.save()
            canvas.translate(0f, bounds[index].first.toFloat())
            view.draw(canvas)
            canvas.restore()
        }
        bitmap.captureRoboImage(
            "src/test/screenshots/pdf_light_export.png",
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(
                    imageComparator = SimpleImageComparator(maxDistance = MAX_PIXEL_DISTANCE),
                    resultValidator = ThresholdValidator(0f),
                ),
            ),
        )
    }

    private companion object {
        const val PAGE_WIDTH_PX = 780

        /**
         * Measured S8-A83: the CI runner draws the vector icons up to 2 of 255 levels off this Mac-recorded
         * golden, on 261 of 1,344,720 pixels. Text matches exactly. The distance is the RGBA Euclidean one on
         * 0..1 channels; the worst pixel measured 0.0096, and one channel off by 3 levels is 0.0118 and fails.
         */
        const val MAX_PIXEL_DISTANCE = 0.01f
    }
}

/** One of each thing a PDF page can show. Mermaid is on-device only: it needs a WebView. */
internal val PDF_COLOUR_SAMPLE = """
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
""".trimIndent()
