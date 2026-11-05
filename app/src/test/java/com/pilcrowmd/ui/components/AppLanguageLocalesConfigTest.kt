// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Languages spec D1: the Settings Language choices and the languages the app declares to Android
 * (`res/xml/locales_config.xml`) are one list. A tag in one and not the other would offer a language
 * the system does not know, or hide one it does.
 */
class AppLanguageLocalesConfigTest {

    private val config = File("src/main/res/xml/locales_config.xml")

    @Test
    fun `the picker tags equal locales_config in order`() {
        assertTrue("run from the app module: ${config.absolutePath}", config.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(config)
        val locales = doc.getElementsByTagName("locale")
        val declared = (0 until locales.length).map {
            locales.item(it).attributes.getNamedItem("android:name").nodeValue
        }
        assertEquals(AppLanguage.entries.mapNotNull { it.tag }, declared)
    }

    @Test
    fun `System default is the first choice`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.entries.first())
    }

    @Test
    fun `fromTag maps exact and longer tags to their choice and the rest to System default`() {
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromTag("de"))
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromTag("de-DE"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromTag("zh-CN"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromTag("zh-Hans-CN"))
        // Only the language part is compared: Simplified is the only Chinese declared, so Traditional lands on it.
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromTag("zh-TW"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("xx"))
    }
}
