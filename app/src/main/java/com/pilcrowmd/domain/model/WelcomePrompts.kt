// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.model

/** One day in milliseconds. */
const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

/** M-177: documents opened before the rating card may show. */
const val RATING_CARD_MIN_OPENS = 5

/** M-177: days since the original install before the rating card may show. */
const val RATING_CARD_MIN_DAYS = 7

/**
 * Whether the one-time "Enjoying PilcrowMD?" card is due on the welcome screen. **M-177.**
 *
 * All must hold: Google Play installed the app; [opens] reached [RATING_CARD_MIN_OPENS]; at least
 * [RATING_CARD_MIN_DAYS] have passed since [firstInstallMillis]; neither button was ever tapped; and
 * the M-174 update note is not due — when both are due, only the note shows. An unknown
 * [firstInstallMillis] (null) hides it: when in doubt, hide.
 */
@Suppress("LongParameterList") // Each input is one of the ruled gates; bundling them would hide which.
fun isRatingCardDue(
    isPlayInstall: Boolean,
    opens: Int,
    firstInstallMillis: Long?,
    nowMillis: Long,
    dismissed: Boolean,
    updateNoteDue: Boolean,
): Boolean = isPlayInstall &&
    opens >= RATING_CARD_MIN_OPENS &&
    firstInstallMillis != null &&
    nowMillis - firstInstallMillis >= RATING_CARD_MIN_DAYS * DAY_MILLIS &&
    !dismissed &&
    !updateNoteDue
