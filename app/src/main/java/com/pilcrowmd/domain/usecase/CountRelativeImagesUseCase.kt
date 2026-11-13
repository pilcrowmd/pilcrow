// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.ImageDestination
import com.pilcrowmd.domain.markdown.ReaderModel
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image

/**
 * M-93: how many markdown images in a note point at a path next to it (`images/photo.png`), the
 * only kind a folder grant can bring in. Images in code are not images, and the parser already
 * leaves them out. Read-only: nothing is changed.
 *
 * Counted in the reader's own blocks (large-documents spec, Rule 5): no parse of its own, which on a
 * 20 MB file shown as plain text was a whole-document tree nobody looked at (S8-A93). A note shown
 * as plain text shows no pictures, so it has none to ask a folder for.
 */
class CountRelativeImagesUseCase {

    operator fun invoke(model: ReaderModel): Int {
        if (model !is ReaderModel.Markdown) return 0
        var count = 0
        val visitor = object : AbstractVisitor() {
            override fun visit(image: Image) {
                if (ImageDestination.classify(image.destination) is ImageDestination.Relative) count++
                visitChildren(image)
            }
        }
        model.blocks.forEach { it.accept(visitor) }
        return count
    }
}
