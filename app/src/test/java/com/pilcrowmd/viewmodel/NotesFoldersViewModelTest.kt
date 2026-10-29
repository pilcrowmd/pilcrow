// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import androidx.lifecycle.viewModelScope
import com.pilcrowmd.domain.folders.Ago
import com.pilcrowmd.domain.folders.FolderEntry
import com.pilcrowmd.domain.folders.Mark
import com.pilcrowmd.domain.folders.NoteEntry
import com.pilcrowmd.domain.folders.NoteMarks
import com.pilcrowmd.domain.folders.SeenRecord
import com.pilcrowmd.repository.AddFolderResult
import com.pilcrowmd.repository.DocumentStats
import com.pilcrowmd.repository.FolderListing
import com.pilcrowmd.repository.NotesFolder
import com.pilcrowmd.repository.NotesFolderRepository
import com.pilcrowmd.storage.NoteMarksStore
import com.pilcrowmd.storage.NotesFolderStore
import com.pilcrowmd.storage.StoredFolder
import com.pilcrowmd.storage.expandedKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * M-162b: the folder list's state (spec sections 2 and 3).
 *
 * Every assertion first waits for a value the ViewModel publishes only once the work has run: a
 * listing (which starts as Loading or NoFolders), a tick that moved, a stored value that changed.
 * The fake repository lists on another thread, so nothing completes before the next line by accident.
 */
@RunWith(RobolectricTestRunner::class)
class NotesFoldersViewModelTest {

    private class FakeRepo : NotesFolderRepository {
        override val folders = MutableStateFlow<List<NotesFolder>>(emptyList())
        val listings = ConcurrentHashMap<Uri, FolderEntry>()
        val watchRequests = CopyOnWriteArrayList<Pair<Uri, Set<String>>>()
        val changeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        /** Answers add() instead of the in-memory list when set. */
        var addAnswer: ((Uri) -> AddFolderResult)? = null

        /** Answers findAgain(); a real repository also moves the tick, which a test does here. */
        var findAgainAnswer: suspend (Uri, Uri) -> AddFolderResult = { _, _ -> AddFolderResult.NotAFolder }

        /** The next listing of this folder waits in [held] until the test resumes it. */
        @Volatile var holdNext: Uri? = null
        val held = ConcurrentHashMap<Uri, Continuation<Unit>>()

        @Volatile var heldKeepsStartListing = false

        override suspend fun add(treeUri: Uri): AddFolderResult {
            addAnswer?.let { return it(treeUri) }
            folders.value.firstOrNull { it.treeUri == treeUri }?.let { return AddFolderResult.AlreadyListed(it) }
            val folder = NotesFolder(treeUri, treeUri.lastPathSegment.orEmpty(), null, available = true)
            folders.update { it + folder }
            return AddFolderResult.Added(folder)
        }

        /**
         * False: a removal reaches [folders] only when the test calls [publishRemovals], like the real
         * list, which the ViewModel sees a moment after the store has changed (it hops threads).
         */
        @Volatile var removalsShowAtOnce = true
        private val removed = CopyOnWriteArrayList<Uri>()

        override suspend fun remove(treeUri: Uri) {
            removed += treeUri
            if (removalsShowAtOnce) publishRemovals()
        }

        fun publishRemovals() {
            folders.update { list -> list.filterNot { it.treeUri in removed } }
        }

        override suspend fun findAgain(old: Uri, picked: Uri): AddFolderResult = findAgainAnswer(old, picked)

        val listCalls = AtomicInteger()

        override suspend fun list(treeUri: Uri): FolderListing {
            listCalls.incrementAndGet()
            if (holdNext == treeUri) {
                holdNext = null
                // Not cancellable, like a provider call already under way: when the test resumes it,
                // it returns its listing even though the ViewModel has moved on. By default that is
                // the folder as it is then; [heldKeepsStartListing] makes it the folder as it was.
                val atStart = answer(treeUri)
                suspendCoroutine { held[treeUri] = it }
                return if (heldKeepsStartListing) atStart else answer(treeUri)
            }
            return withContext(Dispatchers.IO) { answer(treeUri) }
        }

        private fun answer(treeUri: Uri): FolderListing =
            listings[treeUri]?.let { FolderListing.Listed(it) } ?: FolderListing.Unavailable

        override fun changes(treeUri: Uri, folderIds: Set<String>): Flow<Unit> {
            watchRequests += treeUri to folderIds
            return changeEvents
        }

        val stats = ConcurrentHashMap<Uri, DocumentStats>()
        val statsAsked = CopyOnWriteArrayList<Uri>()

        override fun noteUri(treeUri: Uri, documentId: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

        override fun documentIdOf(documentUri: Uri): String? =
            runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull()

        override suspend fun noteStats(documentUri: Uri): DocumentStats? = withContext(Dispatchers.IO) {
            statsAsked += documentUri
            stats[documentUri]
        }
    }

    private class MemoryMarks : NoteMarksStore {
        val records = ConcurrentHashMap<Uri, Map<String, SeenRecord>>()

        /** The thread of every call: the real store decodes and encodes every record in it. */
        val threads = CopyOnWriteArrayList<Thread>()

        override suspend fun get(treeUri: Uri): Map<String, SeenRecord>? {
            threads += Thread.currentThread()
            return records[treeUri]
        }
        override suspend fun put(treeUri: Uri, records: Map<String, SeenRecord>) {
            threads += Thread.currentThread()
            this.records[treeUri] = records
        }
        override suspend fun drop(treeUri: Uri) {
            threads += Thread.currentThread()
            records.remove(treeUri)
        }
    }

    private class MemoryStore : NotesFolderStore {
        override val folders = MutableStateFlow<List<StoredFolder>>(emptyList())
        override val selectedFolder = MutableStateFlow<Uri?>(null)
        override val expanded = MutableStateFlow<Set<String>>(emptySet())
        override suspend fun setFolders(folders: List<StoredFolder>) {
            this.folders.value = folders
        }
        override suspend fun setSelectedFolder(uri: Uri?) {
            selectedFolder.value = uri
        }
        override suspend fun setExpanded(keys: Set<String>) {
            expanded.value = keys
        }
    }

    private val repo = FakeRepo()
    private val store = MemoryStore()
    private val marksStore = MemoryMarks()

    @Volatile private var now = 10_000_000L

    private val a = Uri.parse("content://docs/tree/A")
    private val b = Uri.parse("content://docs/tree/B")

    private fun note(name: String) = NoteEntry("$name-id", name, lastModified = null, size = null)

    private fun rootOf(uri: Uri, vararg notes: String) = FolderEntry(
        documentId = "${uri.lastPathSegment}-root",
        name = uri.lastPathSegment.orEmpty(),
        folders = listOf(FolderEntry("${uri.lastPathSegment}-root/sub", "sub", emptyList(), listOf(note("deep.md")))),
        notes = notes.map { note(it) },
    )

    private fun listed(vararg uris: Uri) {
        repo.folders.value = uris.map { NotesFolder(it, it.lastPathSegment.orEmpty(), null, available = true) }
        uris.forEach { repo.listings[it] = rootOf(it, "${it.lastPathSegment}.md") }
    }

    /** The ViewModel as the Files screen sees it: shown, so it has asked for the first listing. */
    private fun vm() = shownLater().also { it.refresh() }

    /** The ViewModel as the app creates it at start, before the Files screen is ever shown. */
    private fun shownLater() = NotesFoldersViewModel(repo, store, marksStore, clock = { now })

    /** Polls, running the main looper and moving its clock on, so the 300 ms watch debounce can fire. */
    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (actual() == expected) return
            Thread.sleep(10L)
        }
        assertEquals(message, expected, actual())
    }

    private val NotesFoldersViewModel.loadedRoot: FolderEntry?
        get() = (uiState.value.listing as? ListingUi.Loaded)?.root

    private val NotesFoldersViewModel.ticked: Uri?
        get() = uiState.value.folders.singleOrNull { it.selected }?.treeUri

    @Test
    fun `with no folder added the list says so`() {
        val vm = vm()
        awaitValue(ListingUi.NoFolders, "empty state") { vm.uiState.value.listing }
    }

    @Test
    fun `F1 - a picked folder gets the tick and is listed, subfolders closed`() {
        listed(b)
        store.selectedFolder.value = b
        repo.listings[a] = rootOf(a, "a.md")
        // Left over from an earlier time this folder was added: it must not open the subfolder.
        store.expanded.value = setOf(expandedKey(a, "A-root/sub"), expandedKey(b, "B-root/sub"))
        val vm = vm()
        awaitValue(repo.listings[b], "B listed first") { vm.loadedRoot }

        vm.onFolderPicked(a)

        awaitValue(repo.listings[a], "A listed") { vm.loadedRoot }
        assertEquals(a, vm.ticked)
        assertEquals(listOf(b, a), vm.uiState.value.folders.map { it.treeUri })
        assertEquals(emptySet<String>(), (vm.uiState.value.listing as ListingUi.Loaded).expanded)
        assertEquals(setOf(expandedKey(b, "B-root/sub")), store.expanded.value)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `P13 - picking a folder already listed ticks it and says so`() {
        listed(a, b)
        store.selectedFolder.value = a
        val vm = vm()
        awaitValue(repo.listings[a], "A listed") { vm.loadedRoot }

        vm.onFolderPicked(b)

        awaitValue(b, "B ticked") { vm.ticked }
        awaitValue(FolderMessage.ALREADY_LISTED, "message shown") { vm.uiState.value.message }
        assertEquals(listOf(a, b), vm.uiState.value.folders.map { it.treeUri })
        vm.messageShown()
        awaitValue(null, "message cleared") { vm.uiState.value.message }
    }

    @Test
    fun `a stored tick on a folder still listed is kept and listed`() {
        listed(a, b)
        store.selectedFolder.value = b
        val vm = vm()

        awaitValue(repo.listings[b], "B listed") { vm.loadedRoot }
        assertEquals(b, vm.ticked)
    }

    @Test
    fun `a stored tick on a folder no longer listed moves to the first`() {
        listed(a, b)
        store.selectedFolder.value = Uri.parse("content://docs/tree/Gone")
        val vm = vm()

        awaitValue(repo.listings[a], "A listed") { vm.loadedRoot }
        assertEquals(a, store.selectedFolder.value)
    }

    @Test
    fun `P12 - removing the ticked folder ticks the first left, removing the last empties the list`() {
        listed(a, b)
        store.selectedFolder.value = a
        val vm = vm()
        awaitValue(repo.listings[a], "A listed") { vm.loadedRoot }

        vm.remove(a)
        awaitValue(repo.listings[b], "B listed") { vm.loadedRoot }
        assertEquals(listOf(b), vm.uiState.value.folders.filter { it.selected }.map { it.treeUri })

        vm.remove(b)
        awaitValue(ListingUi.NoFolders, "empty state") { vm.uiState.value.listing }
        assertEquals(emptyList<FolderRowUi>(), vm.uiState.value.folders)
        awaitValue(null, "no tick stored") { store.selectedFolder.value }
    }

    @Test
    fun `a folder that cannot be listed shows as unavailable`() {
        repo.folders.value = listOf(NotesFolder(a, "A", null, available = false))
        val vm = vm()

        awaitValue(ListingUi.Unavailable, "unavailable") { vm.uiState.value.listing }
        assertEquals(false, vm.uiState.value.folders.single().available)
    }

    @Test
    fun `L2 - opening and closing a subfolder is stored`() {
        listed(a)
        val vm = vm()
        awaitValue(repo.listings[a], "A listed") { vm.loadedRoot }

        vm.toggleExpanded("A-root/sub")
        awaitValue(setOf(expandedKey(a, "A-root/sub")), "stored open") { store.expanded.value }
        awaitValue(setOf("A-root/sub"), "shown open") { (vm.uiState.value.listing as ListingUi.Loaded).expanded }

        vm.toggleExpanded("A-root/sub")
        awaitValue(emptySet<String>(), "stored closed") { store.expanded.value }
    }

    @Test
    fun `L6 - while watching, a change in the folder lists it again`() {
        listed(a)
        store.expanded.value = setOf(expandedKey(a, "A-root/sub"), expandedKey(a, "A-root/gone"))
        val vm = vm()
        awaitValue(repo.listings[a], "A listed") { vm.loadedRoot }

        vm.startWatching()
        awaitValue(listOf(a to setOf("A-root", "A-root/sub")), "watching the root and the open subfolder") {
            repo.watchRequests.toList()
        }
        awaitValue(1, "collecting changes") { repo.changeEvents.subscriptionCount.value }
        repo.listings[a] = rootOf(a, "A.md", "fresh.md")
        repo.changeEvents.tryEmit(Unit)

        awaitValue(listOf("A.md", "fresh.md"), "listed again") { vm.loadedRoot?.notes?.map { it.name } }
        vm.stopWatching()
    }

    @Test
    fun `F4 - Find again lists the folder found and forgets the old one's open subfolders`() {
        val c = Uri.parse("content://docs/tree/C")
        listed(b)
        repo.folders.value = listOf(NotesFolder(a, "A", null, available = false)) + repo.folders.value
        repo.listings[c] = rootOf(c, "c.md")
        store.selectedFolder.value = a
        store.expanded.value = setOf(expandedKey(a, "A-root/sub"), expandedKey(b, "B-root/sub"))
        repo.findAgainAnswer = { old, picked ->
            val found = NotesFolder(picked, "A", null, available = true)
            repo.folders.update { list -> list.map { if (it.treeUri == old) found else it } }
            store.setSelectedFolder(picked)
            AddFolderResult.Added(found)
        }
        val vm = vm()
        awaitValue(ListingUi.Unavailable, "A unavailable") { vm.uiState.value.listing }

        vm.findAgain(a, c)

        awaitValue(repo.listings[c], "C listed") { vm.loadedRoot }
        assertEquals(listOf(c, b), vm.uiState.value.folders.map { it.treeUri })
        awaitValue(setOf(expandedKey(b, "B-root/sub")), "A's open subfolders forgotten") { store.expanded.value }
    }

    @Test
    fun `refusals from the picker become messages`() {
        listed(a)
        val vm = vm()
        awaitValue(repo.listings[a], "A listed") { vm.loadedRoot }

        repo.addAnswer = { AddFolderResult.NotAFolder }
        vm.onFolderPicked(b)
        awaitValue(FolderMessage.NOT_A_FOLDER, "not a folder") { vm.uiState.value.message }
        vm.messageShown()
        awaitValue(null, "cleared") { vm.uiState.value.message }

        repo.addAnswer = { AddFolderResult.PermissionRefused }
        vm.onFolderPicked(b)
        awaitValue(FolderMessage.PERMISSION_REFUSED, "permission refused") { vm.uiState.value.message }
        vm.messageShown()
        awaitValue(null, "cleared") { vm.uiState.value.message }

        repo.findAgainAnswer = { _, _ -> AddFolderResult.AlreadyListed(repo.folders.value.first()) }
        vm.findAgain(a, b)
        awaitValue(FolderMessage.ALREADY_LISTED, "Find again of a listed folder") { vm.uiState.value.message }
        vm.messageShown()
        awaitValue(null, "cleared") { vm.uiState.value.message }

        repo.findAgainAnswer = { _, _ -> AddFolderResult.PermissionRefused }
        vm.findAgain(a, b)
        awaitValue(FolderMessage.PERMISSION_REFUSED, "Find again refused") { vm.uiState.value.message }
        assertEquals(listOf(a), vm.uiState.value.folders.map { it.treeUri })
    }

    @Test
    fun `removing a folder forgets its open subfolders`() {
        listed(a, b)
        store.expanded.value = setOf(expandedKey(a, "A-root/sub"), expandedKey(b, "B-root/sub"))
        val vm = vm()
        awaitValue(repo.listings[a], "A listed") { vm.loadedRoot }

        vm.remove(a)

        awaitValue(setOf(expandedKey(b, "B-root/sub")), "A's keys dropped") { store.expanded.value }
    }

    @Test
    fun `a listing overtaken by a new tick is never shown`() {
        listed(a, b)
        store.selectedFolder.value = a
        repo.holdNext = a
        val vm = vm()
        val seen = CopyOnWriteArrayList<ListingUi>()
        vm.viewModelScope.launch { vm.uiState.collect { seen += it.listing } }
        awaitValue(true, "A's listing started") { repo.held.containsKey(a) }

        vm.select(b)
        awaitValue(b, "B ticked") { vm.ticked }
        // B's listing starts once A's has finished or stopped; A's finishes now, after the tick moved.
        repo.held.getValue(a).resume(Unit)

        awaitValue(repo.listings[b], "B listed") { vm.loadedRoot }
        val shownRoots = seen.filterIsInstance<ListingUi.Loaded>().map { it.root }
        assertEquals("only B's tree was ever shown", listOf(repo.listings[b]), shownRoots.distinct())
    }

    @Test
    fun `a listing overtaken by a refresh of the same folder is never shown`() {
        listed(a)
        store.selectedFolder.value = a
        // The first listing is Unavailable: a Listed one is also stopped by the marks write, which
        // is cancellable, so only this result is decided by the check under test.
        repo.listings.remove(a)
        repo.heldKeepsStartListing = true
        repo.holdNext = a
        val vm = vm()
        awaitValue(true, "the first listing started") { repo.held.containsKey(a) }
        val newer = rootOf(a, "newer.md")
        repo.listings[a] = newer
        val first = repo.held.getValue(a)

        // The refresh waits for the first listing to end, then lists again; hold that one too.
        vm.refresh()
        repo.holdNext = a
        first.resume(Unit)
        awaitValue(true, "the refresh started") { repo.held[a].let { it != null && it !== first } }

        assertEquals("the overtaken listing is not shown", ListingUi.Loading, vm.uiState.value.listing)
        repo.held.getValue(a).resume(Unit)
        awaitValue(newer, "the refresh is shown") { vm.loadedRoot }
    }

    @Test
    fun `a ticked folder not yet listed never shows the previous folder's listing`() {
        listed(a, b)
        store.selectedFolder.value = a
        val vm = vm()
        awaitValue(repo.listings[a], "A listed") { vm.loadedRoot }
        repo.holdNext = b

        vm.select(b)

        awaitValue(true, "B's listing started") { repo.held.containsKey(b) }
        assertEquals("B ticked", b, vm.ticked)
        assertEquals("A's tree is not B's", ListingUi.Loading, vm.uiState.value.listing)
        repo.held.getValue(b).resume(Unit)
        awaitValue(repo.listings[b], "B listed") { vm.loadedRoot }
    }

    // Section 3b: the marks as the Files screen gets them.

    private val NotesFoldersViewModel.loadedMarks: Map<String, Mark>
        get() = (uiState.value.listing as? ListingUi.Loaded)?.marks.orEmpty().mapValues { it.value.mark }

    /** A, scanned before when it held no notes: every note listed now is New. */
    private fun everythingNewIn(uri: Uri) {
        listed(uri)
        marksStore.records[uri] = emptyMap()
    }

    @Test
    fun `M1 - a listing marks new notes and the folder row shows the total`() {
        everythingNewIn(a)
        val vm = vm()

        awaitValue(mapOf("A.md-id" to Mark.NEW, "deep.md-id" to Mark.NEW), "both notes New") { vm.loadedMarks }
        val loaded = vm.uiState.value.listing as ListingUi.Loaded
        assertEquals(2, vm.uiState.value.folders.single().markCount)
        assertEquals(1, loaded.counts["A-root/sub"])
        val subRow = loaded.rows.filterIsInstance<FileRowUi.Folder>().single()
        assertEquals(1, subRow.markCount)
        assertEquals(Ago.JustNow, loaded.rows.filterIsInstance<FileRowUi.Note>().single().ago)
    }

    @Test
    fun `M3 - the first scan of a folder marks nothing`() {
        listed(a)
        val vm = vm()

        awaitValue(setOf("A.md-id", "deep.md-id"), "baseline stored") { marksStore.records[a]?.keys }
        assertEquals(emptyMap<String, Mark>(), vm.loadedMarks)
        assertNull(vm.uiState.value.folders.single().markCount?.takeIf { it > 0 })
    }

    @Test
    fun `M5 - Mark all as seen clears the ticked folder and its subfolders`() {
        everythingNewIn(a)
        val vm = vm()
        awaitValue(2, "two marks") { vm.loadedMarks.size }

        vm.markAllSeen()

        awaitValue(emptyMap<String, Mark>(), "no marks") { vm.loadedMarks }
        assertEquals(setOf(false), marksStore.records.getValue(a).values.map { it.isNew }.toSet())
    }

    @Test
    fun `M4 - a note shown in the reader loses its mark and takes its current values`() {
        everythingNewIn(a)
        val vm = vm()
        awaitValue(2, "two marks") { vm.loadedMarks.size }
        val opened = repo.noteUri(a, "A.md-id")
        repo.stats[opened] = DocumentStats(lastModified = 42L, size = 7L)

        vm.onNoteSeen(opened)

        awaitValue(mapOf("deep.md-id" to Mark.NEW), "only the other note marked") { vm.loadedMarks }
        assertEquals(
            SeenRecord(42L, 7L, isNew = false, newSince = null, updatedSince = null),
            marksStore.records.getValue(a)["A.md-id"],
        )
    }

    @Test
    fun `M4 - a document of another provider is not a note of the folder`() {
        everythingNewIn(a)
        val vm = vm()
        awaitValue(2, "two marks") { vm.loadedMarks.size }
        // The same document ID, but from another app's provider.
        val foreign = DocumentsContract.buildDocumentUri("other.provider", "A.md-id")
        val deep = repo.noteUri(a, "deep.md-id")

        vm.onNoteSeen(foreign)
        vm.onNoteSeen(deep)

        awaitValue(mapOf("A.md-id" to Mark.NEW), "only deep.md seen") { vm.loadedMarks }
        assertEquals(listOf(deep), repo.statsAsked.toList())
    }

    @Test
    fun `removing a folder forgets its marks`() {
        listed(a, b)
        marksStore.records[b] = mapOf("x" to SeenRecord(1L, 1L, isNew = true, newSince = 1L, updatedSince = null))
        val vm = vm()
        awaitValue(repo.listings[a], "A listed") { vm.loadedRoot }

        vm.remove(b)

        awaitValue(null, "B's marks dropped") { marksStore.records[b] }
        assertEquals(setOf("A.md-id", "deep.md-id"), marksStore.records[a]?.keys)
    }

    @Test
    fun `a pull-to-refresh shows until the listing it asked for is shown`() {
        listed(a)
        val vm = vm()
        awaitValue(repo.listings[a], "A listed") { vm.loadedRoot }
        repo.holdNext = a

        vm.refresh(pulled = true)

        awaitValue(true, "listing held") { repo.held.containsKey(a) }
        awaitValue(true, "indicator shown while the listing runs") { vm.uiState.value.refreshing }
        repo.listings[a] = rootOf(a, "A.md", "fresh.md")
        repo.held.getValue(a).resume(Unit)
        awaitValue(listOf("A.md", "fresh.md"), "listed again") { vm.loadedRoot?.notes?.map { it.name } }
        awaitValue(false, "indicator gone") { vm.uiState.value.refreshing }
    }

    @Test
    fun `L6 - nothing is listed until the Files screen asks, and a note seen meanwhile still loses its mark`() {
        listed(a)
        store.selectedFolder.value = a
        marksStore.records[a] = mapOf(
            "A.md-id" to SeenRecord(1L, 1L, isNew = true, newSince = 5L, updatedSince = null),
        )
        val vm = shownLater()
        val opened = repo.noteUri(a, "A.md-id")
        repo.stats[opened] = DocumentStats(lastModified = 42L, size = 7L)

        // M4 needs no listing: the stored records and one query of the document.
        vm.onNoteSeen(opened)
        awaitValue(false, "seen from the stored records") { marksStore.records.getValue(a).getValue("A.md-id").isNew }
        assertEquals("no listing before the screen asks", 0, repo.listCalls.get())
        assertEquals(ListingUi.Loading, vm.uiState.value.listing)

        vm.refresh()

        awaitValue(repo.listings[a], "listed once asked") { vm.loadedRoot }
        assertEquals(1, repo.listCalls.get())
    }

    /** Polls for [ms], failing at once if [actual] ever differs from [expected]: for a value that must not change. */
    private fun <T> assertStays(expected: T, message: String, ms: Long = 500L, actual: () -> T) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            assertEquals(message, expected, actual())
            Thread.sleep(10L)
        }
    }

    @Test
    fun `M4 - a note of a folder not ticked is seen, and the ticked folder keeps its marks`() {
        listed(a, b)
        store.selectedFolder.value = a
        marksStore.records[a] = emptyMap()
        val bNew = SeenRecord(1L, 1L, isNew = true, newSince = 5L, updatedSince = null)
        marksStore.records[b] = mapOf("B.md-id" to bNew)
        val vm = vm()
        awaitValue(2, "A's two notes New") { vm.loadedMarks.size }
        val opened = repo.noteUri(b, "B.md-id")
        repo.stats[opened] = DocumentStats(lastModified = 42L, size = 7L)

        vm.onNoteSeen(opened)

        awaitValue(false, "B's note seen") { marksStore.records.getValue(b).getValue("B.md-id").isNew }
        assertStays(2, "A's marks untouched") { vm.loadedMarks.size }
        assertEquals(2, vm.uiState.value.folders.first { it.treeUri == a }.markCount)
    }

    @Test
    fun `L7 - the marks are read, worked out and written off the main thread`() {
        everythingNewIn(a)
        val vm = vm()
        awaitValue(2, "two marks") { vm.loadedMarks.size }
        vm.markAllSeen()
        awaitValue(0, "all seen") { vm.loadedMarks.size }

        val main = Looper.getMainLooper().thread
        assertTrue("the store was used", marksStore.threads.isNotEmpty())
        assertEquals("store calls on the main thread", emptyList<Thread>(), marksStore.threads.filter { it == main })
    }

    @Test
    fun `a listing still running when its folder is removed writes no marks for it`() {
        listed(a, b)
        store.selectedFolder.value = a
        marksStore.records[a] = emptyMap()
        repo.holdNext = a
        val vm = vm()
        awaitValue(true, "A's listing started") { repo.held.containsKey(a) }
        repo.removalsShowAtOnce = false

        vm.remove(a)
        awaitValue(null, "A's marks dropped") { marksStore.records[a] }
        // The listing finishes before the ViewModel has seen A leave the list.
        repo.held.getValue(a).resume(Unit)

        awaitValue(repo.listings[a], "the listing came back") { vm.loadedRoot }
        assertNull("no marks written after the drop", marksStore.records[a])
        repo.publishRemovals()
    }

    @Test
    fun `F4 - Find again forgets the old folder's marks`() {
        val c = Uri.parse("content://docs/tree/C")
        repo.folders.value = listOf(NotesFolder(a, "A", null, available = false))
        repo.listings[c] = rootOf(c, "c.md")
        store.selectedFolder.value = a
        marksStore.records[a] = mapOf("x" to SeenRecord(1L, 1L, isNew = true, newSince = 1L, updatedSince = null))
        repo.findAgainAnswer = { old, picked ->
            val found = NotesFolder(picked, "A", null, available = true)
            repo.folders.update { list -> list.map { if (it.treeUri == old) found else it } }
            store.setSelectedFolder(picked)
            AddFolderResult.Added(found)
        }
        val vm = vm()
        awaitValue(ListingUi.Unavailable, "A unavailable") { vm.uiState.value.listing }

        vm.findAgain(a, c)

        awaitValue(null, "A's marks dropped") { marksStore.records[a] }
        awaitValue(repo.listings[c], "C listed") { vm.loadedRoot }
        assertEquals("C starts from a baseline (M3)", emptyMap<String, Mark>(), vm.loadedMarks)
    }

    @Test
    fun `M4 - a note the app has just saved takes its saved values as the baseline`() {
        listed(a)
        store.selectedFolder.value = a
        val old = SeenRecord(1L, 1L, isNew = false, newSince = null, updatedSince = null)
        marksStore.records[a] = mapOf("A.md-id" to old, "deep.md-id" to old)
        val saves = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
        val vm = NotesFoldersViewModel(repo, store, marksStore, clock = { now }, savedDocuments = saves)
        awaitValue(1, "listening for saves") { saves.subscriptionCount.value }
        val saved = repo.noteUri(a, "A.md-id")
        repo.stats[saved] = DocumentStats(lastModified = 42L, size = 7L)

        saves.tryEmit(saved)

        val baseline = { marksStore.records.getValue(a).getValue("A.md-id") }
        val savedValues = SeenRecord(42L, 7L, isNew = false, newSince = null, updatedSince = null)
        awaitValue(savedValues, "saved values") { baseline() }
        assertEquals("the other note untouched", old, marksStore.records.getValue(a)["deep.md-id"])
        vm.stopWatching()
    }

    /**
     * S8-A46 (fix A): Drive sets a saved note's date about 8 s after the save, so the date stored as
     * the baseline right after the save is the old one. The app's own save then waits for that date.
     */
    private fun savedThenListed(
        lastModified: Long,
        size: Long,
        later: Long,
        saveIsOwn: Boolean = true,
        thenDate: Long? = null,
    ): SeenRecord {
        listed(a)
        store.selectedFolder.value = a
        val old = SeenRecord(1L, 1L, isNew = false, newSince = null, updatedSince = null)
        marksStore.records[a] = mapOf("A.md-id" to old, "deep.md-id" to old)
        val saves = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
        val vm = NotesFoldersViewModel(repo, store, marksStore, clock = { now }, savedDocuments = saves)
        awaitValue(1, "listening for saves") { saves.subscriptionCount.value }
        val saved = repo.noteUri(a, "A.md-id")
        // What Drive answers right after the save: the size written, the date still the old one.
        repo.stats[saved] = DocumentStats(lastModified = 42L, size = 7L)
        if (saveIsOwn) saves.tryEmit(saved) else vm.onNoteSeen(saved)
        val baseline = { marksStore.records.getValue(a).getValue("A.md-id") }
        awaitValue(SeenRecord(42L, 7L, false, null, null), "saved values") { baseline() }

        now += later
        repo.listings[a] = rootOf(a).copy(notes = listOf(NoteEntry("A.md-id", "A.md", lastModified, size)))
        vm.refresh()
        awaitValue(true, "the listing reconciled") {
            baseline().let { it.lastModified != 42L || it.updatedSince != null }
        }
        if (thenDate != null) {
            // Later, inside the wait: another date move at the same size, from another device.
            now += 1_000L
            repo.listings[a] = rootOf(a).copy(notes = listOf(NoteEntry("A.md-id", "A.md", thenDate, size)))
            vm.refresh()
            awaitValue(true, "the second listing reconciled") {
                baseline().let { it.lastModified != lastModified || it.updatedSince != null }
            }
        }
        vm.stopWatching()
        return baseline()
    }

    @Test
    fun `S8-A46 - the provider's own date for the app's save is taken in silently`() {
        val record = savedThenListed(lastModified = 50L, size = 7L, later = 10_000L)
        assertEquals("no Updated mark", null, NoteMarks.markOf(record))
        assertEquals("the provider's date is the baseline", SeenRecord(50L, 7L, false, null, null), record)
    }

    @Test
    fun `S8-A46 - a size other than the one written after the app's save is Updated`() {
        val record = savedThenListed(lastModified = 50L, size = 8L, later = 10_000L)
        assertEquals(Mark.UPDATED, NoteMarks.markOf(record)?.mark)
    }

    @Test
    fun `S8-A46 - a date that moves after the wait has ended is Updated`() {
        val record = savedThenListed(lastModified = 50L, size = 7L, later = 2 * 60_000L + 1)
        assertEquals(Mark.UPDATED, NoteMarks.markOf(record)?.mark)
    }

    @Test
    fun `S8-A46 - a note only opened, not saved, gets no wait`() {
        val record = savedThenListed(lastModified = 50L, size = 7L, later = 10_000L, saveIsOwn = false)
        assertEquals(Mark.UPDATED, NoteMarks.markOf(record)?.mark)
    }

    @Test
    fun `S8-A46 - an own save settles once, so a second same-size date move is Updated`() {
        val record = savedThenListed(lastModified = 50L, size = 7L, later = 10_000L, thenDate = 60L)
        assertEquals(Mark.UPDATED, NoteMarks.markOf(record)?.mark)
    }

    /**
     * S8-A52: the app's own save of A.md as Drive answers right after it: the size written (7), the
     * old date (42). The ViewModel re-reads the note every [RECHECK_MS] ms; no listing runs.
     */
    private fun ownSave(saveStarts: Flow<Uri> = emptyFlow()): Pair<NotesFoldersViewModel, Uri> {
        listed(a)
        store.selectedFolder.value = a
        val old = SeenRecord(1L, 1L, isNew = false, newSince = null, updatedSince = null)
        marksStore.records[a] = mapOf("A.md-id" to old, "deep.md-id" to old)
        val saves = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
        val vm = NotesFoldersViewModel(
            repo,
            store,
            marksStore,
            clock = { now },
            savedDocuments = saves,
            saveStarts = saveStarts,
            recheckMs = RECHECK_MS,
            recheckWindowMs = RECHECK_WINDOW_MS,
        )
        awaitValue(1, "listening for saves") { saves.subscriptionCount.value }
        val saved = repo.noteUri(a, "A.md-id")
        repo.stats[saved] = DocumentStats(lastModified = 42L, size = 7L)
        saves.tryEmit(saved)
        awaitValue(SeenRecord(42L, 7L, false, null, null), "saved values") { aRecord() }
        return vm to saved
    }

    private fun aRecord(): SeenRecord = marksStore.records.getValue(a).getValue("A.md-id")

    /** Lists A [later] ms on, with A.md at [date] and [size], and returns A.md's record after it. */
    private fun listAfter(vm: NotesFoldersViewModel, later: Long, date: Long, size: Long): SeenRecord {
        now += later
        val root = rootOf(a).copy(notes = listOf(NoteEntry("A.md-id", "A.md", date, size)))
        repo.listings[a] = root
        vm.refresh()
        awaitValue(root, "A listed") { vm.loadedRoot }
        return aRecord()
    }

    /** Runs the main looper for [count] re-read intervals. */
    private fun idleIntervals(count: Int) = repeat(count) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RECHECK_MS))
        Thread.sleep(RECHECK_MS)
    }

    @Test
    fun `S8-A52 - an own save first listed 3 min later is not Updated`() {
        val (vm, saved) = ownSave()
        // Drive's date for the save arrives a few seconds on, at the same size; no listing meanwhile.
        repo.stats[saved] = DocumentStats(lastModified = 50L, size = 7L)
        awaitValue(50L, "Drive's later date taken in without a listing") { aRecord().lastModified }

        val record = listAfter(vm, later = 3 * 60_000L, date = 50L, size = 7L)

        assertEquals("no Updated mark", null, NoteMarks.markOf(record))
        assertEquals(SeenRecord(50L, 7L, false, null, null), record)
        vm.stopWatching()
    }

    @Test
    fun `S8-A52 - an outside edit of another size during the re-reads is Updated`() {
        val (vm, saved) = ownSave()
        val asked = repo.statsAsked.size
        repo.stats[saved] = DocumentStats(lastModified = 60L, size = 9L)
        awaitValue(true, "re-read twice after the outside edit") { repo.statsAsked.size >= asked + 2 }
        assertEquals("the baseline stays the save's", SeenRecord(42L, 7L, false, null, null), aRecord())

        val record = listAfter(vm, later = 10_000L, date = 60L, size = 9L)

        assertEquals(Mark.UPDATED, NoteMarks.markOf(record)?.mark)
        vm.stopWatching()
    }

    @Test
    fun `S8-A52 - the re-reads end when the app goes to the background`() {
        val (vm, saved) = ownSave()
        awaitValue(true, "re-reading") { repo.statsAsked.size >= 2 }

        vm.stopSaveRecheck()
        repo.stats[saved] = DocumentStats(lastModified = 50L, size = 7L)
        idleIntervals(RECHECK_COUNT)

        assertEquals("the later date is left to a listing", 42L, aRecord().lastModified)
        vm.stopWatching()
    }

    @Test
    fun `S8-A52 - the re-reads end when another save starts`() {
        val starts = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
        val (vm, saved) = ownSave(starts)
        awaitValue(true, "re-reading") { repo.statsAsked.size >= 2 }

        starts.tryEmit(saved)
        idleIntervals(2)
        repo.stats[saved] = DocumentStats(lastModified = 50L, size = 7L)
        idleIntervals(RECHECK_COUNT)

        assertEquals("no re-read overlaps the next save", 42L, aRecord().lastModified)
        vm.stopWatching()
    }

    private companion object {
        const val RECHECK_MS = 20L
        const val RECHECK_COUNT = 10
        const val RECHECK_WINDOW_MS = 5_000L
    }
}
