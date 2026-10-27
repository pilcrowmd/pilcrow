// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

import com.pilcrowmd.domain.markdown.HeadingAnchors
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import org.commonmark.node.Link
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * M-160: the notes-folder spec section 5 cases, against the exact section 5 layout held in memory.
 *
 * Not in this file:
 * - 15 (list order, no hidden or empty folders) belongs to the folder listing test (PR1).
 * - 16 (unsaved-changes prompt first), 17 (Back to the block, K10) and 25 (note opened from Recents)
 *   belong to the reader wiring (PR4).
 * - 24 (symlink pointing outside the folder) is N/A on Android: shared storage and SAF providers cannot
 *   hold a symlink in the folder (spec section 6, case 24).
 */
class NoteLinkResolverTest {

    private fun file(folder: String, name: String) = DirEntry("$folder/$name", name, isFolder = false)
    private fun folder(parent: String, name: String) = DirEntry("$parent/$name", name, isFolder = true)

    /** The section 5 layout. Música.md is stored NFD; Plan.MD exists only when [withUpperPlan] (case 11). */
    private fun layout(withUpperPlan: Boolean = false): Map<String, List<DirEntry>> = mapOf(
        ROOT to listOf(
            file(ROOT, "index.md"),
            file(ROOT, "Note 2.md"),
            file(ROOT, "Note 10.md"),
            folder(ROOT, "projects"),
            file(ROOT, MUSICA_NFD),
            folder(ROOT, ".obsidian"),
            folder(ROOT, "empty"),
        ),
        PROJECTS to listOfNotNull(
            file(PROJECTS, "plan.md"),
            file(PROJECTS, "Plan.MD").takeIf { withUpperPlan },
            file(PROJECTS, "img.png"),
        ),
        "$ROOT/.obsidian" to listOf(file("$ROOT/.obsidian", "config.md")),
        "$ROOT/empty" to emptyList(),
        OTHER to listOf(file(OTHER, "secret.md")),
    )

    private fun resolver(withUpperPlan: Boolean = false) = NoteLinkResolver(DirLister { layout(withUpperPlan)[it] })

    /** From index.md, whose folder chain is the root alone. */
    private fun fromIndex(link: String, withUpperPlan: Boolean = false) =
        resolver(withUpperPlan).resolve(ROOT, listOf(ROOT), link)

    /** From projects/plan.md. */
    private fun fromPlan(link: String) = resolver().resolve(ROOT, listOf(ROOT, PROJECTS), link)

    private fun note(path: String, heading: String? = null) =
        LinkTarget.Note("$ROOT/$path", path.substringAfterLast('/'), heading)

    @Test
    fun case01_relative_subfolder_opens_plan() {
        assertEquals(note("projects/plan.md"), fromIndex("projects/plan.md"))
    }

    @Test
    fun case02_parent_from_plan_opens_index() {
        assertEquals(note("index.md"), fromPlan("../index.md"))
    }

    @Test
    fun case03_root_link_from_plan_opens_index() {
        assertEquals(note("index.md"), fromPlan("/index.md"))
    }

    @Test
    fun case04_heading_fragment_opens_plan_at_the_heading() {
        val target = fromIndex("projects/plan.md#next-steps")
        assertEquals(note("projects/plan.md", "next-steps"), target)
        val heading = (target as LinkTarget.Note).heading
        assertEquals(0, HeadingAnchors.blockIndexOf(planTargets(), "#$heading"))
        // The heading stays raw; blockIndexOf decodes it exactly once.
        val encoded = fromIndex("projects/plan.md#next%2Dsteps")
        assertEquals(note("projects/plan.md", "next%2Dsteps"), encoded)
        assertEquals(0, HeadingAnchors.blockIndexOf(planTargets(), "#${(encoded as LinkTarget.Note).heading}"))
    }

    @Test
    fun case05_missing_heading_opens_plan_at_top() {
        val target = fromIndex("projects/plan.md#no-such-heading")
        assertEquals(note("projects/plan.md", "no-such-heading"), target)
        val heading = (target as LinkTarget.Note).heading
        assertNull(HeadingAnchors.blockIndexOf(planTargets(), "#$heading"))
    }

    @Test
    fun case06_percent_encoded_space() {
        assertEquals(note("Note 2.md"), fromIndex("Note%202.md"))
    }

    @Test
    fun case07_angle_bracket_target() {
        assertEquals(note("Note 2.md"), fromIndex("<Note 2.md>"))
    }

    @Test
    fun case08_case_fallback() {
        assertEquals(note("projects/plan.md"), fromIndex("projects/PLAN.md"))
    }

    @Test
    fun case09_extension_added() {
        assertEquals(note("projects/plan.md"), fromIndex("projects/plan"))
    }

    @Test
    fun case10_missing_note_not_found() {
        assertEquals(LinkTarget.NotFound("missing.md"), fromIndex("missing.md"))
    }

    @Test
    fun case11_two_case_insensitive_matches_not_found() {
        assertEquals(LinkTarget.NotFound("PLAN.md"), fromIndex("projects/PLAN.md", withUpperPlan = true))
    }

    @Test
    fun case12_outside_the_folder_not_found() {
        assertEquals(LinkTarget.NotFound("secret.md"), fromIndex("../Other/secret.md"))
    }

    @Test
    fun case13_non_note_file_handed_to_the_system() {
        assertEquals(LinkTarget.OtherFile("$PROJECTS/img.png", "img.png"), fromIndex("projects/img.png"))
    }

    @Test
    fun case14_web_link_as_today() {
        assertEquals(LinkTarget.Web("https://example.com"), fromIndex("https://example.com"))
    }

    @Test
    fun case18_raw_spaces_in_brackets_link() {
        val found = BareNoteLinks.find("see [x](Note 2.md) now")
        assertEquals(listOf(BareLink(4, 18, "x", "Note 2.md")), found)
        assertEquals(note("Note 2.md"), fromIndex(found.single().destination))
    }

    @Test
    fun case19_raw_spaces_missing_note_stays_plain_text() {
        val found = BareNoteLinks.find("[x](No such note.md)")
        assertEquals(listOf(BareLink(0, 20, "x", "No such note.md")), found)
        assertEquals(LinkTarget.NotFound("No such note.md"), fromIndex(found.single().destination))
    }

    @Test
    fun case20_bare_note_name_shown_as_file_name() {
        val found = BareNoteLinks.find("about <Note 2.md> here")
        assertEquals(listOf(BareLink(6, 17, "Note 2.md", "Note 2.md")), found)
        assertEquals(note("Note 2.md"), fromIndex(found.single().destination))
    }

    @Test
    fun case21_composed_name_finds_decomposed_file() {
        assertEquals(LinkTarget.Note("$ROOT/$MUSICA_NFD", MUSICA_NFD, null), fromIndex(MUSICA_NFC))
    }

    @Test
    fun case22_percent_encoded_accent_finds_decomposed_file() {
        assertEquals(LinkTarget.Note("$ROOT/$MUSICA_NFD", MUSICA_NFD, null), fromIndex("M%C3%BAsica.md"))
    }

    @Test
    fun case23_title_does_not_change_the_target() {
        val parsed = Parser.builder().build().parse("[x](projects/plan.md \"Plan\")")
        val link = parsed.firstChild.firstChild as Link
        assertEquals(note("projects/plan.md"), fromIndex(link.destination))
        val bare = BareNoteLinks.find("[x](projects/plan.md \"Plan\")").single()
        assertEquals("projects/plan.md", bare.destination)
        assertEquals(note("projects/plan.md"), fromIndex(bare.destination))
    }

    @Test
    fun case26_folder_link_not_found() {
        assertEquals(LinkTarget.NotFound("projects"), fromIndex("projects"))
    }

    // --- extras ---

    private fun flat(vararg folders: Pair<String, List<DirEntry>>) = NoteLinkResolver(DirLister { folders.toMap()[it] })

    @Test
    fun parent_of_the_root_is_outside_even_when_the_name_exists_there() {
        assertEquals(LinkTarget.NotFound("index.md"), fromIndex("../index.md"))
    }

    @Test
    fun plus_is_never_a_space() {
        val resolver = flat(ROOT to listOf(file(ROOT, "a+b.md"), file(ROOT, "a b.md")))
        assertEquals(note("a+b.md"), resolver.resolve(ROOT, listOf(ROOT), "a+b.md"))
    }

    @Test
    fun two_folders_differing_only_in_case_are_ambiguous() {
        val resolver = flat(
            ROOT to listOf(folder(ROOT, "Projects"), folder(ROOT, "projects")),
            "$ROOT/Projects" to listOf(file("$ROOT/Projects", "x.md")),
            PROJECTS to listOf(file(PROJECTS, "x.md")),
        )
        assertEquals(LinkTarget.NotFound("x.md"), resolver.resolve(ROOT, listOf(ROOT), "PROJECTS/x.md"))
    }

    @Test
    fun a_folder_never_matches_the_last_segment() {
        val resolver = flat(ROOT to listOf(folder(ROOT, "old.md")), "$ROOT/old.md" to emptyList())
        assertEquals(LinkTarget.NotFound("old.md"), resolver.resolve(ROOT, listOf(ROOT), "old.md"))
    }

    @Test
    fun the_resolver_never_strips_a_title_shaped_ending() {
        val resolver = flat(ROOT to listOf(file(ROOT, "Plan 'v2'.md"), file(ROOT, "Plan.md")))
        assertEquals(note("Plan 'v2'.md"), resolver.resolve(ROOT, listOf(ROOT), "Plan 'v2'"))
    }

    @Test
    fun folder_segment_uses_the_case_fallback() {
        assertEquals(note("projects/plan.md"), fromIndex("Projects/plan.md"))
    }

    @Test
    fun dot_segments_and_doubled_slashes_are_ignored() {
        assertEquals(note("index.md"), fromIndex("./index.md"))
        assertEquals(note("index.md"), fromIndex("//index.md"))
        assertEquals(note("index.md"), fromIndex("projects/../index.md"))
    }

    @Test
    fun malformed_escape_is_kept_as_typed() {
        assertEquals(LinkTarget.NotFound("%zz.md"), fromIndex("%zz.md"))
        assertEquals(LinkTarget.NotFound("50%"), fromIndex("50%"))
    }

    @Test
    fun scheme_handling() {
        assertEquals(LinkTarget.Web("HTTPS://x"), fromIndex("HTTPS://x"))
        assertEquals(LinkTarget.Web("mailto:a@b.c"), fromIndex("mailto:a@b.c"))
        assertEquals(LinkTarget.Unhandled, fromIndex("tel:1"))
        assertEquals(LinkTarget.Unhandled, fromIndex("content://x/index.md"))
    }

    @Test
    fun same_note_heading_link_is_unhandled() {
        assertEquals(LinkTarget.Unhandled, fromIndex("#top"))
    }

    @Test
    fun empty_fragment_is_no_heading() {
        assertEquals(note("index.md"), fromIndex("index.md#"))
    }

    @Test
    fun trailing_slash_or_dot_not_found() {
        assertEquals(LinkTarget.NotFound("projects"), fromIndex("projects/"))
        assertEquals(LinkTarget.NotFound("index.md"), fromIndex("index.md/."))
    }

    @Test
    fun hidden_entries_never_match() {
        assertEquals(LinkTarget.NotFound("config.md"), fromIndex(".obsidian/config.md"))
    }

    @Test
    fun unreadable_folder_not_found() {
        val resolver = NoteLinkResolver { if (it == PROJECTS) null else layout()[it] }
        assertEquals(LinkTarget.NotFound("plan.md"), resolver.resolve(ROOT, listOf(ROOT), "projects/plan.md"))
    }

    @Test
    fun random_destinations_never_throw() {
        val pieces = listOf(
            "a", "Z", ".", "..", "/", "#", "%", "%2", "%zz", "%C3", "%BA", "<", ">", "\"", "'", " ", "+",
            ":", "http", "md", ".md", "projects", "plan", "ú", "ú", "😀", "\uD800", "\n",
        )
        val random = Random(SEED)
        val resolver = resolver()
        repeat(FUZZ_ROUNDS) {
            val destination = (0 until random.nextInt(MAX_PIECES)).joinToString("") { pieces.random(random) }
            val chain = if (random.nextBoolean()) listOf(ROOT) else listOf(ROOT, PROJECTS)
            assertNotNull(destination, resolver.resolve(ROOT, chain, destination))
            BareNoteLinks.find(destination)
        }
    }

    private fun planTargets() =
        HeadingAnchors.targets(ParseMarkdownHeadingsUseCase().parseDocument("## Next steps\n\nBody.\n")!!)

    private companion object {
        const val ROOT = "Notes"
        const val PROJECTS = "Notes/projects"
        const val OTHER = "Other"
        const val MUSICA_NFD = "Música.md"
        const val MUSICA_NFC = "Música.md"
        const val SEED = 160
        const val FUZZ_ROUNDS = 2000
        const val MAX_PIECES = 16
    }
}
