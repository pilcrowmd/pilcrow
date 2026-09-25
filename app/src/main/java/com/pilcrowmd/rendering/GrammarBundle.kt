// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import io.noties.prism4j.annotations.PrismBundle

/**
 * Prism4j grammar bundle annotation.
 * kapt generates a GrammarLocator implementation at compile time.
 * `includeAll` bundles every grammar Prism4j ships, which is these 25: brainfuck, c, clike, clojure,
 * cpp, csharp, css, css-extras, dart, git, go, groovy, java, javascript, json, kotlin, latex,
 * makefile, markdown, markup (also xml, html, svg, mathml), python, scala, sql, swift, yaml.
 * There is NO TypeScript, Rust or Bash grammar (M-136). `diff`/`patch` fences are highlighted with
 * the `git` grammar through [AliasGrammarLocator] (M-133).
 */
@PrismBundle(
    includeAll = true,
)
class GrammarBundle
