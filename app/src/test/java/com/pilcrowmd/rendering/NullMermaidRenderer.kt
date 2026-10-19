// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.Bitmap
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRenderer
import com.pilcrowmd.rendering.mermaid.MermaidStyle

/**
 * A Mermaid renderer that can never draw (M-70): every diagram answers "no picture" at once, so a
 * test or golden always shows the fallback (the source plus its caption), never a WebView picture.
 */
object NullMermaidRenderer : MermaidDiagramRenderer {
    override suspend fun render(source: String, widthPx: Int, style: MermaidStyle): Bitmap? = null
}
