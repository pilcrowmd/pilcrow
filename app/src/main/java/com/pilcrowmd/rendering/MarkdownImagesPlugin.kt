// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.Spanned
import android.widget.TextView
import com.pilcrowmd.domain.markdown.ImageDestination
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.Prop
import io.noties.markwon.SpanFactory
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.AsyncDrawableScheduler
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImageProps
import io.noties.markwon.image.ImageSize
import io.noties.markwon.image.ImageSizeResolver
import org.commonmark.node.Image
import org.commonmark.node.Link

/**
 * M-93: draws markdown images (`![alt](src)`) through [loader] instead of leaving their alt text.
 *
 * CorePlugin's own image visitor is replaced only to carry the alt text to the drawable, so a
 * picture that cannot be shown has its alt text in the placeholder. Everything else matches it. A
 * raw HTML `<img>` reaches the same span factory through HtmlPlugin, without alt text.
 */
internal class MarkdownImagesPlugin(private val loader: AsyncDrawableLoader) : AbstractMarkwonPlugin() {

    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
        builder.asyncDrawableLoader(loader)
    }

    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        builder.setFactory(
            Image::class.java,
            SpanFactory { configuration, props ->
                AsyncDrawableSpan(
                    configuration.theme(),
                    AltTextAsyncDrawable(
                        ImageProps.DESTINATION.require(props),
                        configuration.asyncDrawableLoader(),
                        configuration.imageSizeResolver(),
                        ImageProps.IMAGE_SIZE.get(props),
                        ALT_TEXT.get(props).orEmpty(),
                    ),
                    AsyncDrawableSpan.ALIGN_BOTTOM,
                    ImageProps.REPLACEMENT_TEXT_IS_LINK.get(props, false),
                )
            },
        )
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(Image::class.java) { visitor, image ->
            val factory = visitor.configuration().spansFactory().get(Image::class.java)
            val start = visitor.length()
            visitor.visitChildren(image)
            val alt = visitor.builder().subSequence(start, visitor.length()).toString()
            // A span needs at least one character to draw over.
            if (start == visitor.length()) visitor.builder().append('￼')
            val configuration = visitor.configuration()
            val props = visitor.renderProps()
            ImageProps.DESTINATION.set(props, configuration.imageDestinationProcessor().process(image.destination))
            ImageProps.REPLACEMENT_TEXT_IS_LINK.set(props, image.parent is Link)
            ImageProps.IMAGE_SIZE.set(props, null)
            ALT_TEXT.set(props, alt)
            visitor.setSpans(start, factory?.getSpans(configuration, props))
            // M-93: a picture next to the note can be brought in by a folder grant: "Tap to show".
            if (ImageDestination.classify(image.destination) is ImageDestination.Relative) {
                visitor.setSpans(start, RelativeImageTapSpan())
            }
            // Render props outlive the node; an HTML <img> rendered later must not inherit this alt.
            ALT_TEXT.clear(props)
        }
    }

    override fun beforeSetText(textView: TextView, markdown: Spanned) {
        AsyncDrawableScheduler.unschedule(textView)
    }

    override fun afterSetText(textView: TextView) {
        AsyncDrawableScheduler.schedule(textView)
    }

    private companion object {
        val ALT_TEXT: Prop<String> = Prop.of("pilcrow-image-alt")
    }
}

/** An [AsyncDrawable] that knows its image's alt text, for the placeholder. */
internal class AltTextAsyncDrawable(
    destination: String,
    loader: AsyncDrawableLoader,
    imageSizeResolver: ImageSizeResolver,
    imageSize: ImageSize?,
    val altText: String,
) : AsyncDrawable(destination, loader, imageSizeResolver, imageSize)
