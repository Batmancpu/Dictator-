/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.dictionary

import dev.patrickgold.florisboard.ime.nlp.latin.DictFold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Re-keying the learned store after a language's fold changed (issue #451). The failure it prevents is
 * silent: a Polish word learned before Polish folded sits under `krzyś` while the keyboard asks for
 * `krzys`, and simply stops being offered.
 */
class LearnedRefoldTest {

    private val fold = { lang: String, word: String -> DictFold.foldKey(lang, word) }

    private fun word(id: Long, word: String, key: String, count: Int, lastUsed: Long, lang: String = "pl", promoted: Boolean = false) =
        LearnedWordEntry(id = id, word = word, key = key, lang = lang, count = count, lastUsed = lastUsed, promoted = promoted)

    @Test
    fun `a row filed under its old key is moved to the new one`() {
        val plan = LearnedRefold.words(listOf(word(1, "Krzyś", "krzyś", 3, 100)), fold)
        assertEquals(listOf(word(1, "Krzyś", "krzys", 3, 100)), plan.updates)
        assertTrue(plan.deletes.isEmpty())
    }

    /** Same merge as the v1→v2 migration: counts added, the newest spelling kept, promoted if any was. */
    @Test
    fun `rows that now share a key are merged into the newest`() {
        val plan = LearnedRefold.words(
            listOf(word(1, "Kasia", "kasia", 2, 100, promoted = true), word(2, "Kasią", "kasią", 3, 200)),
            fold,
        )
        assertEquals(listOf(word(2, "Kasią", "kasia", 5, 200, promoted = true)), plan.updates)
        assertEquals(listOf(1L), plan.deletes)
    }

    @Test
    fun `rows already under the right key are left alone`() {
        val rows = listOf(
            word(1, "Prateek", "prateek", 4, 100, lang = "en"),
            word(2, "Krzys", "krzys", 1, 100),
            word(3, "hôtel", "hotel", 2, 100, lang = "fr"),
        )
        val plan = LearnedRefold.words(rows, fold)
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.deletes.isEmpty())
    }

    /** Words of different languages never merge, even where their keys meet. */
    @Test
    fun `languages stay apart`() {
        val plan = LearnedRefold.words(
            listOf(word(1, "Łódź", "łódź", 2, 100), word(2, "lodz", "lodz", 1, 200, lang = "en")),
            fold,
        )
        assertEquals(listOf(word(1, "Łódź", "lodz", 2, 100)), plan.updates)
        assertTrue(plan.deletes.isEmpty())
    }

    @Test
    fun `pairs are re-keyed by their previous word and merged`() {
        val rows = listOf(
            LearnedBigramEntry(id = 1, prev = "dzięki", word = "Kasiu", lang = "pl", count = 2, lastUsed = 100),
            LearnedBigramEntry(id = 2, prev = "dzieki", word = "Kasiu", lang = "pl", count = 1, lastUsed = 300),
            LearnedBigramEntry(id = 3, prev = "thanks", word = "Kasia", lang = "en", count = 1, lastUsed = 300),
        )
        val plan = LearnedRefold.bigrams(rows, fold)
        assertEquals(
            listOf(LearnedBigramEntry(id = 2, prev = "dzieki", word = "Kasiu", lang = "pl", count = 3, lastUsed = 300)),
            plan.updates,
        )
        assertEquals(listOf(1L), plan.deletes)
    }
}
