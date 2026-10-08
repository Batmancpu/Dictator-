/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.text.gestures

import dev.patrickgold.florisboard.ime.keyboard.CaseSelector
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Which keys a swipe passes through for each letter of a word, on the layout that is on screen (issue #426).
 *
 * The rule, in this order:
 *  1. **A letter with a key of its own is swiped through that key, and only that one.** Hungarian `á`,
 *     Russian `й`, Swedish `å` are where the finger goes. Nothing else may stand in for it either, because
 *     the key is the only thing that tells `kar` from `kár`, or `sor` from `sör`, apart.
 *  2. **A letter without one is swiped through every key that types it**: the key of its base letter
 *     (`ó` → `o`, by Unicode decomposition) and every key that offers it on long-press. Hungarian `ő` sits
 *     under both `o` and `ö`, and people swipe it through either; German `ß` and Polish `ł` have no base
 *     letter at all and are reachable *only* through the popup of `s` and `l`.
 *
 * Before this, the classifier answered the question twice, differently: the ideal path went through a
 * letter's own key, but the index that pre-selects words by their first and last key always stripped the
 * accent. `állatkert` was filed under `a` and its swipe starts on `á` — so it was never even scored, and
 * nothing at all came out. Letters with neither a key nor a base letter (`ß`, `ł`, Turkish `ı`) left their
 * words out of the index entirely.
 *
 * Letters are compared lower-cased in the subtype's language, because the keys may have been computed while
 * the shift was on (every sentence start): a Turkish popup then reads `I`, which is `ı` in Turkish and `i`
 * everywhere else.
 */
class GlideKeyMap(keys: List<TextKey>, private val locale: Locale) {
    private val ownKey = HashMap<Char, TextKey>()
    private val offeredBy = HashMap<Char, MutableList<TextKey>>()
    // Asked for every letter of every candidate word on every swipe, from the classifier's worker threads.
    private val cache = ConcurrentHashMap<Char, List<TextKey>>()

    init {
        for (key in keys) {
            val letter = letterOf(key.glideCode()) ?: continue
            ownKey.putIfAbsent(letter, key)
        }
        for (key in keys) {
            if (letterOf(key.glideCode()) == null) continue
            val popups = key.computedPopups
            for (data in listOfNotNull(popups.main) + popups.relevant) {
                // Letters only: the comma key's popup carries an apostrophe, and a word like "l'homme" must
                // not be sent on a detour to the bottom row.
                val letter = letterOf(data.code)?.takeIf { it.isLetter() } ?: continue
                val hosts = offeredBy.getOrPut(letter) { mutableListOf() }
                if (key !in hosts) hosts.add(key)
            }
        }
    }

    /**
     * The keys a swipe of [char] may pass through, in order of preference — empty if this layout cannot type
     * it at all. Never more than one for a letter with a key of its own.
     */
    fun keysFor(char: Char): List<TextKey> = cache[char] ?: resolve(char).also { cache[char] = it }

    private fun resolve(char: Char): List<TextKey> {
        val letter = lower(char)
        ownKey[letter]?.let { return listOf(it) }
        return buildList {
            val base = Normalizer.normalize(letter.toString(), Normalizer.Form.NFD)[0]
            if (base != letter) ownKey[base]?.let { add(it) }
            offeredBy[letter]?.forEach { if (it !in this) add(it) }
        }
    }

    private fun letterOf(code: Int): Char? {
        if (code <= 0 || code > Char.MAX_VALUE.code) return null
        return lower(code.toChar())
    }

    private fun lower(char: Char): Char = char.toString().lowercase(locale).first()
}

/**
 * The code a key types when the shift is off. A case selector (Turkish `ı`/`I` and `i`/`İ`, German `ß`/`ẞ`)
 * is not a [KeyData] itself, so reading the raw data alone gave both Turkish i-keys no code at all.
 */
internal fun TextKey.glideCode(): Int = when (val raw = data) {
    is CaseSelector -> (raw.lower as? KeyData)?.code ?: KeyCode.UNSPECIFIED
    is KeyData -> raw.code
    else -> KeyCode.UNSPECIFIED
}
