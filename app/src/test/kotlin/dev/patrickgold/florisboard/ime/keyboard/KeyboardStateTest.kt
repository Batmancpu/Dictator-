/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.keyboard

import androidx.compose.ui.unit.LayoutDirection
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

/**
 * The 64-bit register behind [KeyboardState], checked for the one way it can go wrong: two things
 * sharing a bit.
 *
 * Every flag and region in there is hand-placed, and nothing at runtime complains when a new one lands
 * on an old one — the symptom is a keyboard that turns right-to-left when a panel opens, which nobody
 * would trace back to a constant. Adding the editing panel (issue #386) made the Ime-UI-mode region one
 * bit wider and moved the layout direction out of its way, which is exactly the edit this guards.
 */
class KeyboardStateTest : FunSpec({
    /** Every named bit in the register, as (name, occupied bits). */
    val regions: List<Pair<String, ULong>> = listOf(
        "keyboardMode" to (KeyboardState.M_KEYBOARD_MODE shl KeyboardState.O_KEYBOARD_MODE),
        "keyVariation" to (KeyboardState.M_KEY_VARIATION shl KeyboardState.O_KEY_VARIATION),
        "inputShiftState" to (KeyboardState.M_INPUT_SHIFT_STATE shl KeyboardState.O_INPUT_SHIFT_STATE),
        "imeUiMode" to (KeyboardState.M_IME_UI_MODE shl KeyboardState.O_IME_UI_MODE),
        "isSelectionMode" to KeyboardState.F_IS_SELECTION_MODE,
        "isManualSelectionMode" to KeyboardState.F_IS_MANUAL_SELECTION_MODE,
        "isManualSelectionModeStart" to KeyboardState.F_IS_MANUAL_SELECTION_MODE_START,
        "isManualSelectionModeEnd" to KeyboardState.F_IS_MANUAL_SELECTION_MODE_END,
        "isRtlLayoutDirection" to KeyboardState.F_IS_RTL_LAYOUT_DIRECTION,
        "isIncognitoMode" to KeyboardState.F_IS_INCOGNITO_MODE,
        "isActionsOverflowVisible" to KeyboardState.F_IS_ACTIONS_OVERFLOW_VISIBLE,
        "isActionsEditorVisible" to KeyboardState.F_IS_ACTIONS_EDITOR_VISIBLE,
        "isComposingEnabled" to KeyboardState.F_IS_COMPOSING_ENABLED,
        "isCharHalfWidth" to KeyboardState.F_IS_CHAR_HALF_WIDTH,
        "isKanaKata" to KeyboardState.F_IS_KANA_KATA,
        "isKanaSmall" to KeyboardState.F_IS_KANA_SMALL,
        "isSubtypeSelectionVisible" to KeyboardState.F_IS_SUBTYPE_SELECTION_VISIBLE,
        "debugShowDragAndDropHelpers" to KeyboardState.F_DEBUG_SHOW_DRAG_AND_DROP_HELPERS,
    )

    test("no two fields of the state register share a bit") {
        val overlaps = buildList {
            for (i in regions.indices) {
                for (j in i + 1 until regions.size) {
                    val (nameA, bitsA) = regions[i]
                    val (nameB, bitsB) = regions[j]
                    if (bitsA and bitsB != 0uL) add("$nameA/$nameB")
                }
            }
        }
        overlaps shouldBe emptyList()
    }

    test("the Ime UI mode region is wide enough for every panel there is") {
        val widest = ImeUiMode.entries.maxOf { it.toInt() }
        (widest.toULong() and KeyboardState.M_IME_UI_MODE).toInt() shouldBe widest
    }

    context("every Ime UI mode survives the register") {
        withData(nameFn = { it.name }, ImeUiMode.entries.toList()) { mode ->
            val state = KeyboardState.new()
            state.imeUiMode = mode
            state.imeUiMode shouldBe mode
        }
    }

    // A panel switch must not quietly flip anything else — that is what a too-narrow region does.
    context("switching the Ime UI mode leaves the rest of the state alone") {
        withData(nameFn = { it.name }, ImeUiMode.entries.toList()) { mode ->
            val state = KeyboardState.new()
            state.keyboardMode = KeyboardMode.SYMBOLS2
            state.keyVariation = KeyVariation.EMAIL_ADDRESS
            state.inputShiftState = InputShiftState.CAPS_LOCK
            state.layoutDirection = LayoutDirection.Rtl
            state.isSelectionMode = true
            state.isManualSelectionMode = true
            state.isIncognitoMode = true
            state.isComposingEnabled = true
            state.isKanaSmall = true
            state.isSubtypeSelectionVisible = true

            state.imeUiMode = mode

            state.imeUiMode shouldBe mode
            state.keyboardMode shouldBe KeyboardMode.SYMBOLS2
            state.keyVariation shouldBe KeyVariation.EMAIL_ADDRESS
            state.inputShiftState shouldBe InputShiftState.CAPS_LOCK
            state.layoutDirection shouldBe LayoutDirection.Rtl
            state.isSelectionMode shouldBe true
            state.isManualSelectionMode shouldBe true
            state.isIncognitoMode shouldBe true
            state.isComposingEnabled shouldBe true
            state.isKanaSmall shouldBe true
            state.isSubtypeSelectionVisible shouldBe true
        }
    }
})
