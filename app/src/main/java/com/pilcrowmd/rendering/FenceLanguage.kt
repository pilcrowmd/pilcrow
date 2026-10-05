// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

private val WHITESPACE = Regex("\\s+")

/**
 * The language a fence's info string names: its first word, before any `{`, lower-cased (M-243).
 * ```` ```python title="x" {2} ```` and ```` ```Python{2} ```` both name `python`. Every place that
 * picks a colouring or a block type from the info string reads it through here.
 */
fun fenceLanguage(info: String?): String = info.orEmpty().trim().split(WHITESPACE, limit = 2)[0]
    .substringBefore('{').lowercase()
