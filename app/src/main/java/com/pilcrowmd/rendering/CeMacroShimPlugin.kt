// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathNode
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.Node

/**
 * Applies [CeMacroShim] to every math node's latex payload in `beforeRender`, so JLaTeXMath
 * receives translated chemistry instead of the unknown `\ce` macro (which would fail parsing and
 * raw-dump the whole equation). Then applies [EquationTagShim] (M-225), so an equation number
 * (`\tag{1}`, `\notag`), which JLaTeXMath also does not know, no longer turns the formula into
 * its source.
 *
 * Runs after markdown parse, before span building — on every path that calls [io.noties.markwon.Markwon.render]
 * (preview recycler entries and the PDF layout builder alike). It walks ONLY the two ext-latex
 * node types, so prose, code blocks, and every other node are structurally unreachable; the
 * markdown source and node offsets are never touched (the latex payload is render-only state).
 * Both shims return the same reference for payloads they do not touch and never emit what they
 * rewrite (`\ce{`, `\tag`), so repeat renders of a retained node tree (recycler rebinds) are no-ops.
 * Search never reads these payloads: it finds maths in the raw markdown and skips formula spans.
 */
class CeMacroShimPlugin : AbstractMarkwonPlugin() {

    override fun beforeRender(node: Node) {
        node.accept(object : AbstractVisitor() {
            override fun visit(customNode: CustomNode) {
                if (customNode is JLatexMathNode) {
                    customNode.latex(rewrite(customNode.latex()))
                }
                visitChildren(customNode)
            }

            override fun visit(customBlock: CustomBlock) {
                if (customBlock is JLatexMathBlock) {
                    customBlock.latex(rewrite(customBlock.latex()))
                }
                visitChildren(customBlock)
            }
        })
    }

    private fun rewrite(latex: String): String = EquationTagShim.translate(CeMacroShim.translate(latex))
}
