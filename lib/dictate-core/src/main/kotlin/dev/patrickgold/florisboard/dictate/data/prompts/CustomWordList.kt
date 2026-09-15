/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.prompts

import kotlin.math.max

/**
 * The custom-words list as a list rather than as a blob of text (issue #389).
 *
 * `dictate.customWords` is a plain string preference that every dictation rides on:
 * [DictatePromptDefaults.appendCustomWords] glues it onto the style prompt and the result goes out as
 * the transcription `prompt` on every single request. That makes two things true at once — it is the
 * app's answer to misspelled proper nouns, and **every word in it is paid for on every dictation**.
 *
 * ## The budget, which is the whole design
 *
 * Whisper-family models read the `prompt` as preceding context and truncate it to `n_text_ctx / 2 - 1`
 * = [PROMPT_TOKEN_WINDOW] tokens. Nothing reports that truncation: a 3000-word list does not fail, it
 * just quietly stops mattering somewhere in the middle while still being assembled and sent. So a list
 * importer without a limit would not be a convenience, it would be a way to make every request bigger
 * and slower for no gain at all.
 *
 * Hence one limit, expressed in the unit the model actually counts in, and it is the model's own:
 * [TOKEN_BUDGET] tokens, which is the window minus [STYLE_PROMPT_RESERVE] for the style prompt that is
 * prepended to the glossary. An import stops there and says how many words did not fit. A list the user
 * typed by hand is **never** truncated — we do not delete what somebody wrote — it is only reported on.
 *
 * ## Why an estimate rather than a real tokenizer
 *
 * Shipping Whisper's BPE vocabulary to count exactly would cost a megabyte of assets to answer a
 * question whose answer only ever drives a warning. [estimateTokens] instead divides the joined
 * glossary's length by [CHARS_PER_TOKEN], counting non-ASCII characters double because byte-level BPE
 * spends two or three bytes on them and merges them far less often. On the rare proper nouns and jargon
 * that a list like this is made of, that lands close enough for "you are over the line", which is all
 * it is ever asked.
 */
object CustomWordList {
    /** Whisper's prompt window: `n_text_ctx / 2 - 1`, i.e. 224 tokens. Everything past it is dropped. */
    const val PROMPT_TOKEN_WINDOW = 224

    /**
     * Held back from [PROMPT_TOKEN_WINDOW] for the style prompt, which shares the window with the
     * glossary. A fixed reserve rather than the real length of whatever style prompt is active: a cap
     * that moved every time another setting changed would be impossible to reason about, and the
     * built-in example sentences all fit inside this comfortably.
     */
    const val STYLE_PROMPT_RESERVE = 32

    /** What the word list itself may use. */
    const val TOKEN_BUDGET = PROMPT_TOKEN_WINDOW - STYLE_PROMPT_RESERVE

    /** Where the UI starts saying the list is getting long — well before anything is actually lost. */
    const val WARN_TOKENS = TOKEN_BUDGET * 3 / 4

    /** How much of a file is read at all. A word list is kilobytes; anything larger is not one. */
    const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

    /**
     * How many entries one file may contribute. Far above the [TOKEN_BUDGET] on purpose — the overflow
     * is not wasted when the import also feeds the typing dictionary, which has no such budget.
     */
    const val MAX_IMPORT_ENTRIES = 2000

    /** Characters per token, non-ASCII counted double. See the class doc for why this is an estimate. */
    private const val CHARS_PER_TOKEN = 3

    /** Weight of the `", "` that joins two entries in the assembled glossary. */
    private const val SEPARATOR_WEIGHT = 2

    /** Longer than this and it is a sentence that wandered into the file, not a word to be spelled. */
    private const val MAX_ENTRY_LENGTH = 48

    /** "Deutsche Bahn AG" is a term; a line with six words in it is prose. */
    private const val MAX_ENTRY_PARTS = 5

    /** Quotes, brackets and list bullets, stripped from the ends of an imported line. */
    private const val EDGE_DEBRIS = "\"'`«»„“”‘’()[]{}*•·–—\t "

    /**
     * The entries of a raw preference value, in order.
     *
     * Deliberately the *same* rule as [DictatePromptDefaults.appendCustomWords] and nothing more — split
     * on comma or newline, trim, drop the blanks. No de-duplication and no repairs, because this is what
     * decides the count and the token estimate the UI shows, and those have to describe the request that
     * actually goes out rather than a tidier version of it.
     */
    fun parse(raw: String?): List<String> = raw.orEmpty()
        .split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /** What a file held: the entries worth keeping, and how much of it was neither of those. */
    data class FileParse(
        val words: List<String>,
        /** Fragments that held nothing a speech model could spell. Blank lines do not count. */
        val unusable: Int,
        /** The file had more than [MAX_IMPORT_ENTRIES] entries, or more bytes than we read. */
        val truncated: Boolean,
    )

    /**
     * The usable entries of an imported file.
     *
     * This is where repairs belong, because a file is written by something other than our settings
     * dialog: lines carry bullets, quotes, a trailing full stop, a header. Comma-separated lines are
     * accepted alongside one-word-per-line, since that is the format the preference itself uses and
     * refusing it would be a puzzle rather than a rule.
     */
    fun parseFile(text: String): FileParse {
        val out = mutableListOf<String>()
        var unusable = 0
        var truncated = text.length > MAX_IMPORT_BYTES
        val body = if (truncated) text.take(MAX_IMPORT_BYTES) else text

        outer@ for (line in body.lineSequence()) {
            val trimmed = line.removePrefix("﻿").trim()
            // A word list may carry a header or a note; `#foo` as an actual entry does not happen.
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue
            for (fragment in trimmed.split(',')) {
                if (fragment.isBlank()) continue
                val entry = sanitize(fragment)
                if (entry == null) {
                    unusable++
                    continue
                }
                out.add(entry)
                if (out.size >= MAX_IMPORT_ENTRIES) {
                    truncated = true
                    break@outer
                }
            }
        }
        return FileParse(out, unusable, truncated)
    }

    /**
     * One imported fragment turned into an entry, or null when there is nothing usable in it.
     *
     * The trailing full stop is dropped only when it is the only one in the entry, so "Kubernetes."
     * loses it and "e.V." keeps both of its own — the abbreviation is exactly the kind of word a list
     * like this exists for, and "repairing" it would be the opposite of the point.
     */
    private fun sanitize(fragment: String): String? {
        var entry = fragment.trim().trim { it in EDGE_DEBRIS }
        // A bulleted list ("- Kubernetes") and a numbered one ("1. Kubernetes").
        entry = entry.removePrefix("-").removePrefix("–").removePrefix("—").trimStart()
        entry = NUMBERED_PREFIX.replace(entry, "")
        entry = entry.trim { it in EDGE_DEBRIS }.trimEnd(';', ':')
        if (entry.endsWith(".") && entry.count { it == '.' } == 1) entry = entry.dropLast(1)
        entry = WHITESPACE_RUN.replace(entry.trim { it in EDGE_DEBRIS }, " ")
        if (entry.isEmpty() || entry.none { it.isLetterOrDigit() }) return null
        if (entry.length > MAX_ENTRY_LENGTH) return null
        if (entry.count { it == ' ' } >= MAX_ENTRY_PARTS) return null
        return entry
    }

    /**
     * How many tokens the assembled glossary for [words] is worth, as read by the speech model.
     *
     * An estimate, and one that is allowed to be a little pessimistic: over-warning costs a sentence of
     * explanation, under-warning costs words that silently never arrive.
     */
    fun estimateTokens(words: List<String>): Int {
        if (words.isEmpty()) return 0
        val weight = words.sumOf { weightOf(it) } + SEPARATOR_WEIGHT * (words.size - 1)
        return tokensFor(weight)
    }

    /** How many of [words], taken from the front, still fit in [budget] tokens. */
    fun countWithinBudget(words: List<String>, budget: Int = TOKEN_BUDGET): Int {
        var weight = 0
        var count = 0
        for (word in words) {
            val next = weight + weightOf(word) + if (count == 0) 0 else SEPARATOR_WEIGHT
            if (tokensFor(next) > budget) break
            weight = next
            count++
        }
        return count
    }

    private fun tokensFor(weight: Int): Int = (weight + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /** Non-ASCII counts double: byte-level BPE spends more bytes on it and merges it less. */
    private fun weightOf(s: String): Int = s.sumOf { if (it.code < 0x80) 1 else 2 }

    /** What an import would do, worked out before anything is written. */
    data class ImportReport(
        /** Every usable entry in the file, in order — including the ones there was no room for. */
        val fromFile: List<String>,
        /** The new entries that fit, in file order. */
        val accepted: List<String>,
        /** Entries already in the list, compared case-insensitively. */
        val alreadyKnown: Int,
        /** New and valid, but there is no room left in the prompt for them. */
        val didNotFit: Int,
        /** Fragments that held nothing a speech model could spell. */
        val unusable: Int,
        /** The file was longer than we are willing to read. */
        val fileTruncated: Boolean,
        /** The preference value to write, or null when there is nothing to add. */
        val merged: String?,
        val tokensBefore: Int,
        val tokensAfter: Int,
    ) {
        val hasSomethingToAdd: Boolean get() = accepted.isNotEmpty()
    }

    /**
     * Works out what importing [fileText] on top of [existingRaw] would do, without writing anything.
     *
     * The existing text is **appended to, never rewritten**: the merged value is the user's own string
     * plus the new entries behind it, joined the way that string already joins things. Re-serialising a
     * parsed list would silently collapse somebody's carefully one-word-per-line field into a single
     * comma run, which is a worse thing to do to a setting than anything this feature adds.
     */
    fun plan(existingRaw: String?, fileText: String): ImportReport {
        val existing = parse(existingRaw)
        val file = parseFile(fileText)

        val known = existing.mapTo(HashSet()) { it.lowercase() }
        val accepted = mutableListOf<String>()
        var alreadyKnown = 0
        var didNotFit = 0
        var weight = existing.sumOf { weightOf(it) } + SEPARATOR_WEIGHT * max(0, existing.size - 1)
        var count = existing.size

        for (word in file.words) {
            if (!known.add(word.lowercase())) {
                alreadyKnown++
                continue
            }
            val next = weight + weightOf(word) + if (count == 0) 0 else SEPARATOR_WEIGHT
            if (tokensFor(next) > TOKEN_BUDGET) {
                didNotFit++
                continue
            }
            weight = next
            count++
            accepted.add(word)
        }

        val separator = if (existingRaw.isNullOrBlank() || existingRaw.contains('\n')) "\n" else ", "
        val merged = when {
            accepted.isEmpty() -> null
            existingRaw.isNullOrBlank() -> accepted.joinToString(separator)
            else -> existingRaw.trimEnd().trimEnd(',').trimEnd() + separator + accepted.joinToString(separator)
        }

        return ImportReport(
            fromFile = file.words,
            accepted = accepted,
            alreadyKnown = alreadyKnown,
            didNotFit = didNotFit,
            unusable = file.unusable,
            fileTruncated = file.truncated,
            merged = merged,
            tokensBefore = estimateTokens(existing),
            tokensAfter = estimateTokens(existing + accepted),
        )
    }

    /** The list as a file: one entry per line, which is what [parseFile] reads back. */
    fun toFileText(words: List<String>): String =
        if (words.isEmpty()) "" else words.joinToString("\n", postfix = "\n")

    private val NUMBERED_PREFIX = Regex("^\\d{1,3}[.)]\\s+")
    private val WHITESPACE_RUN = Regex("\\s+")
}
