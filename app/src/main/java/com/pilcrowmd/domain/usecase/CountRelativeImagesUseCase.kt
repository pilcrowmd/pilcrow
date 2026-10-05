// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.ImageDestination
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.parser.Parser

/**
 * M-93: how many markdown images in a note point at a path next to it (`images/photo.png`), the
 * only kind a folder grant can bring in. Images in code are not images, and the parser already
 * leaves them out. Read-only: the text is parsed, never changed.
 */
class CountRelativeImagesUseCase {
    private val parser = Parser.builder().build()

    operator fun invoke(markdown: String): Int {
        var count = 0
        parser.parse(markdown).accept(
            object : AbstractVisitor() {
                override fun visit(image: Image) {
                    if (ImageDestination.classify(image.destination) is ImageDestination.Relative) count++
                    visitChildren(image)
                }
            },
        )
        return count
    }
}
