// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import java.util.concurrent.atomic.AtomicLong

/**
 * An opaque identity for one [Document] **instance** (M-147).
 *
 * **A URI is not an identity for a document that has no URI.** Two unsaved documents both have
 * `uri == null`, so a `null == null` comparison passes and a Save-As can bind its URI to a
 * *different* blank document — the text reaches disk, the screen shows the other document bound
 * to that file, and its next save overwrites the user's work. Comparing an opaque id is the only
 * thing that distinguishes them.
 *
 * **Minted once per instance and never reused.** `copy()` carries it, so typing does NOT change
 * identity — a Save-As with concurrent typing must still adopt, and only a genuinely different
 * document must be refused.
 *
 * Deliberately opaque: the value is private and carries no meaning. Nothing may order, log or
 * persist it, so nothing can come to depend on how it is generated.
 *
 * Lives in its own file, in this package, on purpose — `MarkdownViewModel.kt` cannot take a new
 * import without shifting the two lines `app/config/ktlint/baseline.xml` pins BY NUMBER.
 */
@JvmInline
value class DocumentId private constructor(private val serial: Long) {
    companion object {
        private val counter = AtomicLong(0)

        /** A fresh identity. Process-local; never persisted, never compared across processes. */
        fun mint(): DocumentId = DocumentId(counter.incrementAndGet())
    }
}
