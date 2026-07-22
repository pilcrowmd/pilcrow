// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.pilcrowmd.rendering.GrammarLocatorDef
import com.pilcrowmd.ui.theme.PilcrowTypography
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j
import java.util.concurrent.ForkJoinPool

/**
 * Singleton Markwon renderer configured with all rendering plugins.
 * Renders markdown to native Spannables (no WebView).
 * Handles CommonMark + GFM + per-language syntax highlighting + LaTeX math.
 * Gracefully degrades on unsupported syntax (Mermaid, footnotes, etc.).
 *
 * Features:
 * - JLatexMathPlugin (ext-latex 4.6.2): renders math as native JLatexMath bitmaps, both as a
 *   standalone block and inline within a paragraph. Async executor prevents main-thread ANR;
 *   parse errors fall back gracefully to raw source (Safeguard 3).
 * - MarkwonInlineParserPlugin: registered before JLatexMathPlugin so the latter can hook its
 *   inline `$$…$$` processor into the inline parser (order matters). Also carries our
 *   [SingleDollarMathInlineProcessor] for inline `$…$`.
 * - FrontmatterPlugin: emits a styled `yaml` code block for leading `---...---` via a custom
 *   commonmark BlockParser — no source mutation, so char offsets stay aligned with the editor
 *   See Frontmatter.kt.
 * - Math delimiter policy: `$$…$$` (display, library) AND `$…$` (inline, our
 *   [SingleDollarMathInlineProcessor]). Single-`$` uses currency-disambiguation
 *   rules so `$`-prices stay literal; `\$` escapes a literal `$`. Both delimiters emit the same
 *   `JLatexMathNode`, so render/theme/async/PDF are shared. Parse-time only — never mutates
 *   source (Safeguard 2; preserves search/editor offset parity).
 *
 * Copy affordance is NOT a plugin here — it is a pinned overlay button rendered by
 * FencedCodeBlockEntry. The old CodeBlockCopyPlugin overrode the FencedCodeBlock visitor and
 * dropped the code content (rendered only "[Copy]"), so it was removed.
 */
class MarkwonRenderer(private val context: Context) {

    // Lazy singleton: configure once, reuse for all renders.
    // Public so the block-level RecyclerView adapter can drive it (markwon-recycler).
    // The plugin chain is built by [buildPilcrowMarkwon] so a test can construct an identical,
    // pre-warm-free instance (the init{} pre-warm below parses on a background thread, and Markwon's
    // InlineProcessors are stateful/shared — concurrent parses would race).
    val markwon: Markwon by lazy { buildPilcrowMarkwon(context) }

    // The font pre-warm parse (see init) runs on this thread. Retained ONLY so a test can
    // deterministically await it ([awaitFontPreWarm]): Markwon's inline parser is stateful and not
    // safe for a concurrent parse, so a test that parses on this same instance while the pre-warm is
    // still running can corrupt parser state (an intermittent StringIndexOutOfBounds). Production
    // never joins it — the warm-up stays fully asynchronous.
    private val preWarmThread = Thread {
        try {
            markwon.toMarkdown("$$1$$") // Simple dummy formula
        } catch (e: Exception) {
            Log.w("MarkwonRenderer", "font pre-warm failed (acceptable): ${e.message}")
        }
    }

    init {
        // Font pre-warm: render a dummy formula on a background thread at app init
        // to load JLatexMath fonts (~100-300ms). Prevents first real formula from stuttering.
        preWarmThread.start()
    }

    /**
     * Test-only: block until the init{} font pre-warm parse has finished, so a test can parse on
     * [markwon] without racing the pre-warm on Markwon's stateful inline parser. No-op in production
     * (never called there); the warm-up itself remains asynchronous.
     */
    @VisibleForTesting
    internal fun awaitFontPreWarm(timeoutMillis: Long = PRE_WARM_JOIN_TIMEOUT_MS) {
        preWarmThread.join(timeoutMillis)
        // Fail fast + clearly if the pre-warm hasn't finished (e.g. a bogged CI machine), rather than
        // letting a test proceed and re-flake on the obscure concurrent-parse StringIndexOutOfBounds.
        check(!preWarmThread.isAlive) { "Font pre-warm did not complete within $timeoutMillis ms" }
    }

    private companion object {
        const val PRE_WARM_JOIN_TIMEOUT_MS = 5_000L
    }
}

/**
 * Build the Pilcrow Markwon instance (full plugin chain). Extracted from [MarkwonRenderer] so
 * tests can build an identical instance without the init{} font pre-warm thread (whose background
 * parse would race the test on Markwon's shared, stateful InlineProcessors). Production always goes
 * through [MarkwonRenderer.markwon].
 */
internal fun buildPilcrowMarkwon(context: Context): Markwon {
    // Initialize Prism4j with kapt-generated GrammarLocator
    val prism4j = Prism4j(GrammarLocatorDef())

    // Calculate body font size in pixels for JLatexMath (17sp)
    val baseFontSizePx = with(context.resources.displayMetrics) {
        (PilcrowTypography.PROSE_BODY_FONT_SIZE_SP * density)
    }

    val builder = Markwon.builder(context)
        // Core markdown parsing (CommonMark)
        .usePlugin(CorePlugin.create())
        // Render leading `---…---` as a styled `yaml` code block via a custom
        // BlockParser (no source mutation → char offsets stay aligned with the editor).
        .usePlugin(FrontmatterPlugin())
        // GFM: tables, strikethrough, task lists
        .usePlugin(TablePlugin.create(context))
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TaskListPlugin.create(context))
        // Autolinks
        .usePlugin(LinkifyPlugin.create())
        // Enable the inline parser so JLatexMathPlugin can hook its inline `$$…$$` processor in.
        // Must come before JLatexMathPlugin (order matters). We also register our own single-`$`
        // processor here (the lambda form keeps all default inline processors, incl. the
        // backslash-escape one that consumes `\$` before our processor sees it).
        .usePlugin(
            MarkwonInlineParserPlugin.create { it.addInlineProcessor(SingleDollarMathInlineProcessor()) },
        )

    // LaTeX math rendering via JLatexMath
    // Async executor prevents main-thread ANR; error handler provides graceful fallback
    try {
        builder.usePlugin(
            JLatexMathPlugin.create(baseFontSizePx) { jlatexBuilder ->
                jlatexBuilder
                    .inlinesEnabled(true) // render `$$…$$` inline within a paragraph
                    .blocksEnabled(true) // Enable block $$…$$ (rendered via inline or LatexBlockEntry)
                    .blocksLegacy(false) // Use 4.3.0+ parsing
                    .errorHandler { latex, error ->
                        // Graceful fallback (Safeguard 3): return null to render raw $…$ text
                        // instead of crashing the adapter. The error is logged in the span layer.
                        Log.w("JLatexMath", "parse error for LaTeX '$latex': ${error.message}")
                        null
                    }
                    // Async rendering: use a background executor (ForkJoinPool) to avoid ANR on main thread
                    // This prevents the app from freezing while JLatexMath renders math bitmaps.
                    .executorService(ForkJoinPool.commonPool())
                // Color: JLatexMath text must be primaryText, not default (black)
                // Use JLatexMathTheme or equivalent to set the color token
                // (theme API will be integrated when the plugin is called)
            },
        )
    } catch (e: Exception) {
        // If ext-latex is missing or incompatible, log and skip it
        Log.e("MarkwonRenderer", "failed to load JLatexMathPlugin: ${e.message}")
    }

    return builder
        // Per-language syntax highlighting with One Dark theme
        .usePlugin(
            SyntaxHighlightPlugin.create(
                prism4j,
                PilcrowTheme(),
            ),
        )
        // Limited HTML (only safe tags: <br>, <sub>, <sup>, <details>)
        .usePlugin(HtmlPlugin.create())
        .build()
}
