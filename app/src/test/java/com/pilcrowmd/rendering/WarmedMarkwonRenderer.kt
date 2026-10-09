// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context

/**
 * A [MarkwonRenderer] whose init{} font pre-warm has finished, for any test that renders through it.
 *
 * M-07 / M-114: until M-114 the pre-warm parsed `$$1$$` on a background thread, on the same Markwon
 * instance the reader uses for Dark at 100%. Markwon's inline processors keep per-parse state in
 * shared fields, so a test parse that overlapped it could throw (`Range [1, 28) out of bounds for
 * length 5`) or quietly build a different tree, which a golden then reported as a changed image. The
 * pre-warm now only builds that instance and warms the math library without parsing, so the race is
 * gone at its source; waiting here still starts each test with the instance built and the thread
 * finished. Held by [MarkwonPreWarmRaceTest].
 */
internal fun warmedMarkwonRenderer(context: Context): MarkwonRenderer =
    MarkwonRenderer(context).also { it.awaitFontPreWarm() }
