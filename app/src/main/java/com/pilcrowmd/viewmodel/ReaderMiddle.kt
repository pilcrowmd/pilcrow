// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

/**
 * Issue #15: what sits in the middle of the Read screen. [block] is the reader item there, and
 * [fraction] how far down that item the middle falls (0 = its top, 1 = its bottom), so a tall code
 * block or plain-text chunk can land the editor on the right line. [viewportHeight] is the reader's
 * height in pixels, so an Edit to Read switch can place the caret's block down the screen. The reader
 * reports it from its scroll callback, which RecyclerView also calls (with dx 0, dy 0) after a layout.
 */
data class ReaderMiddle(val block: Int, val fraction: Float, val viewportHeight: Int)

/**
 * The longest a Read to Edit switch waits to find the reader's middle line in the text (issue #15).
 * A very large Markdown document gets the nearest line found in that time.
 */
internal const val READ_EDIT_LANDING_BUDGET_NS = 200_000_000L

/**
 * The last probe started before the budget ran out may still finish; past this the switch goes ahead
 * without a landing.
 */
internal const val READ_EDIT_LANDING_GRACE_NS = 100_000_000L

/**
 * A document longer than this is not searched ahead when the reader stops: that search is a parse of
 * prefixes on a worker thread, for a switch that may never come. The search at the tap, which is
 * bounded, still runs.
 */
internal const val LANDING_PRECOMPUTE_MAX_CHARS = 2_000_000

/** The reader is taken to have stopped when it has reported no new middle for this long. */
internal const val READER_SETTLE_MILLIS = 250L

/** Edit to Read: the caret's block starts this share (one third) of the way down the Read screen. */
internal const val CARET_BLOCK_SCREEN_SHARE = 3

internal const val NANOS_PER_MILLI = 1_000_000L

/** A cached landing is used for a code block or plain chunk when the middle moved by no more than this. */
internal const val LANDING_FRACTION_TOLERANCE = 0.02f
