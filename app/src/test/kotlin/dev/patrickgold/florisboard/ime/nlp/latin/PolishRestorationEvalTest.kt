/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A measuring stand for Polish typed without its diacritics (issue #451).
 *
 * Replays the decisions [LatinLanguageProvider.suggest] makes for a folding language — the dictionary's
 * spellings per fold key, [AccentRestoration] with the evidence of the folded bigram table, and the
 * frequency floor for a silent swap — over running text, in two passes:
 *
 *  - **Typed bare.** Every word with its diacritics stripped, the way the issue's reporter writes. The
 *    gain is the share of accented words that come back right; the harm is the share of words *without*
 *    accents that get rewritten anyway (`kompozycje` → `kompozycję`, `ze` → `że` before `względu`).
 *  - **Typed correctly.** Every word exactly as written. Nothing here should change at all; whatever does
 *    is a correct word the keyboard took away from someone who typed it right.
 *
 * ### Held out on purpose
 *
 * The bigram table and the dictionary's accent twins come from Leipzig *news*; the text measured here is
 * Leipzig *Wikipedia*, every tenth sentence — a different corpus, not a different slice of the same one.
 * Create it once with
 *
 *     cd tools/glide-dict && python3 -c "import ngramcount; ngramcount.runs_path('pol_wikipedia_2021_1M', 'dist/corpora', 'holdout')"
 *
 * Everything it reads is a generated artefact outside git, so it skips when they are missing, like
 * [FrenchElisionEvalTest].
 */
class PolishRestorationEvalTest {

    private companion object {
        const val DICT = "tools/glide-dict/dist/pl.json"
        const val TABLE = "tools/glide-dict/dist/pl_bigrams_150k.txt"
        const val HELD_OUT = "tools/glide-dict/dist/corpora/pol_wikipedia_2021_1M-holdout.runs"

        // Floors, not targets. Measured 77.7 % / 0.072 % / 0.061 % when this was written; a change that
        // trades one for the other has to be argued in the commit, not slipped past a single-number assertion.
        const val MIN_RESTORED = 0.75
        const val MAX_BARE_HARM = 0.0008
        const val MAX_CORRECT_HARM = 0.0007
    }

    private fun repoFile(relative: String): File? {
        var dir = File(".").absoluteFile
        repeat(5) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun fold(word: String) = DictFold.foldKey("pl", word)

    @Test
    fun measurePolishAccentRestoration() {
        val dict = repoFile(DICT)
        val table = repoFile(TABLE)
        val heldOut = repoFile(HELD_OUT)
        if (dict == null || table == null || heldOut == null) {
            println("\n=== #451 · Polish without diacritics ===")
            println("skipped: the generated corpora are not in the repository")
            return
        }

        // The provider's index: every spelling of a fold key, most frequent first.
        val data = EvalKeyboard.parseDict(dict.readText())
        val forms = HashMap<String, MutableList<String>>(data.size)
        for (word in data.keys) forms.getOrPut(fold(word)) { ArrayList(1) }.add(word)
        for (list in forms.values) list.sortByDescending { data[it] ?: 0 }
        val bigrams = NgramIndex.parse(table.readText()) { fold(it) }

        /** What Space commits for [typed] after the folded word [prev], with autocorrect on. */
        fun committed(typed: String, prev: String?, floor: Int, sentence: Boolean): String {
            val key = fold(typed)
            val spellings = forms[key] ?: return typed
            val evidence = prev?.takeIf { sentence }?.let { p ->
                bigrams.spellingOf("$p $key")?.let { (spelling, count) ->
                    AccentRestoration.Evidence(spelling, count, bigrams.countOf("$p $key"))
                }
            }
            // Case aside, as the user would capitalise a name the dictionary stores with a capital.
            if (spellings.any { it.equals(typed, ignoreCase = true) }) {
                val (form, mayReplace) = AccentRestoration.rival(typed, spellings, evidence) ?: return typed
                return if (mayReplace && (data[form] ?: 0) >= AutoCommitGate.MIN_FREQ) form else typed
            }
            val first = AccentRestoration.restorations("pl", typed, spellings, evidence).firstOrNull() ?: return typed
            return if ((data[first] ?: 0) >= floor) first else typed
        }

        val runs = heldOut.readLines().map { line -> line.split(' ').filter { it.isNotEmpty() } }

        class Result(
            val restored: Double,
            val bareHarm: Double,
            val correctHarm: Double,
            val missed: Map<String, Long>,
            val harms: Map<String, Long>,
            val correctHarms: Map<String, Long>,
        )

        fun measure(floor: Int, sentence: Boolean = true): Result {
            var accented = 0L
            var restored = 0L
            var plain = 0L
            var bareHarm = 0L
            var tokens = 0L
            var correctHarm = 0L
            val missed = HashMap<String, Long>()
            val harms = HashMap<String, Long>()
            val correctHarms = HashMap<String, Long>()
            for (run in runs) {
                var prev: String? = null
                for (token in run) {
                    if (!token.all { it.isLetter() }) {
                        prev = null
                        continue
                    }
                    val bare = fold(token)
                    val fix = committed(bare, prev, floor, sentence).lowercase()
                    if (bare != token) {
                        accented++
                        if (fix == token) restored++ else missed.merge("$bare→$token", 1L, Long::plus)
                    } else {
                        plain++
                        if (fix != token) {
                            bareHarm++
                            harms.merge("$token→$fix", 1L, Long::plus)
                        }
                    }
                    tokens++
                    val kept = committed(token, prev, floor, sentence).lowercase()
                    if (kept != token) {
                        correctHarm++
                        correctHarms.merge("$token→$kept", 1L, Long::plus)
                    }
                    prev = bare
                }
            }
            return Result(
                restored.toDouble() / accented, bareHarm.toDouble() / plain, correctHarm.toDouble() / tokens,
                missed, harms, correctHarms,
            )
        }

        fun top(m: Map<String, Long>, n: Int) = m.entries.sortedByDescending { it.value }.take(n)
            .joinToString { "${it.key} (${it.value})" }
        println()
        println("=== #451 · Polish without diacritics — held-out Wikipedia, bigram evidence from news ===")
        println("%-28s %12s %18s %18s".format("floor for a silent swap", "restored", "bare harm", "correct harm"))
        for (floor in listOf(128, 150, 160, AutoCommitGate.MIN_FREQ)) {
            val r = measure(floor)
            println(
                "%-28s %11.2f%% %17.4f%% %17.4f%%".format(
                    if (floor == AccentRestoration.MIN_FREQ) "$floor  (shipped)" else "$floor",
                    100 * r.restored, 100 * r.bareHarm, 100 * r.correctHarm,
                ),
            )
        }
        // What the previous word adds: `czesc` read as część after `jedna`, `ze` swapped for `że` after `mimo`.
        val blind = measure(AccentRestoration.MIN_FREQ, sentence = false)
        println(
            "%-28s %11.2f%% %17.4f%% %17.4f%%".format(
                "${AccentRestoration.MIN_FREQ}, no sentence",
                100 * blind.restored, 100 * blind.bareHarm, 100 * blind.correctHarm,
            ),
        )
        val shipped = measure(AccentRestoration.MIN_FREQ)
        println("  missed:        " + top(shipped.missed, 12))
        println("  bare harm:     " + top(shipped.harms, 12))
        println("  correct harm:  " + top(shipped.correctHarms, 12))
        println()

        assertTrue(shipped.restored >= MIN_RESTORED, "restored ${100 * shipped.restored}% < ${100 * MIN_RESTORED}%")
        assertTrue(shipped.bareHarm <= MAX_BARE_HARM, "bare harm ${100 * shipped.bareHarm}% > ${100 * MAX_BARE_HARM}%")
        assertTrue(
            shipped.correctHarm <= MAX_CORRECT_HARM,
            "correct harm ${100 * shipped.correctHarm}% > ${100 * MAX_CORRECT_HARM}%",
        )
    }
}
