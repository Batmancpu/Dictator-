/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.translate

/**
 * How the translate bar's live result is kept in the app's text field (issue #424).
 *
 * The translation is ordinary text in the field, not a composing region, and every new result replaces
 * the previous one in a single edit — delete what was written last time, write the new text. That only
 * works while the text in front of the cursor still ends with what was written, so the check is made
 * against the field every time.
 *
 * If it no longer does — the user moved the cursor, the app rewrote the text, something was pasted
 * behind it — there is no edit at all (issue #433). Deleting would eat whatever happens to sit there,
 * and writing the new result at the cursor instead, which this used to do, put the whole translation
 * into the field a second time: "Hello Hello world".
 */
object TranslationInsertion {
    /** Delete [deleteBefore] characters before the cursor, then write [text]. */
    data class Edit(val deleteBefore: Int, val text: String)

    /**
     * The edit that turns the field into one showing [translation], given [previous] — exactly what the
     * last edit wrote, separator included — and [textBeforeCursor] as the field reports it now.
     * An empty [translation] removes the previous result. `null` when [previous] is no longer in front
     * of the cursor, so the field is not ours to change any more.
     */
    fun edit(textBeforeCursor: String, previous: String?, translation: String): Edit? {
        val ours = previous.orEmpty()
        if (!textBeforeCursor.endsWith(ours)) return null
        if (translation.isEmpty()) return Edit(ours.length, "")
        val before = textBeforeCursor.dropLast(ours.length)
        return Edit(ours.length, separatorAfter(before) + translation)
    }

    /** A space between the result and a word in front of it; nothing at the start or after whitespace. */
    fun separatorAfter(before: String): String =
        if (before.isEmpty() || before.last().isWhitespace()) "" else " "
}
