/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The choice that replaced the "Show with Dictate keyboard" switch (issue #439). The migration is the part
 * that must not go wrong quietly: a wrong answer here changes someone's setting without them touching it.
 */
class DictateFloatingButtonShowWhenTest {

    @Test
    fun `the old switch keeps its meaning`() {
        assertEquals(
            DictateFloatingButtonShowWhen.ALSO_WITH_DICTATE_KEYBOARD,
            DictateFloatingButtonShowWhen.fromShowWithDictateKeyboard("true"),
        )
        assertEquals(
            DictateFloatingButtonShowWhen.FIELD_SELECTED,
            DictateFloatingButtonShowWhen.fromShowWithDictateKeyboard("false"),
        )
        // Anything unreadable falls back to what the button has always done.
        assertEquals(
            DictateFloatingButtonShowWhen.FIELD_SELECTED,
            DictateFloatingButtonShowWhen.fromShowWithDictateKeyboard(""),
        )
    }

    /** Each entry sets exactly one of the two terms the visibility rule asks about, or neither. */
    @Test
    fun `each entry says what it changes`() {
        with(DictateFloatingButtonShowWhen.FIELD_SELECTED) {
            assertFalse(keyboardRequired)
            assertFalse(besideDictateKeyboard)
        }
        with(DictateFloatingButtonShowWhen.KEYBOARD_OPEN) {
            assertTrue(keyboardRequired)
            assertFalse(besideDictateKeyboard)
        }
        with(DictateFloatingButtonShowWhen.ALSO_WITH_DICTATE_KEYBOARD) {
            assertFalse(keyboardRequired)
            assertTrue(besideDictateKeyboard)
        }
    }
}
