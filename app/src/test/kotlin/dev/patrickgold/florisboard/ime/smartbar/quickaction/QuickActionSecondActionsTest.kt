/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import dev.patrickgold.florisboard.ime.input.RepeatableKeyCodes
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

private fun key(data: KeyData) = QuickAction.InsertKey(data)
private fun pairing(vararg pairs: Pair<QuickAction, QuickAction>) =
    QuickActionSecondActions(pairs.map { QuickActionSecondAction(it.first, it.second) })

private val COPY = key(TextKeyData.CLIPBOARD_COPY)
private val CUT = key(TextKeyData.CLIPBOARD_CUT)
private val PASTE = key(TextKeyData.CLIPBOARD_PASTE)
private val SETTINGS = key(TextKeyData.SETTINGS)
private val EDITING = key(TextKeyData.IME_UI_MODE_EDITING)
private val ARROW_LEFT = key(TextKeyData.ARROW_LEFT)
private val UNDO = key(TextKeyData.UNDO)
private val MIC = key(TextKeyData.IME_UI_MODE_DICTATE)

class QuickActionSecondActionsTest : FunSpec({
    // The rules that make a pairing safe rather than merely tidy. A repeating host would silently
    // lose its hold-to-repeat (the dispatcher runs the repeat loop only when the long press declines),
    // and the mic's whole gesture belongs to DictateHoldTouch.
    context("eligible hosts") {
        test("no repeating action may host a second one") {
            QuickActionSecondActions.EligibleHosts.filter {
                it.keyData().code in RepeatableKeyCodes
            } shouldBe emptyList()
        }

        test("the mic is never a host, but is a legal second action") {
            QuickActionSecondActions.EligibleHosts.none {
                it.keyData().code == KeyCode.IME_UI_MODE_DICTATE
            } shouldBe true
            QuickActionSecondActions.KnownActions.any {
                it.keyData().code == KeyCode.IME_UI_MODE_DICTATE
            } shouldBe true
        }

        test("the editor placeholders and the retired line codes are no actions at all") {
            val codes = QuickActionSecondActions.KnownActions.map { it.keyData().code }
            listOf(
                KeyCode.NOOP,
                KeyCode.DRAG_MARKER,
                KeyCode.TOGGLE_ACTIONS_OVERFLOW,
                KeyCode.MOVE_START_OF_LINE,
                KeyCode.MOVE_END_OF_LINE,
            ).filter { it in codes } shouldBe emptyList()
        }
    }

    // Sanitising runs on every read, not only behind the picker: a restored backup and a hand-edited
    // datastore reach the same field, and the rules above are not cosmetic.
    context("sanitising a stored pairing") {
        withData(
            nameFn = { it.first },
            Triple("a pair is kept", pairing(COPY to CUT), pairing(COPY to CUT)),
            Triple("an action may not hold itself", pairing(COPY to COPY), pairing()),
            Triple("a repeating host is dropped", pairing(ARROW_LEFT to CUT), pairing()),
            Triple("a repeating host is dropped (undo)", pairing(UNDO to CUT), pairing()),
            Triple("the mic may not host", pairing(MIC to CUT), pairing()),
            Triple("a repeating second action is fine", pairing(COPY to ARROW_LEFT), pairing(COPY to ARROW_LEFT)),
            Triple("the mic as a second action is fine", pairing(EDITING to MIC), pairing(EDITING to MIC)),
            Triple(
                "an unknown host is dropped",
                pairing(key(TextKeyData(code = -4711)) to CUT),
                pairing(),
            ),
            Triple(
                "an unknown second action is dropped",
                pairing(COPY to key(TextKeyData(code = -4711))),
                pairing(),
            ),
            Triple(
                "the first pairing for a host wins",
                pairing(COPY to CUT, COPY to PASTE),
                pairing(COPY to CUT),
            ),
            Triple(
                "no chain: a second action may not host one itself",
                pairing(COPY to CUT, CUT to PASTE),
                pairing(COPY to CUT),
            ),
            Triple(
                "no chain: a host may not become a second action",
                pairing(COPY to CUT, SETTINGS to COPY),
                pairing(COPY to CUT),
            ),
            Triple(
                "the same second action may serve two hosts",
                pairing(COPY to PASTE, SETTINGS to PASTE),
                pairing(COPY to PASTE, SETTINGS to PASTE),
            ),
            Triple(
                "inserted text is neither host nor second action",
                pairing(QuickAction.InsertText("x") to CUT, COPY to QuickAction.InsertText("y")),
                pairing(),
            ),
        ) { (_, raw, expected) ->
            raw.sanitized() shouldBe expected
        }

        test("sanitising twice changes nothing further") {
            val once = pairing(COPY to CUT, CUT to PASTE, ARROW_LEFT to SETTINGS).sanitized()
            once.sanitized() shouldBe once
        }
    }

    context("reading a stored value back") {
        test("a clean pairing survives the round trip") {
            val stored = pairing(COPY to CUT, SETTINGS to PASTE)
            QuickActionSecondActions.Serializer.deserialize(
                QuickActionSecondActions.Serializer.serialize(stored)
            ) shouldBe stored
        }

        test("a backup carrying a chain and a repeating host comes back clean") {
            val restored = QuickActionSecondActions.Serializer.deserialize(
                QuickActionSecondActions.Serializer.serialize(
                    pairing(ARROW_LEFT to SETTINGS, COPY to CUT, CUT to PASTE)
                )
            )
            restored shouldBe pairing(COPY to CUT)
        }

        test("an unreadable value is no pairing at all, not a crash") {
            QuickActionSecondActions.Serializer.deserialize("{ this is not json") shouldBe
                QuickActionSecondActions.Default
        }
    }

    context("setting and clearing a second action") {
        test("setting one for a host that had none adds it") {
            QuickActionSecondActions.Default.with(COPY, CUT) shouldBe pairing(COPY to CUT)
        }

        test("setting a different one replaces rather than doubles") {
            pairing(COPY to CUT).with(COPY, PASTE) shouldBe pairing(COPY to PASTE)
        }

        test("clearing removes the pairing") {
            pairing(COPY to CUT, SETTINGS to PASTE).with(COPY, null) shouldBe pairing(SETTINGS to PASTE)
        }

        test("the write path cannot build a chain either") {
            pairing(COPY to CUT).with(CUT, PASTE) shouldBe pairing(COPY to CUT)
        }
    }

    // The settings grid takes a paired action out of the grid and draws it in its host's corner, so
    // "which codes are absorbed" is a rendering decision now, not only bookkeeping.
    context("which actions are absorbed into a host") {
        test("exactly the second actions, and nothing else") {
            pairing(COPY to CUT, SETTINGS to PASTE).childCodes() shouldBe
                setOf(CUT.keyData().code, PASTE.keyData().code)
        }

        test("hosts are not absorbed themselves") {
            pairing(COPY to CUT).hostCodes() shouldBe setOf(COPY.keyData().code)
        }

        test("clearing a pairing hands the action back to the grid") {
            pairing(COPY to CUT).with(COPY, null).childCodes() shouldBe emptySet()
        }
    }

    context("what the picker offers") {
        test("never the host itself") {
            QuickActionSecondActions.Default.eligibleChildrenFor(COPY).none {
                it.keyData().code == COPY.keyData().code
            } shouldBe true
        }

        test("never an action that already hosts one") {
            pairing(COPY to CUT).eligibleChildrenFor(SETTINGS).none {
                it.keyData().code == COPY.keyData().code
            } shouldBe true
        }

        test("repeating actions and the mic are offered") {
            val offered = QuickActionSecondActions.Default.eligibleChildrenFor(COPY).map { it.keyData().code }
            offered.contains(KeyCode.ARROW_LEFT) shouldBe true
            offered.contains(KeyCode.IME_UI_MODE_DICTATE) shouldBe true
        }
    }
})
