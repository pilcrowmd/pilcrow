// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * S8-A74: every `res/values-xx/strings.xml` is a complete, faithful translation of
 * `res/values/strings.xml`.
 *
 * Per language it checks: the key set equals the English one (minus `translatable="false"`), every
 * plurals block has exactly the quantities the language needs, every text keeps the English
 * placeholders (`%1$s`, `%d`, `%%`, `\n`), no text is left in English unless it is in
 * [IDENTICAL_OK], and no text contains an em or en dash (house style). Parsing the file is the
 * XML validity check.
 */
class TranslationsTest {

    private val res = File("src/main/res")

    @Test
    fun `every translation matches the English keys, placeholders, plurals and house style`() {
        assertTrue("run from the app module: ${res.absolutePath}", res.isDirectory)
        val english = read(File(res, "values/strings.xml")).filterValues { !it.untranslatable }
        val problems = LANGUAGES.flatMap { (folder, quantities) -> check(folder, quantities, english) }
        assertTrue(
            "${problems.size} translation problem(s):\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    private fun check(folder: String, quantities: List<String>, english: Map<String, Entry>): List<String> {
        val file = File(res, "$folder/strings.xml")
        if (!file.isFile) return listOf("$folder: file missing")
        val translated = read(file)
        val problems = mutableListOf<String>()
        (english.keys - translated.keys).forEach { problems += "$folder: missing key $it" }
        (translated.keys - english.keys).forEach { problems += "$folder: unknown key $it" }
        for ((key, entry) in translated) {
            val source = english[key] ?: continue
            if (entry.quantities != null && entry.quantities.sorted() != quantities.sorted()) {
                problems += "$folder: $key has quantities ${entry.quantities}, needs $quantities"
            }
            entry.texts.forEachIndexed { i, text ->
                val reference = source.texts.getOrElse(i) { source.texts.last() }
                problems += checkText(folder, key, text, reference, source.texts)
            }
        }
        return problems
    }

    private fun checkText(folder: String, key: String, text: String, reference: String, sources: List<String>) =
        buildList {
            if (placeholders(text) != placeholders(reference)) {
                add("$folder: $key placeholders differ: '$text' vs '$reference'")
            }
            if (text.contains('—') || text.contains('–')) add("$folder: $key contains a dash: '$text'")
            val allowed = IDENTICAL_OK[key].orEmpty()
            if (text in sources && folder !in allowed && ALL !in allowed) {
                add("$folder: $key is still English: '$text'")
            }
            if (key in LETTERS && text != reference) add("$folder: $key must stay '$reference' (D4), is '$text'")
        }

    private class Entry(val texts: List<String>, val quantities: List<String>?, val untranslatable: Boolean)

    private fun read(file: File): Map<String, Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = linkedMapOf<String, Entry>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> {
                    val untranslatable = node.getAttribute("translatable") == "false"
                    out[name] = Entry(listOf(node.textContent), null, untranslatable)
                }
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    val quantities = (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }
                    val texts = (0 until items.length).map { items.item(it).textContent }
                    out[name] = Entry(texts, quantities, false)
                }
            }
        }
        return out
    }

    /** The placeholders of a text as a sorted list, so order-free and count-exact. */
    private fun placeholders(text: String): List<String> {
        val found = PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()
        return if (text.contains("\\n")) found + "\\n" else found
    }

    private companion object {
        val PLACEHOLDER = Regex("""%(\d+\$)?[sd%]""")

        /** Resource folder to the plural quantities Android expects for that language (CLDR). */
        val LANGUAGES: Map<String, List<String>> = mapOf(
            "values-de" to listOf("one", "other"),
            "values-fr" to listOf("one", "many", "other"),
            "values-es" to listOf("one", "many", "other"),
            "values-zh-rCN" to listOf("other"),
            "values-it" to listOf("one", "many", "other"),
            "values-uk" to listOf("one", "few", "many", "other"),
            "values-hi" to listOf("one", "other"),
        )

        const val ALL = "*"

        /** The N and U marks stay as they are in every language (languages spec D4). */
        val LETTERS = setOf("files_mark_new_letter", "files_mark_updated_letter")

        /**
         * Keys whose text may equal the English one, and in which folders: brand names, symbols and
         * numbers everywhere ([ALL]); words that happen to be the same only in the languages named.
         */
        val IDENTICAL_OK: Map<String, Set<String>> = mapOf(
            "app_name" to setOf(ALL), "settings_theme_amoled" to setOf(ALL),
            "settings_credit_fonts" to setOf(ALL), "settings_about_version" to setOf(ALL),
            "search_match_count" to setOf(ALL), "search_match_count_none" to setOf(ALL),
            "search_match_count_capped" to setOf(ALL),
            "main_pane_title" to setOf(ALL), "editor_accessibility_name_with_file" to setOf(ALL),
            "preview_scroll_percent" to setOf(ALL), "settings_percent" to setOf(ALL),
            "licenses_version_and_license" to setOf(ALL), "files_mark_new_letter" to setOf(ALL),
            "files_mark_updated_letter" to setOf(ALL), "banner_ok" to setOf(ALL),
            "files_minutes_short" to setOf(ALL), "files_hours_short" to setOf(ALL),
            "callout_title_note" to setOf("values-fr"), "callout_title_important" to setOf("values-fr"),
            "render_image_label_no_alt" to setOf("values-fr"), "files_minutes_long" to setOf("values-fr"),
            "files_days_short" to setOf("values-es"),
            "format_link" to setOf("values-de", "values-it"),
            "main_pane_editor" to setOf("values-de", "values-es", "values-it"),
            "settings_section_feedback" to setOf("values-de", "values-it"),
            "main_open_file_description" to setOf("values-it"),
            "main_open_file_description_plain" to setOf("values-it"),
            "details_default_summary" to setOf("values-de"), "render_font_set_modern" to setOf("values-de"),
        )
    }
}
