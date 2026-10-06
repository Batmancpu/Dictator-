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
 * Where a live dictation carries on once the user has taken part of it over (issue #421).
 *
 * A realtime dictation types its growing transcript into the field and keeps rewriting the end of it as
 * the provider settles its words. When the user edits that text — deletes a word somebody else said,
 * clears the field — everything they saw becomes theirs: the dictation never revises it again and writes
 * only what is said after it, at the cursor.
 *
 * That needs the point in a *later* transcript where the taken-over one ends, and by then the provider
 * has usually revised the words around that point: the partial "the taste" settles as "The test.", a
 * filler word disappears. So the end is found by alignment rather than by length.
 */
object TranscriptHandover {

    /**
     * What of [transcript] comes after [handedOver], without the whitespace in front of it: the separator
     * to the field is the caller's to decide. All of [transcript] when nothing was handed over.
     *
     * A word [handedOver] ends inside of ("dicta" of "dictation") was taken over as it stood, and its rest
     * is not written either: the user has deleted it, or moved the cursor, and a word's tail would land on
     * its own wherever the cursor now is.
     */
    fun continuation(handedOver: String, transcript: String): String {
        if (handedOver.isEmpty()) return transcript
        val end = if (transcript.startsWith(handedOver, ignoreCase = true)) {
            handedOver.length
        } else {
            alignedEnd(handedOver, transcript)
        }
        return transcript.substring(pastCutWord(transcript, end)).trimStart()
    }

    /**
     * Where [handedOver] ends in a [transcript] that revised it: after their common prefix, the end of the
     * stretch that is the fewest single-character edits away from the rest of [handedOver]. Of two equally
     * close ends the earlier wins, so that a mark the revision put after the last word ("test.") is still
     * written rather than swallowed.
     */
    private fun alignedEnd(handedOver: String, transcript: String): Int {
        val start = handedOver.commonPrefixWith(transcript, ignoreCase = true).length
        val old = handedOver.substring(start)
        // A revision is about as long as what it revises. Half as much again bounds the work without
        // cutting a genuine match short.
        val new = transcript.substring(start, minOf(transcript.length, start + old.length * 3 / 2 + 16))
        // Edit distance of all of `old` to every prefix of `new`, kept one row at a time.
        var prev = IntArray(new.length + 1) { it }
        var cur = IntArray(new.length + 1)
        for (i in 1..old.length) {
            cur[0] = i
            for (j in 1..new.length) {
                val substitution = prev[j - 1] + if (old[i - 1].equals(new[j - 1], ignoreCase = true)) 0 else 1
                cur[j] = minOf(substitution, prev[j] + 1, cur[j - 1] + 1)
            }
            val done = prev
            prev = cur
            cur = done
        }
        var best = 0
        for (j in 1..new.length) if (prev[j] < prev[best]) best = j
        return start + best
    }

    /** [end], or the end of the word it falls inside of. */
    private fun pastCutWord(text: String, end: Int): Int {
        if (end <= 0 || end >= text.length) return end
        val before = text[end - 1]
        val after = text[end]
        if (!isWordChar(before) || !isWordChar(after)) return end
        // Every character is a unit of its own there; skipping "to the end of the word" would skip the
        // rest of the sentence.
        if (isWrittenWithoutSpaces(before) || isWrittenWithoutSpaces(after)) return end
        var past = end
        while (past < text.length && isWordChar(text[past])) past++
        return past
    }
}
