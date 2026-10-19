// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** M-174: days past the release-prep date before the update note may show. */
const val UPDATE_NOTE_MIN_DAYS = 60

/** M-174: days a dismissal hides the note for. */
const val UPDATE_NOTE_SNOOZE_DAYS = 14

/**
 * Whether the "this version is over 2 months old" note is due on the welcome screen. **M-174.**
 *
 * Hidden when the release date is unknown. Otherwise due from [UPDATE_NOTE_MIN_DAYS] after
 * [releaseMillis], unless it was dismissed less than [UPDATE_NOTE_SNOOZE_DAYS] ago. A device clock
 * earlier than the release gives a negative age, which the same comparison hides.
 */
fun isUpdateNoteDue(releaseMillis: Long?, nowMillis: Long, dismissedAtMillis: Long?): Boolean {
    if (releaseMillis == null) return false
    if (nowMillis - releaseMillis < UPDATE_NOTE_MIN_DAYS * DAY_MILLIS) return false
    return dismissedAtMillis == null || nowMillis - dismissedAtMillis >= UPDATE_NOTE_SNOOZE_DAYS * DAY_MILLIS
}

/** An ISO `YYYY-MM-DD` date as UTC midnight in epoch millis, or null if it does not parse. */
fun utcMidnightMillis(isoDate: String): Long? = runCatching {
    LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()

/**
 * The version as Settings → About shows it, e.g. `1.0.11 · October 2026` (M-174). The month is
 * taken in UTC, so the same build shows the same month in every timezone. Without a date it is the
 * bare version name.
 */
fun versionLabel(versionName: String, releaseMillis: Long?): String {
    if (versionName.isEmpty() || releaseMillis == null) return versionName
    val month = Instant.ofEpochMilli(releaseMillis).atZone(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
    return "$versionName · $month"
}
