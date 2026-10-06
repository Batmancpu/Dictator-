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

import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.MultiTextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The user's symbol pages (issue #342) as the keyboard's layout data. */
class CustomSymbolsLayoutTest {

    private fun text(value: String) = SymbolValue.Text(value)

    @Test
    fun `a page has the rows of its geometry`() {
        assertEquals(listOf(10, 7), CustomSymbolsLayout.arrangement(CustomSymbols(), 0).map { it.size })
        assertEquals(listOf(10, 10, 7), CustomSymbolsLayout.arrangement(CustomSymbols(), 1).map { it.size })
    }

    @Test
    fun `one code point is a plain key, more are typed together`() {
        val single = assertIs<TextKeyData>(CustomSymbolsLayout.keyData(SymbolKey(text("₹"))))
        assertEquals('₹'.code, single.code)
        assertEquals("₹", single.label)

        val heart = "❤️"
        val multi = assertIs<MultiTextKeyData>(CustomSymbolsLayout.keyData(SymbolKey(text(heart))))
        assertEquals(heart, multi.asString(isForDisplay = false))
        assertEquals(heart, multi.label)
    }

    @Test
    fun `the first long press is the popup's main key, the rest follow in order`() {
        val data = CustomSymbolsLayout.keyData(SymbolKey(text("✓"), listOf(text("✔"), text("☑"), text("✅"))))
        val popup = assertIs<TextKeyData>(data).popup!!
        assertEquals("✔", (popup.main as TextKeyData).label)
        assertEquals(listOf("☑", "✅"), popup.relevant.map { (it as TextKeyData).label })
        assertNull(assertIs<TextKeyData>(CustomSymbolsLayout.keyData(SymbolKey(text("∞")))).popup)
    }

    @Test
    fun `a currency key is the language's currency slot, as on the built-in page`() {
        val data = assertIs<TextKeyData>(CustomSymbolsLayout.keyData(SymbolKey(SymbolValue.Currency(1))))
        assertEquals(KeyCode.CURRENCY_SLOT_1, data.code)
        val fifth = assertIs<TextKeyData>(CustomSymbolsLayout.keyData(SymbolKey(SymbolValue.Currency(5))))
        assertEquals(KeyCode.CURRENCY_SLOT_5, fifth.code)
    }
}
