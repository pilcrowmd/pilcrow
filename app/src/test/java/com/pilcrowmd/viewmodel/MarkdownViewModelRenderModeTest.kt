// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.model.RenderMode
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.storage.LocalStorageManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Render-mode state: extension defaults, per-URI override persistence, re-derivation on
 * EVERY document-identity change (load + Save-As adoption — the review's desync case), TOC
 * gating, and plain-text search. Mirrors [MarkdownViewModelSaveAsTest]'s setup (real Robolectric
 * DataStore + inline fake repository); assertions AWAIT their expected state because the render-
 * mode flows cross real dispatchers (DataStore on IO, headings/search on Default), so a single
 * main-looper idle is not a completion barrier.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelRenderModeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("rendermode_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        storageScope.cancel()
    }

    private class FakeRepo(private val readContent: String) : FileRepository {
        val capturedSaves = linkedMapOf<Uri, String>()
        override suspend fun readFile(uri: Uri) = Result.success(readContent)
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> {
            capturedSaves[uri] = content
            return Result.success(Unit)
        }
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment ?: "doc.md"
        override fun hasPersistedPermission(uri: Uri): Boolean = true
        override fun hasPersistedWritePermission(uri: Uri): Boolean = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    private fun vmWith(content: String): MarkdownViewModel = vmWith(FakeRepo(content))

    private fun vmWith(repo: FakeRepo): MarkdownViewModel {
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = repo,
            storage = storage,
            parseHeadingsUseCase = parseHeadings,
            searchUseCase = SearchMarkdownUseCase(parseHeadings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
        )
    }

    /**
     * Await [expected] from [actual], idling the main looper between polls so viewModelScope
     * continuations that resumed from IO/Default dispatchers can land. Fails with a plain
     * assertion after the deadline.
     *
     * **What makes a call to this a real barrier.** It blocks only while [actual] differs from
     * [expected], so it is a barrier for exactly one thing: the emission that changes the value.
     * Two consequences, and the difference between them matters:
     *  - Awaiting a value that is ALREADY current (typically a flow's initial value) is vacuous — it
     *    returns before anything has happened and the test passes even if the work never ran. That is
     *    the PR #50 defect; two instances of it were fixed on PR #56 ([saveAsTxtNameReDerivesToPlain]
     *    and [togglePersistsOverrideAcrossReload], both of which awaited the initial `MARKDOWN`).
     *  - Awaiting a value that DIFFERS from the current one is sound for that emission, but it says
     *    nothing about async work the same coroutine does *afterwards*. `toggleRenderMode` emits
     *    `_renderMode` first and only then writes DataStore, refreshes headings and re-runs search —
     *    so `awaitValue(<the other mode>)` genuinely barriers the flip, and a test that also depends
     *    on the follow-on work needs a second, stronger barrier on that work (the storage poll in
     *    [toggleBackStoresExplicitPlain], `searchMatches` in [activeSearchReRunsOnRenderModeToggle],
     *    `capturedSaves` in [roundTripAfterTogglingIsByteIdentical]). Those pairs are deliberate; do
     *    not "simplify" them to a headings await — for a `.txt` in PLAIN mode headings are empty,
     *    which is the initial value, i.e. it would reintroduce the vacuous form above.
     */
    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (actual() == expected) return
            Thread.sleep(POLL_MS)
        }
        assertEquals(message, expected, actual())
    }

    /**
     * Load [uri] and BLOCK until the document is actually open. Waiting on `renderMode` instead is
     * unsound: its initial value is already MARKDOWN, so an `awaitValue(MARKDOWN)` after loading a
     * `.md` returns instantly without the load having happened — a later `saveActiveDocumentAs`
     * then sees a null document and returns early (CI caught exactly this; the local run happened
     * to win the race). The document identity only becomes non-null when loadDocument completes,
     * so it is a real barrier.
     */
    private fun MarkdownViewModel.loadAndAwait(uri: Uri) {
        loadFile(uri)
        awaitValue(uri, "document loaded") { currentDocument.value?.uri }
    }

    private val txtUri = Uri.parse("content://test/notes.txt")
    private val mdUri = Uri.parse("content://test/readme.md")

    // --- extension defaults ---

    @Test
    fun txtDefaultsToPlainAndToggleIsAvailable() {
        val vm = vmWith("# raw")
        vm.loadAndAwait(txtUri)
        awaitValue(RenderMode.PLAIN, "txt defaults to plain") { vm.renderMode.value }
        assertTrue("toggle offered for .txt", vm.plainToggleAvailable.value)
    }

    @Test
    fun mdDefaultsToMarkdownAndToggleIsAbsent() {
        val vm = vmWith("# heading")
        vm.loadAndAwait(mdUri)
        awaitValue(1, "md load extracts headings") { vm.headings.value.size }
        assertEquals(RenderMode.MARKDOWN, vm.renderMode.value)
        assertFalse("no toggle for .md", vm.plainToggleAvailable.value)
    }

    // --- toggle + persistence ---

    @Test
    fun togglePersistsOverrideAcrossReload() {
        val vm = vmWith("# raw")
        vm.loadAndAwait(txtUri)
        awaitValue(RenderMode.PLAIN, "loaded plain") { vm.renderMode.value }
        vm.toggleRenderMode()
        awaitValue(RenderMode.MARKDOWN, "toggled to markdown") { vm.renderMode.value }
        awaitValue(RenderMode.MARKDOWN, "override persisted") {
            runBlocking { storage.getRenderModeOverride(txtUri) }
        }

        // A fresh ViewModel over the same storage re-derives the remembered mode.
        val vm2 = vmWith("# raw")
        vm2.loadAndAwait(txtUri)
        // Second tautology of the same family: vm2's renderMode STARTS at
        // MARKDOWN, so `awaitValue(MARKDOWN)` would return instantly — passing even if the DataStore
        // read never happened or returned the wrong value, which is the entire point of this test.
        // Headings are the real barrier again: refreshHeadings runs after applyDerivedRenderMode, and
        // in the remembered MARKDOWN mode "# raw" parses to exactly one heading (in PLAIN it would be
        // zero, so this also fails loudly if the override were NOT restored).
        awaitValue(1, "reload finished deriving in the remembered MARKDOWN mode") { vm2.headings.value.size }
        awaitValue(RenderMode.MARKDOWN, "remembered across reload") { vm2.renderMode.value }
    }

    @Test
    fun toggleBackStoresExplicitPlain() {
        val vm = vmWith("# raw")
        vm.loadAndAwait(txtUri)
        awaitValue(RenderMode.PLAIN, "loaded plain") { vm.renderMode.value }
        vm.toggleRenderMode()
        awaitValue(RenderMode.MARKDOWN, "first toggle") { vm.renderMode.value }
        vm.toggleRenderMode()
        awaitValue(RenderMode.PLAIN, "second toggle") { vm.renderMode.value }
        // Explicit in both directions: the override is stored, not cleared.
        awaitValue(RenderMode.PLAIN, "explicit PLAIN override stored") {
            runBlocking { storage.getRenderModeOverride(txtUri) }
        }
    }

    // --- re-derivation on Save-As identity change ---

    @Test
    fun saveAsMdNameReDerivesToMarkdown() {
        val vm = vmWith("# raw")
        vm.loadAndAwait(txtUri)
        // This direction is already sound: PLAIN is NOT the flow's initial value, so awaiting it is
        // a genuine barrier on the load's derivation. That asymmetry is why only the .txt-direction
        // sibling above could flake — keep the reasoning visible so neither drifts back.
        awaitValue(RenderMode.PLAIN, "loaded plain") { vm.renderMode.value }

        vm.saveActiveDocumentAs(Uri.parse("content://test/copy.md"))
        awaitValue(RenderMode.MARKDOWN, "adopted .md identity renders as Markdown") { vm.renderMode.value }
        assertFalse(vm.plainToggleAvailable.value)
    }

    @Test
    fun saveAsTxtNameReDerivesToPlain() {
        val vm = vmWith("# heading")
        vm.loadAndAwait(mdUri)
        // Barrier, not decoration. `loadAndAwait` only proves the DOCUMENT was emitted, and
        // loadDocument emits it BEFORE its own applyDerivedRenderMode (which suspends on DataStore).
        // Asserting `renderMode == MARKDOWN` here would be the PR #50 tautology all over again —
        // MARKDOWN is the flow's initial value, so it passes whether or not the load derivation ran.
        // Headings are the real barrier: refreshHeadings runs strictly AFTER applyDerivedRenderMode
        // in the same coroutine, and an empty list is impossible once it has. Without this, the
        // load's derivation can resume AFTER the Save-As derivation below and overwrite PLAIN with
        // MARKDOWN — which is exactly how CI failed on a docs-only commit while 8 local reruns passed.
        awaitValue(1, "md load finished deriving (headings extracted)") { vm.headings.value.size }
        assertEquals("md loads as markdown", RenderMode.MARKDOWN, vm.renderMode.value)

        vm.saveActiveDocumentAs(Uri.parse("content://test/copy.txt"))
        awaitValue(RenderMode.PLAIN, "adopted .txt identity renders plain") { vm.renderMode.value }
        assertTrue(vm.plainToggleAvailable.value)
    }

    // --- TOC gating ---

    @Test
    fun headingsAreEmptyInPlainModeAndAppearAfterToggle() {
        val vm = vmWith("# Alpha\n\n## Beta")
        vm.loadAndAwait(txtUri)
        awaitValue(RenderMode.PLAIN, "loaded plain") { vm.renderMode.value }
        assertEquals("plain mode has no headings", 0, vm.headings.value.size)

        vm.toggleRenderMode()
        awaitValue(2, "markdown mode extracts headings") { vm.headings.value.size }

        vm.toggleRenderMode()
        awaitValue(0, "back to plain clears headings") { vm.headings.value.size }
    }

    // --- search over literal text ---

    @Test
    fun plainSearchMatchesLiteralTextIncludingMarkdownSyntax() {
        val vm = vmWith("# alpha\nalpha beta")
        vm.loadAndAwait(txtUri)
        awaitValue(RenderMode.PLAIN, "loaded plain") { vm.renderMode.value }

        vm.updateSearchQuery("alpha")
        awaitValue(2, "both literal occurrences found") { vm.searchMatches.value.size }
        assertEquals("single chunk → adapter position 0", 0, vm.searchMatches.value[0].adapterPosition)
        assertEquals(0, vm.searchMatches.value[0].occurrenceInBlock)
        assertEquals(1, vm.searchMatches.value[1].occurrenceInBlock)

        // "# alpha" is literal text in plain mode — findable, unlike in markdown search.
        vm.updateSearchQuery("# alpha")
        awaitValue(1, "markdown syntax is searchable literally") { vm.searchMatches.value.size }
    }

    @Test
    fun plainSearchPositionsFollowChunks() {
        val head = (1..220).map { "line $it" }
        val content = (head + "" + listOf("needle here")).joinToString("\n")
        val vm = vmWith(content)
        vm.loadAndAwait(txtUri)
        awaitValue(RenderMode.PLAIN, "loaded plain") { vm.renderMode.value }

        vm.updateSearchQuery("needle")
        awaitValue(1, "needle found") { vm.searchMatches.value.size }
        assertEquals("match sits in the second chunk", 1, vm.searchMatches.value[0].adapterPosition)
    }

    @Test
    fun plainSearchCountsOverlappingMatchesLikeMarkdownSearch() {
        // The highlighter and the markdown scanner both step by 1 (overlaps counted);
        // plain search must match, or counts and focus ordinals diverge ("aa" in "aaa" = 2).
        val vm = vmWith("aaa")
        vm.loadAndAwait(txtUri)
        awaitValue(RenderMode.PLAIN, "loaded plain") { vm.renderMode.value }

        vm.updateSearchQuery("aa")
        awaitValue(2, "overlapping matches counted") { vm.searchMatches.value.size }
    }

    @Test
    fun activeSearchReRunsOnRenderModeToggle() {
        // Matches computed against the plain tree are stale after a toggle — the
        // literal "# alpha" exists in plain text but not in the rendered-Markdown visible text.
        val vm = vmWith("# alpha\nalpha beta")
        vm.loadAndAwait(txtUri)
        awaitValue(RenderMode.PLAIN, "loaded plain") { vm.renderMode.value }
        vm.updateSearchQuery("# alpha")
        awaitValue(1, "literal match in plain mode") { vm.searchMatches.value.size }

        vm.toggleRenderMode()
        awaitValue(RenderMode.MARKDOWN, "toggled") { vm.renderMode.value }
        awaitValue(0, "stale plain matches re-run against the markdown tree") { vm.searchMatches.value.size }
    }

    // --- Safeguard 2: the toggle is viewing-only — a save after toggling is byte-identical ---

    @Test
    fun roundTripAfterTogglingIsByteIdentical() {
        // CRLF + trailing spaces + Markdown-significant chars: the hostile round-trip fixture.
        val source = "# raw heading\r\n**bold?**  \r\n\r\n| a | b |\r\n"
        val repo = FakeRepo(source)
        val vm = vmWith(repo)
        vm.loadAndAwait(txtUri)
        awaitValue(RenderMode.PLAIN, "loaded plain") { vm.renderMode.value }

        vm.toggleRenderMode()
        awaitValue(RenderMode.MARKDOWN, "toggled") { vm.renderMode.value }
        vm.toggleRenderMode()
        awaitValue(RenderMode.PLAIN, "toggled back") { vm.renderMode.value }

        vm.saveFile()
        awaitValue(1, "save captured") { repo.capturedSaves.size }
        assertEquals("saved bytes identical to source", source, repo.capturedSaves[txtUri])
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
    }
}
