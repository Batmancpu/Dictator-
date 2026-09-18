/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.mappings

/**
 * The find-and-replace rules as a file, so a list of them can be handed over instead of typed in
 * (issue #389).
 *
 * ## Why this format and not the stored one
 *
 * The rules are persisted as JSON (see `DictateMappings.Serializer`), and exporting that would have
 * been one line of code. It is the wrong file all the same: the reason anybody wants a mappings list
 * out of the app is to work on it somewhere else — a spreadsheet, a colleague's phone, a text editor —
 * and JSON is the format that makes each of those a little worse. Tab-separated columns open in every
 * spreadsheet, diff cleanly, and can be written by hand without a single quotation mark going wrong.
 *
 * ```
 * # from<TAB>to<TAB>word|any<TAB>case|nocase
 * github	GitHub	word	nocase
 * teh	the
 * ```
 *
 * Both flag columns may be left off; they fall back to the same defaults the editor dialog uses
 * (whole-word, case-insensitive), so the two-column file a person types by hand is a valid one. On the
 * way in a plain arrow (`->`, `=>`, `→`) or a comma is accepted in place of the tab, because that is
 * what a list written by hand actually looks like.
 *
 * An empty replacement is preserved in both directions: "delete this word wherever it appears" is a
 * real rule, and the most common single-line fix for a hallucinated word.
 */
object DictateMappingsText {
    /** Same ceiling as the word list: a file with more rules than this in it is not a mappings list. */
    const val MAX_IMPORT_ENTRIES = 2000

    private const val WHOLE_WORD = "word"
    private const val ANYWHERE = "any"
    private const val MATCH_CASE = "case"
    private const val IGNORE_CASE = "nocase"

    /** Arrow spellings accepted in place of the tab, longest first so `=>` is not read as `>`. */
    private val ARROWS = listOf("→", "=>", "->")

    /** What a file held. [unusable] counts lines that named no replacement at all. */
    data class Parse(
        val mappings: List<DictateMappings.Mapping>,
        val unusable: Int,
        val truncated: Boolean,
    )

    fun parse(text: String): Parse {
        val out = mutableListOf<DictateMappings.Mapping>()
        var unusable = 0
        var truncated = false
        for (line in text.lineSequence()) {
            val trimmed = line.removePrefix("﻿").trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue
            val mapping = mappingFrom(trimmed)
            if (mapping == null) {
                unusable++
                continue
            }
            out.add(mapping)
            if (out.size >= MAX_IMPORT_ENTRIES) {
                truncated = true
                break
            }
        }
        return Parse(out, unusable, truncated)
    }

    /**
     * One line as a rule, or null when it does not name one.
     *
     * A line with no separator at all is not a rule and is not guessed at: "github" alone could only
     * ever become a rule that replaces a word with itself.
     */
    private fun mappingFrom(line: String): DictateMappings.Mapping? {
        val columns = when {
            line.contains('\t') -> line.split('\t')
            else -> {
                val arrow = ARROWS.firstOrNull { line.contains(it) }
                when {
                    arrow != null -> listOf(line.substringBefore(arrow), line.substringAfter(arrow))
                    line.contains(',') -> listOf(line.substringBefore(','), line.substringAfter(','))
                    else -> return null
                }
            }
        }
        val from = columns.getOrNull(0)?.trim().orEmpty()
        if (from.isEmpty()) return null
        // The replacement is trimmed but may legitimately end up empty — that rule deletes the match.
        val to = columns.getOrNull(1)?.trim().orEmpty()
        val flags = columns.drop(2).map { it.trim().lowercase() }
        return DictateMappings.Mapping(
            from = from,
            to = to,
            matchCase = flags.any { it == MATCH_CASE || it == "true" },
            wholeWord = flags.none { it == ANYWHERE || it == "false" },
        )
    }

    /** The rules as a file, header included, in the shape [parse] reads back. */
    fun format(mappings: List<DictateMappings.Mapping>): String = buildString {
        append("# from\tto\t${WHOLE_WORD}|${ANYWHERE}\t${MATCH_CASE}|${IGNORE_CASE}\n")
        for (m in mappings) {
            append(m.from).append('\t').append(m.to).append('\t')
            append(if (m.wholeWord) WHOLE_WORD else ANYWHERE).append('\t')
            append(if (m.matchCase) MATCH_CASE else IGNORE_CASE).append('\n')
        }
    }

    /** What an import would do, worked out before anything is written. */
    data class ImportReport(
        val added: List<DictateMappings.Mapping>,
        val alreadyKnown: Int,
        val unusable: Int,
        val fileTruncated: Boolean,
        val merged: DictateMappings,
    ) {
        val hasSomethingToAdd: Boolean get() = added.isNotEmpty()
    }

    /**
     * Works out what importing [text] on top of [existing] would do.
     *
     * A rule counts as already present when its `from` matches one that is there, whatever the flags
     * say: two rules firing on the same word are not a merge, they are a bug the user would have to
     * find by dictating. The rule that is already in the list wins, since it is the one they may have
     * adjusted by hand.
     */
    fun plan(existing: DictateMappings, text: String): ImportReport {
        val parse = parse(text)
        val known = existing.items.mapTo(HashSet()) { it.from.lowercase() }
        val added = mutableListOf<DictateMappings.Mapping>()
        var alreadyKnown = 0
        for (mapping in parse.mappings) {
            if (!known.add(mapping.from.lowercase())) {
                alreadyKnown++
                continue
            }
            added.add(mapping)
        }
        return ImportReport(
            added = added,
            alreadyKnown = alreadyKnown,
            unusable = parse.unusable,
            fileTruncated = parse.truncated,
            merged = existing.copy(items = existing.items + added),
        )
    }
}
