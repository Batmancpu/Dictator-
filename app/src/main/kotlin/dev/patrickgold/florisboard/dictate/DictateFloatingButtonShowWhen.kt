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

/**
 * When the floating dictation button comes up (issue #439).
 *
 * This used to be two switches that answered the same question from two sides: "Show with Dictate keyboard"
 * and "Only while the keyboard is open". One of their four combinations — waiting for a keyboard, and then
 * also standing over Dictate's own — was never asked for and never existed before either switch, so the
 * choice is three entries. [FIELD_SELECTED] is what the button has always done, and the default.
 */
enum class DictateFloatingButtonShowWhen {
    /**
     * As soon as an editable field holds focus, keyboard or not. This is what apps whose fields never call
     * themselves editable and hardware-keyboard users depend on. Steps aside for the Dictate keyboard, which
     * has a mic key of its own.
     */
    FIELD_SELECTED,

    /**
     * Only while an on-screen keyboard is open — any keyboard's input-method window. A messenger that gives
     * its composer focus the moment a chat opens no longer brings the button up over a chat being read.
     * Steps aside for the Dictate keyboard like [FIELD_SELECTED].
     */
    KEYBOARD_OPEN,

    /** Like [FIELD_SELECTED], and also while the Dictate keyboard is up. */
    ALSO_WITH_DICTATE_KEYBOARD;

    /** Whether a focused field needs an on-screen keyboard beside it before it counts. */
    val keyboardRequired: Boolean
        get() = this == KEYBOARD_OPEN

    /** Whether the button stays up while the Dictate keyboard itself is on screen. */
    val besideDictateKeyboard: Boolean
        get() = this == ALSO_WITH_DICTATE_KEYBOARD

    companion object {
        /**
         * The value the old `dictate__floating_button_show_with_dictate_keyboard` switch stood for, from its
         * raw stored form. On was "also with the Dictate keyboard"; off, or anything unreadable, the default.
         */
        fun fromShowWithDictateKeyboard(rawValue: String): DictateFloatingButtonShowWhen =
            if (rawValue == "true") ALSO_WITH_DICTATE_KEYBOARD else FIELD_SELECTED
    }
}
