// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.net.Uri
import android.util.Log
import android.view.View
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.HeadingAnchors
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.MarkwonConfiguration
import java.util.Locale

private const val TAG = "ReaderLinkResolver"

private val NOTE_EXTENSIONS = listOf(".md", ".markdown", ".txt")

/**
 * Anchor → adapter position for the document on screen (M-159). Set on the reader's RecyclerView as
 * `R.id.anchor_targets`, and refilled whenever the document is (re)parsed, so a link tap — which only
 * has the tapped view — can find the heading it names.
 */
class AnchorTargets {
    var byAnchor: Map<String, Int> = emptyMap()
}

/**
 * M-160: what a tap on a link to another note does, set on the reader's RecyclerView as
 * `R.id.note_link_handler`. Returns false to leave the link to today's behaviour.
 */
fun interface NoteLinkHandler {
    fun onLink(destination: String): Boolean
}

/**
 * Decides what a tapped link does in the reader (M-159, M-173). No tap may leave the app wrongly or crash it.
 *
 * - `#heading`: jump to that heading in this document, through the footnote jump. Never delegated —
 *   Markwon's default would turn it into `https://#heading` and open a browser. A link that names no
 *   heading, or a view with no reader around it (the PDF export), does nothing.
 * - `file:`: does nothing. Handing the platform a `file://` URI throws `FileUriExposedException`.
 * - Any other scheme-less link goes first to the screen's [NoteLinkHandler] (M-160: a note or file in
 *   an added folder). The handler declines a web address written without a scheme: one that starts
 *   with `www.`, or whose first segment has a dot and is followed by a path that does not end in `.md`
 *   or `.markdown` (`example.com/page`, see `NoteLinkPaths`). When there is no handler, or it declines:
 *   a relative path to another note (`./a.md`, `../a`, `/a`, `a.md`) does nothing, and anything else
 *   keeps Markwon's behaviour (it adds `https://`, so the browser opens).
 * - Everything else goes to [delegate], and a refusal from the platform is logged, not thrown.
 */
internal class ReaderLinkResolver(private val delegate: LinkResolver = LinkResolverDef()) : LinkResolver {

    override fun resolve(view: View, link: String) {
        if (link.startsWith("#")) {
            val targets = (view.findRecyclerViewAncestor()?.getTag(R.id.anchor_targets) as? AnchorTargets)
            HeadingAnchors.blockIndexOf(targets?.byAnchor ?: emptyMap(), link)?.let(view::jumpToBlock)
            return
        }
        val uri = Uri.parse(link)
        val scheme = uri.scheme
        if (scheme.equals("file", ignoreCase = true)) return
        if (scheme.isNullOrEmpty()) {
            val handler = view.findRecyclerViewAncestor()?.getTag(R.id.note_link_handler) as? NoteLinkHandler
            if (handler?.onLink(link) == true) return
            if (isRelativeNotePath(link)) return
        }
        @Suppress("TooGenericExceptionCaught") // the platform throws unchecked for unresolvable intents
        try {
            delegate.resolve(view, link)
        } catch (e: RuntimeException) {
            // The class only: the link, and an intent exception's message that quotes it, are user content.
            Log.w(TAG, "could not open link: ${e.javaClass.simpleName}")
        }
    }

    private fun isRelativeNotePath(link: String): Boolean {
        if (link.startsWith("./") || link.startsWith("../") || link.startsWith("/")) return true
        val path = link.substringBefore('#').substringBefore('?').lowercase(Locale.ROOT)
        return NOTE_EXTENSIONS.any { path.endsWith(it) }
    }
}

/** Installs [ReaderLinkResolver] as the link resolver. */
internal class ReaderLinkResolverPlugin : AbstractMarkwonPlugin() {
    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
        builder.linkResolver(ReaderLinkResolver())
    }
}
