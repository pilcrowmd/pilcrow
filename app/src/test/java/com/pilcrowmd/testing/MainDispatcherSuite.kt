// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.testing

/**
 * JUnit category marking a test class that mutates the **process-global**
 * `Dispatchers.Main` via `Dispatchers.setMain` / `Dispatchers.resetMain` — in practice, the ViewModel
 * barrier suites certified in PR #59/#60.
 *
 * ## Why this exists
 *
 * `setMain` is safe across Gradle forks, because forks are separate JVMs, but **not** against
 * concurrency *within* one JVM. A Compose `Recomposer` — started by `createComposeRule` /
 * `createAndroidComposeRule`, including every Roborazzi screenshot suite — keeps using
 * `Dispatchers.Main` for as long as it lives. Sharing a process with a `resetMain()` teardown yields:
 *
 * ```
 * IllegalStateException: Dispatchers.Main is used concurrently with setting it
 * ```
 *
 * This was predicted in a review of the barrier work as the one configuration that would break
 * `setMain`, and it broke the build a day later. It is nasty because it is **order-dependent** and
 * surfaces on an **unrelated suite, in TEARDOWN rather than an assertion**.
 *
 * ## The rule
 *
 * **Annotate every class that calls `Dispatchers.setMain` with
 * `@Category(MainDispatcherSuite::class)`.** Those classes run in `testMainDispatcherDebug`, a JVM of
 * their own. Everything else — including all Compose and screenshot tests — stays in
 * `testDebugUnitTest`. The two sets never share a process.
 *
 * ## Why THIS side is the isolated one
 *
 * Isolating the Compose side was tried first and **rejected on evidence**: Roborazzi's
 * `verifyRoborazziDebug` and `recordRoborazziDebug` are bound to `testDebugUnitTest`, so moving the
 * screenshot suites out left `verifyRoborazziDebug` running 393 unrelated tests and **exiting 0 while
 * verifying no goldens at all** — a silent hole exactly like the ones this project keeps catching.
 * Isolating `setMain` instead moves 3 classes rather than 8, leaves Roborazzi untouched, and keeps
 * the class names cited in the PR #59/#60 merge-gate record intact.
 *
 * Forgetting the annotation does not fail loudly — it reintroduces an order-dependent failure in some
 * other suite. If you add a `setMain` test, add the category.
 */
interface MainDispatcherSuite
