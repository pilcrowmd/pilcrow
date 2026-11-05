// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

/**
 * The Settings Language choices (languages spec D1). The tags equal res/xml/locales_config.xml
 * (AppLanguageLocalesConfigTest). A language's name is written in that language and is never
 * translated, so it is a literal here, not a string resource.
 */
enum class AppLanguage(val tag: String?, val nativeName: String?) {
    SYSTEM(null, null),
    ENGLISH("en", "English"),
    GERMAN("de", "Deutsch"),
    FRENCH("fr", "Français"),
    SPANISH("es", "Español"),
    CHINESE_SIMPLIFIED("zh-CN", "中文 (简体)"),
    ITALIAN("it", "Italiano"),
    UKRAINIAN("uk", "Українська"),
    HINDI("hi", "हिन्दी"),
    ;

    companion object {
        /**
         * The choice for a stored tag. AppCompat may hand back a longer tag than the one set ("de-DE",
         * "zh-Hans-CN"), so the match is on the language part alone; no tag or an unknown one is [SYSTEM].
         * Only the language part is compared, so "zh-Hant" and "zh-TW" resolve to 中文 (简体): Simplified is
         * the only Chinese the app declares. A multi-tag value ("de,fr") has the language "de,fr" and gives [SYSTEM].
         */
        fun fromTag(tag: String?): AppLanguage {
            val language = tag?.substringBefore('-') ?: return SYSTEM
            return entries.firstOrNull { it.tag?.substringBefore('-') == language } ?: SYSTEM
        }
    }
}
