/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.symbols

/** What one key of the user's own symbol pages types (issue #342). */
sealed interface SymbolValue {
    /** Literal text: one symbol, or a short run of them — an emoji sequence, ꧁, `->`. */
    data class Text(val text: String) : SymbolValue

    /**
     * Whatever currency the active language puts at [slot] of its currency set (1 is its own, ₹ for
     * Indian English and € for German). Only the defaults use it, so that switching the pages on keeps
     * the currency key the built-in page has; anything the user types is [Text].
     */
    data class Currency(val slot: Int) : SymbolValue
}

/** A key: what a tap types, and what holding it offers, the first of which is preselected. */
data class SymbolKey(val value: SymbolValue, val longPress: List<SymbolValue> = emptyList())

/**
 * The user's own two symbol pages (issue #342), holding only the keys they changed. Every other key is
 * its default, so a key can never be missing and shift the rest of its row, and a later version that
 * improves a default reaches every key the user left alone.
 *
 * The geometry is fixed and is the built-in pages' own: page 1 is the digit row plus 10 and 7, page 2 is
 * 10/10/7, the last row of each between the page switch and backspace. The letters' 10/9/7 was tried
 * first: a symbol page takes the letters' height so the keyboard does not jump, and its extra row made
 * every key a fifth flatter — hard to hit (Jannis, on the phone). Long presses make up the difference.
 */
data class CustomSymbols(val changed: Map<Slot, SymbolKey> = emptyMap()) {
    data class Slot(val page: Int, val index: Int)

    fun key(page: Int, index: Int): SymbolKey = changed[Slot(page, index)] ?: SymbolDefaults.key(page, index)

    fun isChanged(page: Int, index: Int): Boolean = Slot(page, index) in changed

    /** This layout with the key at [page]/[index] set to [key]; null, or the default itself, resets it. */
    fun with(page: Int, index: Int, key: SymbolKey?): CustomSymbols {
        val slot = Slot(page, index)
        require(index in 0 until keyCount(page)) { "No key $index on page $page" }
        return if (key == null || key == SymbolDefaults.key(page, index)) {
            CustomSymbols(changed - slot)
        } else {
            CustomSymbols(changed + (slot to key))
        }
    }

    /** The keys of [page], row by row. */
    fun rows(page: Int): List<List<SymbolKey>> {
        var index = 0
        return PAGE_ROWS[page].map { size -> List(size) { key(page, index++) } }
    }

    companion object {
        /** Keys per row on each page, as on the built-in pages. */
        val PAGE_ROWS: List<List<Int>> = listOf(listOf(10, 7), listOf(10, 10, 7))

        const val PAGE_COUNT = 2

        /** How many symbols one long press may offer: two full rows of a popup, the keyboard's width. */
        const val MAX_LONG_PRESS = 20

        /** Room for an e-mail address on a long press, short of a paragraph on one key. */
        const val MAX_CODE_POINTS = 64

        fun keyCount(page: Int): Int = PAGE_ROWS[page].sum()
    }
}

/**
 * What the user types into a key turned into what a key can hold (issue #342).
 *
 * A key never holds whitespace, which is what makes a space a safe separator: "✔ ☑ ✅" is three
 * symbols whether one space or three stand between them. HeliBoard's text format breaks on exactly
 * that, which is the complaint behind the issue.
 */
object SymbolText {
    /** The symbols in [input], split at whitespace, cleaned of control characters, long ones cut short. */
    fun tokens(input: String): List<String> =
        input.split(WHITESPACE).mapNotNull(::clean)

    /** [input] as one key's text, or null if nothing typeable is left of it. */
    fun clean(input: String): String? {
        val kept = StringBuilder()
        var count = 0
        var i = 0
        while (i < input.length && count < CustomSymbols.MAX_CODE_POINTS) {
            val cp = input.codePointAt(i)
            i += Character.charCount(cp)
            // Cc only: zero-width joiners and variation selectors are format characters, and an emoji
            // sequence falls apart without them.
            if (Character.getType(cp) == Character.CONTROL.toInt() || Character.isWhitespace(cp)) continue
            kept.appendCodePoint(cp)
            count++
        }
        return kept.toString().takeIf { it.isNotEmpty() }
    }

    /** [values] without repeats and capped at [CustomSymbols.MAX_LONG_PRESS], order kept. */
    fun longPress(values: List<SymbolValue>): List<SymbolValue> =
        values.distinct().take(CustomSymbols.MAX_LONG_PRESS)

    private val WHITESPACE = Regex("\\s+")
}
