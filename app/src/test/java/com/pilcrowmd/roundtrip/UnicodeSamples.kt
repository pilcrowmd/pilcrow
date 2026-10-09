// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.roundtrip

/**
 * Generated Unicode documents for the round-trip corpus, plus the byte-level non-UTF-8 fixtures.
 * Invisible or easily-confused code points are written as escapes so the source says what the file
 * holds; visible text is written literally.
 */
internal object UnicodeSamples {

    private const val ZWJ = "\u200D"
    private const val RLM = "\u200F"
    private const val LRM = "\u200E"
    private const val RLI = "\u2067"
    private const val PDI = "\u2069"
    private const val VS16 = "\uFE0F"

    // Emoji built from code points, so each sequence is exactly what its name says.
    private val family = listOf(0x1F468, 0x1F469, 0x1F467, 0x1F466).joinToString(ZWJ) { cp(it) }
    private val flagPl = cp(0x1F1F5) + cp(0x1F1F1)
    private val flagJp = cp(0x1F1EF) + cp(0x1F1F5)
    private val flagUa = cp(0x1F1FA) + cp(0x1F1E6)
    private val flagEngland = cp(0x1F3F4) +
        listOf(0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F).joinToString("") { cp(it) }
    private val rainbowFlag = cp(0x1F3F3) + VS16 + ZWJ + cp(0x1F308)
    private val thumbsDark = cp(0x1F44D) + cp(0x1F3FF)
    private const val KEYCAP_HASH = "#" + VS16 + "\u20E3"
    private val womanTechnologist = cp(0x1F469) + cp(0x1F3FD) + ZWJ + cp(0x1F4BB)
    private val heartOnFire = "❤" + VS16 + ZWJ + cp(0x1F525)

    private const val ARABIC = "مرحبا بالعالم. هذا نص تجريبي باللغة العربية مع أرقام ١٢٣ و 456."
    private const val HEBREW = "שלום עולם. זהו טקסט לדוגמה בעברית עם ניקוד: ב\u05B0\u05BCר\u05B5אש\u05B4\u05C1ית."
    private const val PERSIAN = "این یک متن آزمایشی است."

    val all: List<Pair<String, String>> = listOf(
        "rtl-arabic" to "# $ARABIC\n\n$ARABIC\n\n- $ARABIC\n- **$ARABIC**\n\n> $ARABIC\n",
        "rtl-hebrew" to "# $HEBREW\n\n$HEBREW\n\n1. $HEBREW\n2. `$HEBREW`\n",
        "rtl-mixed-bidi-controls" to
            "English then $RLI$ARABIC$PDI then English.\n$RLM$HEBREW$LRM (123)\n" +
            "[$HEBREW](https://example.org/$HEBREW) and $PERSIAN\n",
        "rtl-crlf" to "# $ARABIC\r\n\r\n$HEBREW\r\n",
        "emoji-zwj-and-flags" to
            "# Emoji $family\n\nFamily: $family\nFlags: $flagPl $flagJp $flagUa $flagEngland $rainbowFlag\n" +
            "Skin tone: $thumbsDark, keycap: $KEYCAP_HASH, profession: $womanTechnologist, $heartOnFire\n" +
            "| a | b |\n|---|---|\n| $family | $flagEngland |\n",
        "emoji-dense" to (0x1F600..0x1F64F).joinToString("") { cp(it) } + "\n" +
            (0x1F300..0x1F3FF step 3).joinToString(" ") { cp(it) } + "\n",
        "emoji-no-final-newline" to "End on a flag $flagPl$ZWJ",
        "combining-nfd-vs-nfc" to
            // The same words precomposed (NFC) and decomposed (NFD). A save that normalised would
            // collapse one form into the other; both must survive untouched.
            "NFC: \u00E9t\u00E9 \u00C5ngstr\u00F6m \u1EC7\n" +
            "NFD: e\u0301te\u0301 A\u030Angstro\u0308m e\u0323\u0302\n",
        "combining-stacked" to
            "Z\u0335\u0321\u0328a\u0336\u0334l\u0337g\u0338\u0327o\u0360\u0361 " +
            "text a\u0300\u0301\u0302\u0303\u0304\u0305\u0306\n",
        "combining-scripts" to
            "Devanagari: नमस\u094Dत\u0947\n" +
            "Hangul jamo: \u1100\u1161\u11A8 vs 각\n" +
            "Thai: สว\u0E31สด\u0E35\n" +
            "Vietnamese: Tiếng Việt\n",
        "supplementary-planes" to
            "Math: ${(0x1D400..0x1D419).joinToString("") { cp(it) }}\n" +
            "CJK ext B: ${(0x20000..0x20010).joinToString("") { cp(it) }}\n" +
            "Old italic: ${(0x10300..0x10310).joinToString("") { cp(it) }}\n",
        "special-code-points" to
            // All valid UTF-8, all things a text pipeline is tempted to strip or rewrite.
            "NUL[\u0000] LS[\u2028] PS[\u2029] midBOM[\uFEFF] ZWSP[\u200B] NBSP[\u00A0]\n" +
            "Replacement char written on purpose [\uFFFD] and noncharacters [\uFFFE][\uFFFF]\n" +
            "Soft hyphen[\u00AD] word joiner[\u2060] ideographic space[\u3000]\n",
        "cjk-mixed" to "# 日本語の見出し\n\n中文段落，包含标点。한국어 문장입니다.\n\n```\n全角\u3000スペース\n```\n",
        "unicode-crlf-bom" to "\uFEFF# Ünïcödé\r\n\r\n$family $ARABIC\r\n",
    )

    /** Short samples reused as table cells. */
    val cellSamples: List<Pair<String, String>> = listOf(
        "Arabic" to ARABIC.take(12),
        "Hebrew" to HEBREW.take(12),
        "Emoji" to "$family $flagPl",
        "Combining" to "e\u0301 A\u030A",
    )

    private fun cp(codePoint: Int): String = String(Character.toChars(codePoint))
}

/**
 * Byte-level documents that are NOT valid UTF-8. `LocalFileRepository.readFile` decodes as UTF-8,
 * so each of these is expected to fail the byte-identity test (see RoundTripFindingsTest).
 */
internal object NonUtf8Samples {

    /** Windows-1252: smart quotes, euro, dashes, ellipsis and every byte in 0x80..0x9F. */
    val windows1252: ByteArray =
        latin("Café menu: ") + bytes(0x93) + latin("quoted") + bytes(0x94) + latin(" costs ") + bytes(0x80) +
            latin("5 ") + bytes(0x96) + latin(" long") + bytes(0x97) + latin("dash") + bytes(0x85) +
            latin("\n\nAll: ") +
            ByteArray(0x20) { (0x80 + it).toByte() } + latin("\n")

    /** ISO-8859-1: accented Latin letters and every byte in 0xA0..0xFF. */
    val latin1: ByteArray =
        latin("# Straße und Käse\n\nGrüße aus München © 2026, 20°C.\n\nAll: ") +
            ByteArray(0x60) { (0xA0 + it).toByte() } + latin("\n")

    /** UTF-16 little-endian with a byte-order mark, as some Windows editors save "Unicode" text. */
    val utf16leBom: ByteArray =
        bytes(0xFF, 0xFE) + "# Title\r\n\r\nPlain text saved as UTF-16 LE, café.\r\n".toByteArray(Charsets.UTF_16LE)

    /** UTF-8 cut off in the middle of a multi-byte character at end of file (a truncated copy). */
    val truncatedUtf8: ByteArray = "Price: 5 ".toByteArray(Charsets.UTF_8) + bytes(0xE2, 0x82)

    private fun latin(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)
    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }
}
