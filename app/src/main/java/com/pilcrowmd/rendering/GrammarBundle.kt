// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import io.noties.prism4j.annotations.PrismBundle

/**
 * Prism4j grammar bundle annotation.
 * kapt generates a GrammarLocator implementation at compile time.
 * Includes grammars for common AI-output languages:
 * Python, JavaScript, TypeScript, Java, Kotlin, Go, Rust, C, C++, C#,
 * Bash, SQL, YAML, JSON, XML, CSS, Markdown, and more.
 */
@PrismBundle(
    includeAll = true,
)
class GrammarBundle
