// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.min

/**
 * The full-screen diagram viewer's zoom (S8-A7), as plain values the gestures turn into new values.
 *
 * The picture is drawn fitted inside the viewport (1x). The transform has its origin at the viewport's
 * top-left corner: a point at `q` in the fitted layout shows at `offset + scale * q`. Every change is
 * clamped so no blank space shows beyond the picture's edges, and a picture smaller than the viewport on
 * an axis stays centred on it. Until [layout] has measured both, nothing is clamped.
 *
 * @property viewport the area the picture is shown in, px.
 * @property picture the picture's own size, px; it is fitted into [viewport] keeping its aspect ratio.
 * @property scale 1 = fitted to the viewport.
 * @property offset where the viewport's top-left corner of the fitted layout lands, px.
 */
data class DiagramZoom(
    val viewport: Size = Size.Zero,
    val picture: Size = Size.Zero,
    val scale: Float = MIN_SCALE,
    val offset: Offset = Offset.Zero,
) {
    /** Measured: the viewport and the picture, with the offset kept inside the new limits. */
    fun layout(viewport: Size, picture: Size): DiagramZoom = copy(viewport = viewport, picture = picture).clamped()

    /** A pinch/pan gesture: [zoom] times about [centroid], then moved by [pan]; the point under the centroid stays. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float): DiagramZoom {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = newScale / scale
        return copy(scale = newScale, offset = centroid - (centroid - offset) * ratio + pan).clamped()
    }

    /** Double-tap: back to fit when zoomed in, else [DOUBLE_TAP_SCALE] about [at]. */
    fun doubleTap(at: Offset): DiagramZoom = if (scale > MIN_SCALE + FIT_TOLERANCE) {
        copy(scale = MIN_SCALE, offset = Offset.Zero).clamped()
    } else {
        transform(at, Offset.Zero, DOUBLE_TAP_SCALE / scale)
    }

    private fun clamped(): DiagramZoom {
        if (viewport == Size.Zero || picture == Size.Zero) return this
        val fit = min(viewport.width / picture.width, viewport.height / picture.height)
        val shownW = picture.width * fit
        val shownH = picture.height * fit
        return copy(
            offset = Offset(
                clampAxis(offset.x, viewport.width, (viewport.width - shownW) / 2, shownW),
                clampAxis(offset.y, viewport.height, (viewport.height - shownH) / 2, shownH),
            ),
        )
    }

    /** One axis: centred while the scaled picture fits the viewport, else kept so its edges never pull inside. */
    private fun clampAxis(value: Float, viewportExtent: Float, start: Float, extent: Float): Float {
        val scaledExtent = scale * extent
        return if (scaledExtent <= viewportExtent) {
            viewportExtent / 2 - scale * (start + extent / 2)
        } else {
            value.coerceIn(viewportExtent - scale * (start + extent), -scale * start)
        }
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f

        // A pinch that ends within a hair of fit counts as fit, so the next double-tap zooms in.
        private const val FIT_TOLERANCE = 0.01f
    }
}
