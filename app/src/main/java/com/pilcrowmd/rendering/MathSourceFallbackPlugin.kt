// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.util.Log
import android.util.LruCache
import androidx.annotation.VisibleForTesting
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.ext.latex.JLatexMathPlugin
import org.commonmark.node.Block
import org.commonmark.node.Node
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.JLatexMathAndroid

/**
 * A formula JLaTeXMath cannot parse is shown as its source in plain text, so it wraps onto as many
 * lines as it needs, like the prose around it (M-260, issue #9).
 *
 * Without this, the plugin lays every formula out as one image span over its source and learns only
 * on its background loader that there is no image to draw. The span then paints the source itself,
 * as one line that cannot break: a long formula runs off the right edge and cannot be read. A span
 * cannot wrap, so the decision is taken before layout: each formula is parsed here while the text is
 * built, and one that fails gets plain text instead of the span.
 *
 * Wraps the plugin instead of replacing its visitors, so every formula that parses still goes
 * through the plugin's own visitor and renders exactly as before. The text is the plugin's own
 * placeholder (newlines become spaces, trimmed), so what the reader sees is unchanged except that it
 * now wraps. [MathSourceSpan] marks it, so search skips it as it skips a rendered formula.
 */
internal class MathSourceFallbackPlugin(private val math: JLatexMathPlugin) : MarkwonPlugin by math {

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        math.configureVisitor(
            object : MarkwonVisitor.Builder by builder {
                override fun <N : Node> on(
                    node: Class<N>,
                    nodeVisitor: MarkwonVisitor.NodeVisitor<in N>?,
                ): MarkwonVisitor.Builder {
                    builder.on(node, nodeVisitor?.let { checked(it) })
                    return this
                }
            },
        )
    }

    private fun <N : Node> checked(rendered: MarkwonVisitor.NodeVisitor<in N>) =
        MarkwonVisitor.NodeVisitor<N> { visitor, node ->
            val latex = (node as? JLatexMathBlock)?.latex() ?: (node as? JLatexMathNode)?.latex()
            if (latex == null || parses(latex)) {
                rendered.visit(visitor, node)
            } else {
                appendSource(visitor, node, latex)
            }
        }

    private fun appendSource(visitor: MarkwonVisitor, node: Node, latex: String) {
        if (node is Block) visitor.blockStart(node)
        val start = visitor.length()
        visitor.builder().append(latex.replace('\n', ' ').trim())
        visitor.setSpans(start, MathSourceSpan())
        if (node is Block) visitor.blockEnd(node)
    }

    /**
     * The parse the plugin's loader runs first (`JLatexMathDrawable` starts with `new TeXFormula`),
     * so a formula that fails here would have failed there. Only the parse: building the image stays
     * on the loader. A formula that parses but fails to build keeps the old one-line fallback.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun parses(latex: String): Boolean {
        if (!libraryReady()) return true
        if (parsed.get(latex) != null) return true
        onParse?.invoke(latex)
        return try {
            TeXFormula(latex)
            parsed.put(latex, Unit)
            true
        } catch (e: Exception) {
            rejected(latex, e)
        } catch (e: LinkageError) {
            rejected(latex, e)
        } catch (e: StackOverflowError) {
            // Deep nesting overflows the parser's recursion. On the loader thread that was caught;
            // here it is the main thread, and an uncaught overflow would crash the reader
            // (Safeguard 3). The stack has unwound by the time it is caught, so this is safe to
            // handle as a parse failure.
            rejected(latex, e)
        }
    }

    private fun rejected(latex: String, error: Throwable): Boolean {
        Log.w("JLatexMath", "parse error for LaTeX '$latex': ${error.message}")
        return false
    }

    internal companion object {
        /** An asset every JLaTeXMath install has; opening it needs only `JLatexMathAndroid.init`. */
        private const val PROBE_ASSET = "DefaultTeXFont.xml"

        /** Total source length kept in [parsed]: about a thousand typical formulas. */
        private const val PARSED_CACHE_CHARS = 64 * 1024

        /**
         * Formulas already seen to parse (NEW-33a). The check runs on the main thread at every bind,
         * and a fling back, a pinch or a theme switch binds the same formulas again; with this, a
         * re-bind costs a lookup instead of a parse. Only successes are kept: whether a formula
         * parses can depend on a `\newcommand` in another one, so a failure is checked again next
         * time. A kept success that a later `\renewcommand` breaks gets the plugin's own one-line
         * placeholder, as every failure did before M-260. Shared by every instance, since the
         * verdict depends on neither theme nor size.
         */
        private val parsed = object : LruCache<String, Unit>(PARSED_CACHE_CHARS) {
            override fun sizeOf(key: String, value: Unit) = key.length
        }

        /** Called with each formula the check parses, cache hits excluded; lets a test see the cache. */
        @VisibleForTesting
        @Volatile
        internal var onParse: ((String) -> Unit)? = null

        @Volatile
        private var initialised = false

        /**
         * Whether the check may touch [TeXFormula]. Its class initializer reads assets through
         * `JLatexMathAndroid`, and if it runs before `JLatexMathAndroid.init` it fails for good: the
         * class is unusable for the rest of the process. On a device the library's ContentProvider
         * calls init before any app code; where nothing has (Robolectric runs no providers), the check
         * must not be the first to touch the class, so it leaves the formula to the plugin, as before.
         * Once true it stays true: init cannot be undone.
         */
        // The library throws NullPointerException or RuntimeException; both mean "not ready". An
        // IOException from close() is caught too: it is not checked in Kotlin and would otherwise
        // reach the bind on the main thread (Safeguard 3). Not ready leaves the formula to the plugin.
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private fun libraryReady(): Boolean {
            if (!initialised) {
                initialised = try {
                    JLatexMathAndroid.getResourceAsStream(PROBE_ASSET).close()
                    true
                } catch (e: Exception) {
                    false
                }
            }
            return initialised
        }
    }
}

/** Marks the source text of a formula that failed to parse (see [MathSourceFallbackPlugin]). */
class MathSourceSpan
