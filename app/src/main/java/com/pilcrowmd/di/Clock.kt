// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.di

/**
 * Wall-clock time, injected so date-gated behaviour can be tested at an exact boundary.
 *
 * Provided by [AppContainer]. Only code written for M-177/M-174 reads it; the existing
 * `System.currentTimeMillis()` call sites are deliberately left alone (M-174 *Shape*).
 */
fun interface Clock {
    /** Milliseconds since the epoch, by the device clock. */
    fun nowMillis(): Long
}
