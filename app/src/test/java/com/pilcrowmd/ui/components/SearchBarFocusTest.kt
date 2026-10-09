// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import io.github.rosemoe.sora.widget.CodeEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-301: opening Search must put focus IN the search field. Before the fix nothing requested
 * focus, so TalkBack stayed on the toolbar's Search button and, when the editor still held input
 * focus, hardware-keyboard typing went into the DOCUMENT instead of the query.
 */
@RunWith(RobolectricTestRunner::class)
class SearchBarFocusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var visible by mutableStateOf(false)
    private var query by mutableStateOf("")
    private var focusKey by mutableIntStateOf(0)

    private fun searchField() = compose.onNode(hasSetTextAction())

    /** Opening the bar (the visibility flag flipping, as `setSearchVisible(true)` does) focuses the field. */
    @Test
    fun openingTheBarFocusesTheField() {
        compose.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                if (visible) SearchBar(query = query, onQueryChange = { query = it })
            }
        }
        compose.runOnIdle { visible = true }
        compose.waitForIdle()

        searchField().assertIsFocused()
    }

    /**
     * The editor case, with the app's real editor view: a Sora [CodeEditor] in an `AndroidView`
     * holds Android view focus, then the bar opens. A key event dispatched through the ACTIVITY
     * (the same route a hardware keyboard takes, unlike `performKeyInput`, which injects at the
     * Compose root and would bypass the editor) must land in the query, and the document text must
     * be unchanged. Also asserts the editor view itself gave up focus.
     */
    @Test
    fun openingTheBarTakesFocusFromTheEditorViewAndKeysGoToTheQuery() {
        lateinit var editor: CodeEditor
        compose.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                Column {
                    if (visible) SearchBar(query = query, onQueryChange = { query = it })
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        factory = { context ->
                            CodeEditor(context).also {
                                it.setText(DOC)
                                editor = it
                            }
                        },
                    )
                }
            }
        }
        compose.runOnIdle { assertTrue("precondition: the editor view takes focus", editor.requestFocus()) }
        compose.runOnIdle { assertTrue("precondition: the editor view holds focus", editor.isFocused) }

        compose.runOnIdle { visible = true }
        compose.waitForIdle()

        searchField().assertIsFocused()
        compose.runOnIdle { assertFalse("the editor view must give up Android focus", editor.hasFocus()) }

        compose.runOnIdle {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Q))
        }
        compose.waitForIdle()

        assertEquals("the typed key lands in the search query", "q", query)
        compose.runOnIdle { assertEquals("the document is untouched", DOC, editor.text.toString()) }
    }

    /**
     * The toolbar's Search pressed again while the bar is already open: `setSearchVisible(true)` is
     * then a no-op, so only the bumped [SearchBar]'s `focusRequestKey` can bring focus back. Focus is
     * first moved to the real editor view (a tap into the document), then the key is bumped.
     */
    @Test
    fun searchPressedAgainWhileOpenRefocusesTheField() {
        lateinit var editor: CodeEditor
        compose.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                Column {
                    SearchBar(query = query, onQueryChange = { query = it }, focusRequestKey = focusKey)
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        factory = { context ->
                            CodeEditor(context).also {
                                it.setText(DOC)
                                editor = it
                            }
                        },
                    )
                }
            }
        }
        searchField().assertIsFocused()
        compose.runOnIdle { assertTrue("precondition: the editor view takes focus", editor.requestFocus()) }
        compose.waitForIdle()
        searchField().assertIsNotFocused()

        compose.runOnIdle { focusKey++ }
        compose.waitForIdle()

        searchField().assertIsFocused()
        compose.runOnIdle { assertFalse("the editor view must give up Android focus", editor.hasFocus()) }
    }

    private companion object {
        const val DOC = "line one\nline two\n"
    }
}
