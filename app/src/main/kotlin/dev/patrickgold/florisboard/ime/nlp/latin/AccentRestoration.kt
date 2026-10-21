/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.latin

/**
 * What the previous word says about the accents of the word being typed (issue #451).
 *
 * The spelling restoration ([LatinLanguageProvider.suggest]) knows from the dictionary alone that
 * `bedzie` must be `będzie`. It cannot know whether `czesc` is `cześć` (hello) or `część` (part), and it
 * leaves `ze` alone because `ze` is a word — although after `wiem` it is `że` practically every time.
 * Both are questions about the sentence, and the folded bigram table answers them: for "wiem ze" it
 * holds how the corpus spelled the second word, and how often ([NgramIndex.spellingOf]).
 *
 * Pure functions, so the rules can be measured against held-out text without a keyboard
 * (`PolishRestorationEvalTest`).
 */
internal object AccentRestoration {

    /**
     * The languages the sentence evidence and [MIN_FREQ] were measured for. French and Afrikaans fold the
     * same way and would take the same rules, but nobody has measured what they do to correct French or
     * Afrikaans yet — until then they restore exactly as before.
     */
    val LANGS = setOf("pl")

    /**
     * How strongly the sentence has to speak before it may replace a word that is itself correct: the
     * other spelling at least [DOMINANCE] times as often after this word as every other spelling together,
     * and seen at least [MIN_COUNT] times.
     *
     * Measured on 1.26 M words of held-out Polish Wikipedia (`PolishRestorationEvalTest`): with the
     * sentence, 77.7 % of the accents left off come back instead of 76.8 % (`że` after `to`, `mimo`,
     * `fakt`; `mają` after `nie`; `nazwą` after `pod`), for 0.003 % more correctly typed words rewritten —
     * `ze` before `względu`, which only the next word could tell apart. A simulation on the same text put
     * 16× / 20 at half again the gain and four times the harm.
     */
    const val DOMINANCE = 64
    const val MIN_COUNT = 50

    /**
     * The dictionary frequency a restored spelling needs before Space may swap it in, for [LANGS] — the
     * lowest the dictionary has, so any listed word. Everywhere else it is [AutoCommitGate.MIN_FREQ], as
     * for any correction.
     *
     * The floor exists because a correction overrides what was typed, and for a typo that takes evidence.
     * A word that lacks only its accents is different: the dictionary does not know it, and the one word
     * it folds onto is the explanation. Measured on the same held-out text, the usual floor of 170 brought
     * back 41 % of the accents and no floor 78 %, at 0.06 % of correctly typed words rewritten against
     * 0.02 % — mostly English words in Polish text (`game` → gamę) and abbreviations. Each of those is
     * taken back with one Backspace, and taken back twice the spelling is promoted into the personal
     * dictionary and left alone from then on (`isUsersOwnSpelling` in [LatinLanguageProvider]).
     */
    const val MIN_FREQ = 128

    /** How the corpus spelled the word after the previous one: [spelling], [count] of [total] times. */
    data class Evidence(val spelling: String, val count: Int, val total: Long)

    /**
     * The dictionary spellings to offer for [typed], a word that is *not* itself one of them, best first:
     * the one the sentence calls for, then the rest by frequency. [forms] arrive most frequent first.
     * In a language that folds accents ([lang], [DictFold.foldsAccents]) only the spellings that keep the
     * accents already typed ([keepsTypedAccents]), so the list can be empty. Arabic folds letter forms
     * rather than accents, and every one of them stays a candidate there, as it always was.
     */
    fun restorations(lang: String, typed: String, forms: List<String>, evidence: Evidence?): List<String> {
        val kept = if (DictFold.foldsAccents(lang)) forms.filter { keepsTypedAccents(typed, it) } else forms
        val preferred = preferred(kept, evidence) ?: return kept
        return listOf(preferred) + kept.filter { it != preferred }
    }

    /**
     * The other spelling the sentence calls for when [typed] is already a correct word, and whether the
     * evidence is strong enough to swap it in on Space — or null when the sentence agrees with what was
     * typed, says nothing, or would take an accent away.
     */
    fun rival(typed: String, forms: List<String>, evidence: Evidence?): Pair<String, Boolean>? {
        if (evidence == null) return null
        val preferred = preferred(forms, evidence) ?: return null
        if (preferred.equals(typed, ignoreCase = true) || !keepsTypedAccents(typed, preferred)) return null
        return preferred to mayReplace(evidence)
    }

    /**
     * Whether [form] keeps every accented letter [typed] already has, in its place. A restoration puts in
     * the accents that were left off; it never takes one away. Someone who typed `kompozycją` meant it,
     * and the dictionary holding only `kompozycja` says nothing about that — but the fold made the two one
     * key, so without this the rarer case would be "restored" into the commoner one. Same for `że` after a
     * word the corpus mostly follows with `ze`.
     */
    fun keepsTypedAccents(typed: String, form: String): Boolean {
        val accented = typed.indices.filter { isAccented(typed[it]) }
        if (accented.isEmpty()) return true
        // Accented letters fold one-to-one; only the ligatures change the length, and a word typed with
        // accents and compared against one spelled with a ligature is not a restoration anyway.
        if (typed.length != form.length) return false
        return accented.all { typed[it].equals(form[it], ignoreCase = true) }
    }

    private fun isAccented(ch: Char): Boolean = DictFold.foldAccents(ch.toString()) != ch.lowercase()

    /** Whether [evidence] outweighs every other spelling enough to override a correct word. */
    fun mayReplace(evidence: Evidence): Boolean =
        evidence.count >= MIN_COUNT &&
            evidence.count.toLong() >= DOMINANCE * maxOf(1L, evidence.total - evidence.count)

    private fun preferred(forms: List<String>, evidence: Evidence?): String? =
        evidence?.let { e -> forms.firstOrNull { it.equals(e.spelling, ignoreCase = true) } }
}
