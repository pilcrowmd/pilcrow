// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.di.Clock
import com.pilcrowmd.di.InstallSource
import com.pilcrowmd.domain.model.DAY_MILLIS
import com.pilcrowmd.storage.LocalStorageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch

/**
 * The wiring around M-177's pure gate (which [com.pilcrowmd.domain.model.WelcomePromptsTest]
 * covers case by case): the ViewModel reads the install facts and the stored count and flag, and
 * reacts to them changing. Every assertion waits for a change AWAY from the initial `false`.
 */
@RunWith(RobolectricTestRunner::class)
class WelcomePromptsViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    private val installed = 1_000_000_000_000L
    private val clock = Clock { installed + 30 * DAY_MILLIS }

    private class FakeInstall(private val play: Boolean, private val first: Long) : InstallSource {
        /** Counted down once both reads have returned, so a test can check that they happened. */
        val read = CountDownLatch(1)
        override fun isPlayInstall() = play
        override fun firstInstallTimeMillis() = first.also { read.countDown() }
    }

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        storage = LocalStorageManager(
            context,
            PreferenceDataStoreFactory.create(scope = storageScope) { tempFolder.newFile("prompts.preferences_pb") },
        )
    }

    @After
    fun tearDown() = storageScope.cancel()

    private fun vm(
        play: Boolean = true,
        releaseMillis: Long? = null,
        install: InstallSource = FakeInstall(play, installed),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = WelcomePromptsViewModel(
        storage,
        install,
        object : AppInfo {
            override val versionName = "1.0.11"
            override val releasePrepMillis = releaseMillis
        },
        clock,
        ioDispatcher,
    )

    /** A release 70 days before [clock]'s now, so the M-174 note is due. */
    private val oldRelease = installed + 30 * DAY_MILLIS - 70 * DAY_MILLIS

    private fun opened(n: Int) = runBlocking { repeat(n) { storage.incrementDocumentsOpened() } }

    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (actual() == expected) return
            Thread.sleep(10L)
        }
        assertEquals(message, expected, actual())
    }

    @Test
    fun `the Settings card follows the install source`() {
        val vm = vm(play = true)
        awaitValue(true, "Play install reaches the Settings flag") { vm.isPlayInstall.value }
    }

    @Test
    fun `a non-Play install keeps the Settings card hidden once its install facts are read`() {
        val install = FakeInstall(play = false, first = installed)
        // The flag starts false, so waiting for false would pass before anything is read. The reads
        // run on the main looper here, so one idle() runs the read, its publish and the flag's map.
        val vm = vm(install = install, ioDispatcher = Dispatchers.Main)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("install facts read", 0L, install.read.count)
        assertEquals("a non-Play install never shows the Settings card", false, vm.isPlayInstall.value)
    }

    @Test
    fun `the fifth open brings the card up while the app is running`() {
        opened(4)
        val vm = vm()
        awaitValue(true, "install facts read") { vm.isPlayInstall.value }
        // The Play flag comes from the same combine as the count, so the four opens are in by now:
        // without this, a card that showed at four opens would pass the wait below at once.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("four opens showed the card", false, vm.ratingCardVisible.value)
        opened(1)
        awaitValue(true, "fifth open shows the card") { vm.ratingCardVisible.value }
    }

    @Test
    fun `a button hides it, and a new ViewModel keeps it hidden`() {
        opened(5)
        val vm = vm()
        awaitValue(true, "card shown") { vm.ratingCardVisible.value }
        vm.dismissRatingCard()
        awaitValue(false, "button hides the card") { vm.ratingCardVisible.value }
        // Stored, not held in memory: a fresh ViewModel (next launch) with even more opens stays
        // hidden. Its barrier is the Play flag arriving, which comes from the same combine inputs.
        opened(10)
        val next = vm()
        awaitValue(true, "next launch has read its install facts") { next.isPlayInstall.value }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, next.ratingCardVisible.value)
    }

    @Test
    fun `when both are due only the update note shows, and dismissing it lets the card through`() {
        opened(5)
        val vm = vm(releaseMillis = oldRelease)
        awaitValue(true, "the note is due") { vm.updateNoteVisible.value }
        awaitValue(true, "install facts read") { vm.isPlayInstall.value }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the rating card showed alongside the update note", false, vm.ratingCardVisible.value)

        vm.dismissUpdateNote()
        awaitValue(false, "the \u00d7 hides the note") { vm.updateNoteVisible.value }
        awaitValue(true, "the card was only held back while the note was due") { vm.ratingCardVisible.value }
    }

    @Test
    fun `a dismissed note stays snoozed on the next launch`() {
        val vm = vm(releaseMillis = oldRelease)
        awaitValue(true, "the note is due") { vm.updateNoteVisible.value }
        vm.dismissUpdateNote()
        awaitValue(false, "the \u00d7 hides the note") { vm.updateNoteVisible.value }
        // Stored with its time, not held in memory: a fresh ViewModel on the same clock reads the
        // dismissal. `false` is also the note's initial value, so the barrier is the rating card:
        // it can only turn true once the note's flow has emitted "not due" from the stored time.
        assertEquals(clock.nowMillis(), runBlocking { storage.updateNoteDismissedAt.first() })
        opened(5)
        val next = vm(releaseMillis = oldRelease)
        awaitValue(true, "next launch decided the note from the stored dismissal") { next.ratingCardVisible.value }
        assertEquals(false, next.updateNoteVisible.value)
    }
}
