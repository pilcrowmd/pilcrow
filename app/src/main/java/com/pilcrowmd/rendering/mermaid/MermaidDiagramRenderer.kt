// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering.mermaid

import android.graphics.Bitmap

/**
 * Draws Mermaid diagram source into a picture, on the device and offline (M-70).
 *
 * Contract:
 * - Returns the diagram as a [Bitmap], or `null` when it could not be drawn for ANY reason: the
 *   source does not parse, the engine is unavailable on this device, or the render ran out of
 *   time. A `null` means "show the source instead"; it is never an error the caller must handle.
 * - Never throws (cancellation of the calling coroutine aside), never blocks the main thread, and
 *   never hangs: every render is bounded by a hard timeout, however hostile the source.
 * - The bitmap is exactly [widthPx] wide when [widthPx] is within 320..1600 px, so a fill-width view
 *   shows it 1:1; a width outside that range is clamped to it, and the bitmap is the clamped width
 *   wide (a view of another width scales it, keeping its aspect ratio). The diagram inside
 *   is never drawn larger than its natural size at the screen density, is centred on a
 *   transparent background, and is scaled down to fit the width or the height cap.
 * - The returned bitmap may be shared with later callers (results are cached): do not recycle or
 *   mutate it.
 */
interface MermaidDiagramRenderer {
    suspend fun render(source: String, widthPx: Int, style: MermaidStyle): Bitmap?

    /** Releases cached pictures and the engine, e.g. on `onTrimMemory`. Renders keep working after. */
    fun trim() {}
}
