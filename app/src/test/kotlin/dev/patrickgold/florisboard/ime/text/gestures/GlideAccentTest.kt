/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.text.gestures

import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.keyboard.CaseSelector
import dev.patrickgold.florisboard.ime.nlp.latin.EvalKeyboard
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.lib.FlorisLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Glide typing for letters that are not plain a–z (issue #426): Hungarian `á é ö ü` on keys of their own,
 * `ő ű` only on long-press, and the same two shapes in other languages — Russian `й`, Swedish `å`, German
 * `ß`, Polish `ł`, Turkish `ı`.
 *
 * Built from the shipped assets — the layout's letter rows and the language's popup mapping — so what is
 * tested is what a user gets, and swiped along the path a finger takes rather than along whatever the
 * classifier itself considers ideal. The route a swipe takes is decided here, independently of
 * [GlideKeyMap]: a letter's own key if the layout has one, otherwise its base letter, otherwise the first
 * key that offers it.
 *
 * The first half runs on small fixed vocabularies with the real frequencies, so it holds on a clean
 * checkout. [measureAccentedWordsAcrossLanguages] runs whole dictionaries from `tools/glide-dict/dist/`
 * and skips when they are not there, like [dev.patrickgold.florisboard.ime.nlp.latin.FrenchElisionEvalTest].
 */
class GlideAccentTest {

    private companion object {
        const val ASSETS = "app/src/main/assets/ime/keyboard"
        const val WIDTH = 1080f
        const val ROW_HEIGHT = 140f
        const val TRACE_STEP = 12f
    }

    private fun repoFile(relative: String): File? {
        var dir = File(".").absoluteFile
        repeat(5) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun json(relative: String): JsonElement =
        Json.parseToJsonElement(requireNotNull(repoFile(relative)) { "missing asset $relative" }.readText())

    /** Lower and upper code of a layout or popup entry; null for anything that is not a plain key. */
    private fun codesOf(entry: JsonElement?): Pair<Int, Int>? {
        val obj = entry as? JsonObject ?: return null
        return when (obj["$"]?.jsonPrimitive?.content) {
            "case_selector" -> {
                val lower = codesOf(obj["lower"])?.first ?: return null
                val upper = codesOf(obj["upper"])?.first ?: return null
                lower to upper
            }
            null, "auto_text_key", "text_key" -> {
                val code = obj["code"]?.jsonPrimitive?.int ?: return null
                if (code <= 0) null else code to Character.toUpperCase(code)
            }
            else -> null
        }
    }

    private fun data(code: Int) = TextKeyData(type = KeyType.CHARACTER, code = code, label = String(Character.toChars(code)))

    private class Board(val keys: List<TextKey>, val locale: Locale, val subtype: Subtype) {
        /** The key printed with [letter], if the layout has one. */
        fun ownKey(letter: Char): TextKey? = keys.firstOrNull { it.glideCode() == letter.code }

        /** Where a typist goes for [letter] when nobody tells them otherwise. */
        fun naturalKey(letter: Char): TextKey? {
            val lower = letter.toString().lowercase(locale).first()
            ownKey(lower)?.let { return it }
            val base = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)[0]
            if (base != lower) ownKey(base)?.let { return it }
            return keys.firstOrNull { key ->
                (listOfNotNull(key.computedPopups.main) + key.computedPopups.relevant).any { popup ->
                    String(Character.toChars(popup.code)).lowercase(locale).first() == lower
                }
            }
        }
    }

    /**
     * The layout's letter rows, a shift and a delete key on the third row and a space bar below, laid out on a
     * 1080 px wide keyboard the way the emulator draws it, with the language's popups on each key.
     *
     * [shifted] computes the popups the way the keyboard does at a sentence start, upper half of every case
     * selector — the state the classifier often receives its keys in.
     */
    private fun board(layout: String, popups: String, language: String, shifted: Boolean = false): Board {
        val locale = Locale(language)
        val rows = json("$ASSETS/org.florisboard.layouts/layouts/characters/$layout.json").jsonArray
        val mapping = json("$ASSETS/org.florisboard.localization/popupMappings/$popups.json")
            .jsonObject["all"]?.jsonObject.orEmpty()
        val keys = mutableListOf<TextKey>()

        fun place(key: TextKey, left: Float, row: Int, width: Float) {
            key.touchBounds.apply {
                this.left = left
                top = row * ROW_HEIGHT
                right = left + width
                bottom = (row + 1) * ROW_HEIGHT
            }
            key.visibleBounds.applyFrom(key.touchBounds).deflateBy(4f, 10f)
            keys.add(key)
        }

        rows.forEachIndexed { r, rowElement ->
            val entries = (rowElement as JsonArray).mapNotNull { entry -> codesOf(entry)?.let { entry to it } }
            val withModifiers = r == rows.size - 1
            val slots = entries.size + if (withModifiers) 3 else 0
            val pitch = minOf(WIDTH / 10f, WIDTH / slots)
            var x = (WIDTH - entries.size * pitch) / 2f
            if (withModifiers) {
                place(TextKey(TextKeyData(type = KeyType.MODIFIER, code = KeyCode.SHIFT, label = "shift")), 0f, r, x)
                place(TextKey(TextKeyData(type = KeyType.ENTER_EDITING, code = KeyCode.DELETE, label = "delete")),
                    WIDTH - x, r, x)
            }
            for ((entry, codes) in entries) {
                val (lower, upper) = codes
                val raw = if ((entry as JsonObject)["$"]?.jsonPrimitive?.content == "case_selector") {
                    CaseSelector(lower = data(lower), upper = data(upper))
                } else {
                    data(lower)
                }
                val key = TextKey(raw)
                val label = String(Character.toChars(lower))
                (mapping[label] as? JsonObject)?.let { popup ->
                    fun pick(e: JsonElement?) = codesOf(e)?.let { (lo, up) -> data(if (shifted) up else lo) }
                    key.computedPopups.main = pick(popup["main"])
                    popup["relevant"]?.jsonArray?.forEach { e -> pick(e)?.let { key.computedPopups.relevant.add(it) } }
                }
                place(key, x, r, pitch)
                x += pitch
            }
        }
        place(TextKey(TextKeyData(type = KeyType.CHARACTER, code = KeyCode.SPACE, label = "space")),
            WIDTH * 0.25f, rows.size, WIDTH * 0.5f)

        val subtype = Subtype.DEFAULT.copy(id = layout.hashCode().toLong(), primaryLocale = FlorisLocale.from(language))
        return Board(keys, locale, subtype)
    }

    private class FixedVocabulary(private val freq: Map<String, Int>) : StatisticalGlideTypingClassifier.Vocabulary {
        override fun words(subtype: Subtype) = freq.keys.toList()
        override fun frequency(subtype: Subtype, word: String) = (freq[word] ?: 0) / 255.0
    }

    private fun classifier(board: Board, freq: Map<String, Int>) =
        StatisticalGlideTypingClassifier(FixedVocabulary(freq)).also { it.setLayout(board.keys, board.subtype) }

    /**
     * A finger's trace through [route]'s key for every letter of [word]: dense points along straight lines, a
     * small loop on a doubled letter, and — with [random] — each key aimed at a little off its centre.
     */
    private fun trace(
        board: Board,
        word: String,
        route: (Char) -> TextKey? = board::naturalKey,
        random: Random? = null,
    ): List<GlideTypingGesture.Detector.Position> {
        val out = mutableListOf<GlideTypingGesture.Detector.Position>()
        var last: Pair<Float, Float>? = null
        var previous: Char? = null
        for (letter in word) {
            val key = route(letter) ?: continue
            val bounds = key.visibleBounds
            val spread = 0.18f * bounds.width
            val x = bounds.center.x + (random?.let { (it.nextFloat() * 2f - 1f) * spread } ?: 0f)
            val y = bounds.center.y + (random?.let { (it.nextFloat() * 2f - 1f) * spread } ?: 0f)
            val from = last
            if (from == null) {
                out.add(GlideTypingGesture.Detector.Position(x, y))
            } else if (previous == letter) {
                val r = bounds.width / 4f
                for ((dx, dy) in listOf(r to r, r to -r, -r to -r, -r to r, 0f to 0f)) {
                    out.add(GlideTypingGesture.Detector.Position(x + dx, y + dy))
                }
            } else {
                val steps = maxOf(1, (hypot(x - from.first, y - from.second) / TRACE_STEP).toInt())
                for (i in 1..steps) {
                    val t = i.toFloat() / steps
                    out.add(GlideTypingGesture.Detector.Position(
                        from.first + (x - from.first) * t, from.second + (y - from.second) * t,
                    ))
                }
            }
            last = x to y
            previous = letter
        }
        return out
    }

    private fun StatisticalGlideTypingClassifier.swipe(points: List<GlideTypingGesture.Detector.Position>): List<String> {
        clear()
        points.forEach { addGesturePoint(it) }
        return getSuggestions(8, true).map { it.toString() }.also { clear() }
    }

    /** Route [letter] through the key printed with [via] instead of its natural one. */
    private fun Board.via(vararg pairs: Pair<Char, Char>): (Char) -> TextKey? {
        val overrides = pairs.toMap()
        return { letter -> overrides[letter]?.let { ownKey(it) } ?: naturalKey(letter) }
    }

    // ── Hungarian, the report ──────────────────────────────────────────────────────────────────────────────

    private val hungarian = mapOf(
        "állatkert" to 151, "át" to 206, "és" to 241, "én" to 231, "ötlet" to 192, "ősember" to 141,
        "első" to 205, "idő" to 200, "őrült" to 191, "örült" to 164, "működik" to 192, "okosság" to 130,
        "léha" to 133, "ismert" to 178, "ismer" to 179, "pitéket" to 131, "elkap" to 155, "ünnep" to 157,
        "kar" to 161, "kár" to 185, "sor" to 178, "sör" to 172, "ember" to 211, "ok" to 192, "orvos" to 187,
        "ló" to 173, "lő" to 163,
    )

    @Test
    fun `a word that starts or ends on a key of its own is found`() {
        val board = board("hungarian", "hu", "hu")
        val glide = classifier(board, hungarian)
        for (word in listOf("állatkert", "át", "és", "én", "ötlet", "okosság", "működik")) {
            assertEquals(word, glide.swipe(trace(board, word)).firstOrNull(), "swiping $word")
        }
    }

    @Test
    fun `a long-press letter is found through either key that offers it`() {
        val board = board("hungarian", "hu", "hu")
        val glide = classifier(board, hungarian)
        for (word in listOf("ősember", "első", "idő", "őrült", "lő")) {
            assertEquals(word, glide.swipe(trace(board, word, board.via('ő' to 'ö'))).firstOrNull(), "$word through ö")
        }
        for (word in listOf("ősember", "első", "idő", "őrült")) {
            assertEquals(word, glide.swipe(trace(board, word, board.via('ő' to 'o'))).firstOrNull(), "$word through o")
        }
        // Through o, `lő` draws exactly the path of `ló` — `ó` lives on that key too — and the commoner word
        // wins. That is the price of accepting the base key, and why it is the strip's second word, not missing.
        val throughO = glide.swipe(trace(board, "lő", board.via('ő' to 'o')))
        assertEquals("ló", throughO.firstOrNull())
        assertTrue("lő" in throughO, "lő is offered next to ló: $throughO")

        assertEquals("működik", glide.swipe(trace(board, "működik", board.via('ű' to 'ü'))).firstOrNull())
    }

    @Test
    fun `a key of its own still tells two words apart that differ only in the accent`() {
        val board = board("hungarian", "hu", "hu")
        val glide = classifier(board, hungarian)
        for (word in listOf("kar", "kár", "sor", "sör", "ló")) {
            assertEquals(word, glide.swipe(trace(board, word)).firstOrNull(), "swiping $word")
        }
    }

    // ── The same two shapes elsewhere ──────────────────────────────────────────────────────────────────────

    @Test
    fun `Russian words ending in the й key are found`() {
        val board = board("jcuken_russian", "ru", "ru")
        val glide = classifier(board, mapOf(
            "какой" to 207, "мой" to 221, "твой" to 213, "который" to 211, "другой" to 203, "мои" to 210,
            "как" to 239, "кто" to 224,
        ))
        for (word in listOf("какой", "мой", "твой", "который", "другой")) {
            assertEquals(word, glide.swipe(trace(board, word)).firstOrNull(), "swiping $word")
        }
    }

    @Test
    fun `a letter that exists only on long-press is found through that key`() {
        val german = board("qwertz", "de", "de")
        val de = classifier(german, mapOf(
            "weiß" to 227, "groß" to 198, "heiß" to 192, "weist" to 171, "weis" to 163, "weise" to 192,
            "wer" to 222, "was" to 243, "über" to 221,
        ))
        for (word in listOf("weiß", "groß", "heiß", "über")) {
            assertEquals(word, de.swipe(trace(german, word)).firstOrNull(), "swiping $word")
        }

        val polish = board("qwerty", "pl", "pl")
        val pl = classifier(polish, mapOf(
            "był" to 222, "miał" to 211, "mógł" to 204, "ładny" to 181, "bym" to 203, "byk" to 155, "być" to 224,
        ))
        for (word in listOf("był", "miał", "mógł", "ładny")) {
            assertEquals(word, pl.swipe(trace(polish, word)).firstOrNull(), "swiping $word")
        }
    }

    private val turkish = mapOf(
        "bir" to 255, "ışık" to 186, "için" to 241, "bu" to 249, "iyi" to 231, "ılık" to 155, "şık" to 171,
        "sık" to 191, "çok" to 240,
    )

    @Test
    fun `Turkish dotless i is found on the long-press of i, even with the shift on`() {
        for (shifted in listOf(false, true)) {
            val board = board("qwerty", "tr", "tr", shifted = shifted)
            val glide = classifier(board, turkish)
            for (word in listOf("ışık", "ılık", "bir", "iyi", "çok")) {
                assertEquals(word, glide.swipe(trace(board, word)).firstOrNull(), "swiping $word, shifted=$shifted")
            }
        }
    }

    @Test
    fun `Turkish Q's two i keys are case selectors and still keys`() {
        val board = board("turkish_q", "tr", "tr")
        val glide = classifier(board, turkish)
        for (word in listOf("ışık", "ılık", "bir", "iyi", "için")) {
            assertEquals(word, glide.swipe(trace(board, word)).firstOrNull(), "swiping $word")
        }
    }

    // ── The measurement ───────────────────────────────────────────────────────────────────────────────────

    private data class Lang(val code: String, val layout: String, val popups: String)

    /**
     * Every language whose layout has a key of its own for an accented letter, or a letter only a popup
     * offers, swiped across its 1500 most frequent words with each key aimed a little off-centre. "Edge" is
     * the population this issue is about: words whose first or last letter is such a letter.
     */
    @Test
    fun measureAccentedWordsAcrossLanguages() {
        val langs = listOf(
            Lang("hu", "hungarian", "hu"), Lang("ru", "jcuken_russian", "ru"), Lang("uk", "jcuken_ukrainian", "uk"),
            Lang("fi", "swedish_finnish", "fi"), Lang("sv", "swedish_finnish", "sv"), Lang("sl", "slovenian", "sl-SI"),
            Lang("fr", "canadian_french", "fr"), Lang("de", "qwertz", "de"), Lang("pl", "qwerty", "pl"),
            Lang("tr", "qwerty", "tr"), Lang("en", "qwerty", "en"),
        )
        println("\n=== #426 · glide typing, accented letters ===")
        println("lang  words  top-1    edge words  edge top-1  edge nothing  ms/swipe")
        for (lang in langs) {
            val dictFile = repoFile("tools/glide-dict/dist/${lang.code}.json")
                ?: repoFile("app/src/main/assets/ime/dict/${lang.code}.json")
            if (dictFile == null) {
                println("${lang.code.padEnd(5)} skipped: no dictionary")
                continue
            }
            val freq = EvalKeyboard.parseDict(dictFile.readText())
            val board = board(lang.layout, lang.popups, lang.code)
            val glide = classifier(board, freq)
            // A letter with a key of its own that Unicode does not decompose: what the classifier always handled.
            fun plain(c: Char): Boolean {
                val lower = c.toString().lowercase(board.locale).first()
                return board.ownKey(lower) != null &&
                    Normalizer.normalize(lower.toString(), Normalizer.Form.NFD).length == 1
            }
            val sample = freq.entries
                .filter { (w, _) -> w.length >= 2 && w.all { it.isLetter() } && w.all { board.naturalKey(it) != null } }
                .sortedByDescending { it.value }
                .take(1500)
                .map { it.key }
            val random = Random(426)
            var hits = 0
            var edge = 0
            var edgeHits = 0
            var edgeNothing = 0
            val edgeMisses = mutableListOf<String>()
            val started = System.nanoTime()
            for (word in sample) {
                val result = glide.swipe(trace(board, word, random = random))
                val hit = result.firstOrNull()?.equals(word, ignoreCase = true) == true
                if (hit) hits++
                if (!plain(word.first()) || !plain(word.last())) {
                    edge++
                    if (hit) edgeHits++ else edgeMisses.add("$word→${result.firstOrNull() ?: "∅"}")
                    if (result.isEmpty()) edgeNothing++
                }
            }
            val ms = (System.nanoTime() - started) / 1e6 / sample.size
            fun pct(a: Int, b: Int) = if (b == 0) "    –" else "%5.1f%%".format(100.0 * a / b)
            println("%-5s %5d  %s    %5d       %s      %s       %5.1f".format(
                lang.code, sample.size, pct(hits, sample.size), edge, pct(edgeHits, edge), pct(edgeNothing, edge), ms,
            ))
            if (edgeMisses.isNotEmpty()) println("      edge misses: ${edgeMisses.take(10).joinToString(" ")}")

            // Floors, not targets: measured 91–100 % on the edge words and 96–99 % overall when this was written,
            // against 0–76 % and 82–94 % before (issue #426).
            assertEquals(0, edgeNothing, "${lang.code}: an edge word swiped and nothing came back")
            if (edge > 0) assertTrue(edgeHits >= 0.88 * edge, "${lang.code}: edge words ${pct(edgeHits, edge)}")
            assertTrue(hits >= 0.95 * sample.size, "${lang.code}: overall ${pct(hits, sample.size)}")
        }
    }
}
