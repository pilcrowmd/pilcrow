// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import org.commonmark.node.Image
import org.commonmark.node.Link

/**
 * What the PDF export's pictures say about themselves, so the render can decide per image. The export
 * sets [canPrint]; until then, and in every instance that has no hook, nothing is printable and an image
 * prints its alt text, as it did before pictures were printed (S8-A89).
 */
internal class PrintPictures {
    @Volatile
    var canPrint: (destination: String) -> Boolean = { false }
}

/**
 * S8-A89, PDF only: an image the export can print becomes a picture span over its alt text, which the
 * export then fills with the decoded picture (`PdfImageSource.printPictures`); any other image is left
 * exactly as it is without this plugin, its alt text. The pictures are never loaded here: a span with no
 * result draws nothing, and the export sets the result before it measures.
 */
internal class PrintPicturePlugin(private val pictures: PrintPictures) : AbstractMarkwonPlugin() {

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(Image::class.java) { visitor, image ->
            val start = visitor.length()
            visitor.visitChildren(image) // the alt text, which is what prints when there is no picture
            val configuration = visitor.configuration()
            val destination = configuration.imageDestinationProcessor().process(image.destination)
            if (!pictures.canPrint(destination)) return@on
            // A span needs at least one character to sit over.
            if (start == visitor.length()) visitor.builder().append('￼')
            val drawable = AsyncDrawable(
                destination,
                configuration.asyncDrawableLoader(),
                configuration.imageSizeResolver(),
                null,
            )
            visitor.setSpans(
                start,
                AsyncDrawableSpan(
                    configuration.theme(),
                    drawable,
                    AsyncDrawableSpan.ALIGN_BOTTOM,
                    image.parent is Link,
                ),
            )
        }
    }
}
