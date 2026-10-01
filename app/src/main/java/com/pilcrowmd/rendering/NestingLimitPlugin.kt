// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import com.pilcrowmd.domain.markdown.NestingLimit
import io.noties.markwon.AbstractMarkwonPlugin
import org.commonmark.parser.Parser

/**
 * Registers [NestingLimit.postProcessor] on Markwon's parser (NEW-11). Must be the FIRST plugin in the
 * chain: commonmark runs post-processors in registration order, and the task-list plugin's
 * post-processor walks the whole tree recursively inside `markwon.parse`.
 */
class NestingLimitPlugin : AbstractMarkwonPlugin() {

    override fun configureParser(builder: Parser.Builder) {
        builder.postProcessor(NestingLimit.postProcessor)
    }
}
