// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.pilcrowmd.rendering.GrammarLocatorDef
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.PrintColorScheme
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
import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import java.util.concurrent.ForkJoinPool

/**
 * Singleton Markwon renderer configured with all rendering plugins.
 * Renders markdown to native Spannables (no WebView).
 * Handles CommonMark + GFM + per-language syntax highlighting + LaTeX math.
 * Gracefully degrades on unsupported syntax (Mermaid, wiki-links, emoji shortcodes, etc.).
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
    // The plugin chain is built by [buildPilcrowMarkwon] so a test can construct an identical,
    // pre-warm-free instance (the init{} pre-warm below parses on a background thread, and Markwon's
    // InlineProcessors are stateful/shared — concurrent parses would race).
    //
    // M-135: a Markwon instance bakes its code colours in when it is built, so there is one instance
    // per screen theme, each built once. [markwon] is the Dark one. The screen asks for the instance
    // matching the active scheme through [markwonFor]; nothing is shared or mutated between the two,
    // so a theme toggle only switches which instance the adapter is built with.
    val markwon: Markwon by lazy { buildPilcrowMarkwon(context, DarkColorScheme) }
    private val lightMarkwon: Markwon by lazy { buildPilcrowMarkwon(context, LightColorScheme) }

    // The PDF export's instance (M-132). The export has always drawn code on the Dark code panel, so it
    // keeps Dark's surface and text, but takes Print's token colours and the bundle's own grammar
    // names: the token roles and the diff/patch aliases added for the screen must not change the
    // export. Built lazily, on the first export.
    val printMarkwon: Markwon by lazy { buildPrintMarkwon(context) }

    /** The instance whose code colours match [colorScheme] — the reader's entry point. */
    fun markwonFor(colorScheme: PilcrowColorScheme): Markwon =
        if (colorScheme === LightColorScheme) lightMarkwon else markwon

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
 * through [MarkwonRenderer.markwonFor] (or [MarkwonRenderer.printMarkwon] for the PDF).
 */
/**
 * The PDF export's instance: Dark's code panel with Print's token colours, and the bundle's own grammar
 * names (no diff/patch alias), so nothing added for the screen reaches the export (M-132).
 */
internal fun buildPrintMarkwon(context: Context): Markwon = buildPilcrowMarkwon(
    context,
    DarkColorScheme.copy(codeSyntax = PrintColorScheme.codeSyntax),
    GrammarLocatorDef(),
)

internal fun buildPilcrowMarkwon(
    context: Context,
    colorScheme: PilcrowColorScheme = DarkColorScheme,
    grammarLocator: GrammarLocator = AliasGrammarLocator(),
): Markwon {
    // Prism4j over the kapt-generated grammars, plus the diff/patch aliases unless told otherwise
    val prism4j = Prism4j(grammarLocator)

    // Calculate body font size in pixels for JLatexMath (17sp)
    val baseFontSizePx = with(context.resources.displayMetrics) {
        (PilcrowTypography.PROSE_BODY_FONT_SIZE_SP * density)
    }

    val builder = Markwon.builder(context)
        // Core markdown parsing (CommonMark)
        .usePlugin(CorePlugin.create())
        // M-119: CorePlugin's ListItem visitor MUTATES the parsed tree as it renders, advancing
        // each ordered list's start number by one per item. The reader re-renders the same Node
        // objects on every bind, so the numbers climbed. This plugin snapshots and restores them.
        // Registration order is IRRELEVANT here: Markwon runs every plugin's beforeRender, THEN
        // node.accept(visitor), THEN every plugin's afterRender (MarkwonImpl.render), so the
        // mutation always lands between the two phases whatever the order. Placed next to
        // CorePlugin because that is the plugin it compensates for, not because it must be.
        .usePlugin(OrderedListRebindPlugin())
        // M-164: heading sizes H1–H6 relative to the body size.
        .usePlugin(HeadingScalePlugin())
        // Render leading `---…---` as a styled `yaml` code block via a custom
        // BlockParser (no source mutation → char offsets stay aligned with the editor).
        .usePlugin(FrontmatterPlugin())
        // Footnote definitions: `[^label]: body` becomes a container block instead of a stray
        // paragraph (or, for a single-token body, a bogus link reference definition).
        .usePlugin(FootnotePlugin())
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
        // mhchem \ce{…} shim: rewrites math-node latex payloads in beforeRender so chemistry
        // renders instead of raw-dumping the whole equation (JLaTeXMath has no mhchem).
        // Registered with JLatexMathPlugin — without it there are no math nodes to rewrite.
        builder.usePlugin(CeMacroShimPlugin())
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
        // Per-language syntax highlighting, coloured from the scheme's code tokens
        .usePlugin(
            SyntaxHighlightPlugin.create(
                prism4j,
                PilcrowTheme(colorScheme),
            ),
        )
        // Must follow SyntaxHighlightPlugin: overrides its single code background with separate
        // inline and fenced-block backgrounds.
        .usePlugin(CodeSurfacePlugin(colorScheme))
        // Limited HTML (only safe tags: <br>, <sub>, <sup>, <details>)
        .usePlugin(HtmlPlugin.create())
        .build()
}
