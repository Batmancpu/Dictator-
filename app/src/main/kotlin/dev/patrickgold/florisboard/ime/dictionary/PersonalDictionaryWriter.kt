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

/**
 * Adding words to the personal dictionary in bulk, from wherever they came.
 *
 * Extracted out of [ContactNameImport] when the custom-words importer (issue #389) needed the same
 * three lines: a dictate settings screen reaching for something called `ContactNameImport` would have
 * read as a mistake every time somebody came across it, and the rule being applied — no language, case
 * ignored, already-known words skipped — has nothing to do with where the words came from.
 */
object PersonalDictionaryWriter {
    /**
     * Writes [words] to [dao] and returns those that were actually new, in order.
     *
     * `locale = null` is what the dictionary calls "all languages", which is right for a name or a
     * piece of jargon: it belongs to the user rather than to the language the keyboard happens to be
     * set to. The comparison is case-insensitive because "Bondur" and "bondur" are the same word to
     * everything downstream, and a second row would only clutter the list.
     */
    fun addWords(dao: UserDictionaryDao, words: List<String>): List<String> {
        val added = mutableListOf<String>()
        for (word in words) {
            val known = runCatching {
                dao.query(word).any { it.word.equals(word, ignoreCase = true) }
            }.getOrDefault(true)
            if (known) continue
            runCatching {
                dao.insert(
                    UserDictionaryEntry(
                        id = 0,
                        word = word,
                        freq = FREQUENCY_MAX,
                        locale = null,
                        shortcut = null,
                    ),
                )
            }.onSuccess { added.add(word) }
        }
        return added
    }
}
