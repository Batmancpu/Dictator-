/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.field

/**
 * Whether [c] can be part of a word: a letter, a digit or a combining mark (a Devanagari vowel sign is a
 * mark, not a letter), or an apostrophe or hyphen joining two of them.
 */
fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c in WORD_JOINERS || when (Character.getType(c)) {
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}

private const val WORD_JOINERS = "'’-_"

/**
 * Whether [c] belongs to a script written without spaces between its words — Chinese, Japanese, Thai and
 * their neighbours. There a run of letters is a sentence rather than a word, and no space ever goes in.
 */
fun isWrittenWithoutSpaces(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.THAI,
    Character.UnicodeScript.LAO,
    Character.UnicodeScript.KHMER,
    Character.UnicodeScript.MYANMAR,
    Character.UnicodeScript.TIBETAN,
    -> true
    else -> false
}
