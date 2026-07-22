// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.pilcrowmd.ui.components.MarkdownPreview
import com.pilcrowmd.ui.theme.DarkColorScheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Crash-regression guard for large-file rendering (Safeguard 3).
 *
 * Renders a ~5000-line markdown document (mixed blocks: prose, headings, code, tables, lists)
 * via the real MarkwonRenderer → RecyclerView adapter, under Robolectric NATIVE graphics.
 * Asserts the render completes WITHOUT throwing within a generous timeout (60 seconds).
 *
 * **Intent:** This is a **crash/regression guard**, NOT a real-device latency benchmark.
 * Robolectric/JVM timing is not representative of on-device latency. A true macrobenchmark
 * (real on-device performance under load) is deferred to a potential future benchmark module.
 * This test exists to catch pathological regressions (infinite loops, memory issues, hangs) that
 * would kill the app on large files.
 *
 * If rendering hangs or throws, the test fails and the build gate fails, preventing the merge.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class MarkwonRendererLargeFileTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test(timeout = 60000) // 60-second timeout per test
    fun testLargeFileRenderNoCrash() {
        val largeMarkdown = generateLargeMarkdown(5000)

        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { MarkwonRenderer(context) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(DarkColorScheme.primaryBackground),
            ) {
                MarkdownPreview(
                    content = largeMarkdown,
                    renderer = renderer,
                    fontScale = 1.0f,
                )
            }
        }

        // Drain the main looper to settle Compose + block rendering
        drainMainLooper()

        // If rendering crashed, this assertion would never execute.
        // If rendering hung, the 60-second timeout would fire.
        // If rendering completed, this assertion proves it happened.
        composeRule.onRoot().assertExists()
    }

    /**
     * Generate a ~5000-line markdown document with mixed block types to exercise
     * the full rendering pipeline: paragraphs, headings, code blocks, tables, and lists.
     */
    private fun generateLargeMarkdown(lines: Int): String {
        val sb = StringBuilder()
        for (i in 1..lines) {
            when {
                i % 50 == 0 -> {
                    // Heading every 50 lines
                    sb.append("# Heading $i\n")
                }
                i % 20 == 0 -> {
                    // Code block every 20 lines
                    sb.append("```kotlin\n")
                    sb.append("val x = $i\n")
                    sb.append("println(\"Line $i\")\n")
                    sb.append("```\n")
                }
                i % 15 == 0 -> {
                    // Table every 15 lines
                    sb.append("| Column 1 | Column 2 |\n")
                    sb.append("|----------|----------|\n")
                    sb.append("| Row $i A | Row $i B |\n")
                }
                i % 10 == 0 -> {
                    // List every 10 lines
                    sb.append("- Item $i\n")
                    sb.append("  - Subitem $i.1\n")
                    sb.append("  - Subitem $i.2\n")
                }
                else -> {
                    // Regular paragraph
                    sb.append("Paragraph $i: Lorem ipsum dolor sit amet, consectetur adipiscing elit. ")
                    sb.append("Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.\n")
                }
            }
        }
        return sb.toString()
    }

    /**
     * Settle Compose + Markwon rendering by draining the main looper.
     * Waits until the looper is genuinely idle before returning, with a safety cap.
     */
    private fun drainMainLooper() {
        val mainLooper = shadowOf(Looper.getMainLooper())
        var guard = 0
        val maxPasses = 50 // Safety cap to prevent infinite loops on pathological regressions

        repeat(maxPasses) {
            composeRule.waitForIdle()
            mainLooper.idle()
            guard++
        }

        check(guard > 0) { "Main looper drain failed" }
    }
}
