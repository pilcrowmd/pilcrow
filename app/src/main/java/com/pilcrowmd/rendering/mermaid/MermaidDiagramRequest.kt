// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering.mermaid

/** What the full-screen viewer asks the renderer for again: the diagram's source and the width it was drawn at. */
data class MermaidDiagramRequest(val source: String, val widthPx: Int)
