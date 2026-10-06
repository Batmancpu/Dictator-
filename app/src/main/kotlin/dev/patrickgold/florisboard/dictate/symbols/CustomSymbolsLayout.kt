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

import dev.patrickgold.florisboard.ime.keyboard.AbstractKeyData
import dev.patrickgold.florisboard.ime.keyboard.LayoutArrangement
import dev.patrickgold.florisboard.ime.popup.PopupSet
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.keyboard.MultiTextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData

/**
 * The user's symbol pages (issue #342) as the keyboard's own layout data — what a symbols layout JSON
 * of the built-in extension would have been parsed into. The keyboard adds its page switch, backspace
 * and bottom row around it as it does for every symbols layout.
 */
object CustomSymbolsLayout {
    fun arrangement(symbols: CustomSymbols, page: Int): LayoutArrangement =
        symbols.rows(page).map { row -> row.map(::keyData) }

    fun keyData(key: SymbolKey): AbstractKeyData {
        val longPress = key.longPress.map { valueData(it, popup = null) }
        // The first is the popup's main key: it opens right above the finger, preselected.
        val popup = longPress.takeIf { it.isNotEmpty() }?.let { PopupSet(main = it.first(), relevant = it.drop(1)) }
        return valueData(key.value, popup)
    }

    private fun valueData(value: SymbolValue, popup: PopupSet<AbstractKeyData>?): AbstractKeyData = when (value) {
        is SymbolValue.Currency -> TextKeyData(
            code = KeyCode.CURRENCY_SLOT_1 - (value.slot - 1),
            label = "currency_slot_${value.slot}",
            popup = popup,
        )
        is SymbolValue.Text -> {
            val codePoints = value.text.codePoints().toArray()
            if (codePoints.size == 1) {
                TextKeyData(type = KeyType.CHARACTER, code = codePoints[0], label = value.text, popup = popup)
            } else {
                MultiTextKeyData(type = KeyType.CHARACTER, codePoints = codePoints, label = value.text, popup = popup)
            }
        }
    }
}
