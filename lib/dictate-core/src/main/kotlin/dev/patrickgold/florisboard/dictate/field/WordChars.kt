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

/**
 * The marks the keyboard's phantom space accepts on either side of a seam — the "default" rule of the
 * localization extension, for callers with no keyboard in reach. See [seamSeparator].
 */
const val SEAM_PRECEDING_DEFAULT = ".,;:?‽!&%)]}»©®™"
const val SEAM_FOLLOWING_DEFAULT = "¿⸘¡([{"

/**
 * The separator [piece] needs in front of it to sit next to [textBefore]: a single space, or nothing.
 *
 * Two dictations in a row used to be glued — `Testing 1, 2, 3.Testing one-two-three.` — because every
 * write went in exactly as transcribed. The keyboard only ever spaced a seam it made itself, through
 * the phantom space after a picked suggestion; a dictation never set that up, and the floating button
 * has no keyboard to ask at all. So this is the phantom space's own rule, asked of the text instead
 * of a flag: a space between a word (or a closing mark from [precedingSymbols]) and a word (or an
 * opening one from [followingSymbols]), and nowhere else. A mark that binds backwards (`.`, `,`) and a
 * text that already ends in whitespace both get nothing. The same rule puts an automatic snippet behind
 * its dictation (`Testing 1, 2, 3. Best regards,`), on the phone and on the watch.
 *
 * Scripts written without spaces between words — Chinese, Japanese, Thai and their neighbours — never
 * get one, on either side of the seam. That is decided from the characters rather than from the
 * keyboard's locale, because the floating button writes beside keyboards we cannot ask.
 *
 * [textBefore] null means the field could not be read, and an unread field gets no space: a stray one
 * at the start of a field is as wrong as a missing one in the middle, and only one of them is ours.
 */
fun seamSeparator(
    textBefore: String?,
    piece: String,
    precedingSymbols: String = SEAM_PRECEDING_DEFAULT,
    followingSymbols: String = SEAM_FOLLOWING_DEFAULT,
): String {
    val before = textBefore?.lastOrNull() ?: return ""
    val first = piece.firstOrNull() ?: return ""
    if (before.isWhitespace() || first.isWhitespace()) return ""
    if (isWrittenWithoutSpaces(before) || isWrittenWithoutSpaces(first)) return ""
    val closesWord = before.isLetterOrDigit() || precedingSymbols.contains(before)
    val opensWord = first.isLetterOrDigit() || followingSymbols.contains(first)
    return if (closesWord && opensWord) " " else ""
}
