/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.overlay

import dev.patrickgold.florisboard.dictate.DictateController
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the floating button is on screen (issue #339).
 *
 * The reported failure is the first test: the button stood on the home screen after a finished
 * dictation, because the rate/donate nudge armed at the end of it left the state machine off idle — and
 * "not idle" was being read as "a dictation is running". The second test is the behaviour that must not
 * be traded away for it (#293): a recording keeps the button even after the user has left the app,
 * because the microphone is still open.
 */
class BubbleVisibilityTest {

    private val recording = DictateController.UiState.Recording(startedAtMs = 0L)
    private val transcribing = DictateController.UiState.Transcribing(startedAtMs = 0L)
    private val rewording = DictateController.UiState.Rewording("Formal")
    private val nudge = DictateController.UiState.Promo(DictateController.PromoKind.RATE)
    private val interrupted = DictateController.UiState.Interrupted(seconds = 12L)
    private val failed = DictateController.UiState.Error(
        message = "No internet connection",
        action = DictateController.ErrorAction.RESEND,
    )

    private fun shown(
        state: DictateController.UiState = DictateController.UiState.Idle,
        focused: Boolean = false,
        keyboardRequired: Boolean = false,
        keyboardShown: Boolean = false,
        enabled: Boolean = true,
        hiddenByOwnKeyboard: Boolean = false,
        recognitionActive: Boolean = false,
        screenOn: Boolean = true,
        allowedInApp: Boolean = true,
    ) = BubbleVisibility.shouldShow(
        enabled = enabled,
        focused = focused,
        keyboardRequired = keyboardRequired,
        keyboardShown = keyboardShown,
        state = state,
        hiddenByOwnKeyboard = hiddenByOwnKeyboard,
        recognitionActive = recognitionActive,
        screenOn = screenOn,
        allowedInApp = allowedInApp,
    )

    @Test
    fun `a parked nudge does not keep the button on a screen with no field`() {
        assertFalse(shown(state = nudge))
        assertFalse(shown(state = interrupted))
        assertFalse(shown(state = failed))
        assertFalse(shown(state = DictateController.UiState.Idle))
    }

    @Test
    fun `work in flight keeps the button even after leaving the app`() {
        assertTrue(shown(state = recording))
        assertTrue(shown(state = transcribing))
        assertTrue(shown(state = rewording))
    }

    @Test
    fun `a focused field is enough on its own`() {
        assertTrue(shown(focused = true))
        assertTrue(shown(state = nudge, focused = true))
        assertTrue(shown(state = failed, focused = true))
    }

    @Test
    fun `only in-flight work pins the button`() {
        assertTrue(BubbleVisibility.pinsBubble(recording))
        assertTrue(BubbleVisibility.pinsBubble(transcribing))
        assertTrue(BubbleVisibility.pinsBubble(rewording))
        assertFalse(BubbleVisibility.pinsBubble(DictateController.UiState.Idle))
        assertFalse(BubbleVisibility.pinsBubble(nudge))
        assertFalse(BubbleVisibility.pinsBubble(interrupted))
        assertFalse(BubbleVisibility.pinsBubble(failed))
    }

    /** Each of the suppressors wins over both reasons to show, including a live recording. */
    @Test
    fun `the suppressors win over everything`() {
        assertFalse(shown(state = recording, focused = true, enabled = false))
        assertFalse(shown(state = recording, focused = true, hiddenByOwnKeyboard = true))
        assertFalse(shown(state = recording, focused = true, recognitionActive = true))
        assertFalse(shown(state = recording, focused = true, screenOn = false))
        assertFalse(shown(state = recording, focused = true, allowedInApp = false))
    }

    /**
     * The decision behind that last line, written out (#392): a dictation started in one app and carried
     * into an app the user has filtered the button out of does **not** bring the button with it. "Never
     * over my banking app" is about the window, and a recording walking in is the one case where the
     * promise would otherwise break. The recording itself is not this rule's business — it belongs to the
     * microphone foreground service and keeps running (#293).
     */
    @Test
    fun `a filtered-out app beats a dictation in flight`() {
        assertTrue(shown(state = recording))
        assertFalse(shown(state = recording, allowedInApp = false))
        assertFalse(shown(state = transcribing, allowedInApp = false))
        assertFalse(shown(focused = true, allowedInApp = false))
    }

    /**
     * "Only while the keyboard is open" (#439): WhatsApp focuses its composer the moment a chat is opened,
     * so the focused field that is enough by default is exactly what must not be enough here.
     */
    @Test
    fun `with the keyboard required a focused field waits for the keyboard`() {
        assertFalse(shown(focused = true, keyboardRequired = true))
        assertTrue(shown(focused = true, keyboardRequired = true, keyboardShown = true))
        // The default is untouched: a keyboard is not needed, and not having one changes nothing.
        assertTrue(shown(focused = true))
    }

    /**
     * It narrows the reason to appear, not what keeps the button up: closing the keyboard in the middle of
     * a recording keeps the stop button.
     */
    @Test
    fun `with the keyboard required work in flight still pins the button`() {
        assertTrue(shown(state = recording, keyboardRequired = true))
        assertTrue(shown(state = transcribing, keyboardRequired = true))
        assertTrue(shown(state = rewording, keyboardRequired = true))
        // A resting state is no work in flight, so it waits for the keyboard like anything else.
        assertFalse(shown(state = failed, focused = true, keyboardRequired = true))
    }

    /** A keyboard on screen is a condition, not a reason: the suppressors still win over it. */
    @Test
    fun `a keyboard on screen does not beat the suppressors`() {
        assertFalse(shown(focused = true, keyboardRequired = true, keyboardShown = true, hiddenByOwnKeyboard = true))
        assertFalse(shown(focused = true, keyboardRequired = true, keyboardShown = true, allowedInApp = false))
    }
}
