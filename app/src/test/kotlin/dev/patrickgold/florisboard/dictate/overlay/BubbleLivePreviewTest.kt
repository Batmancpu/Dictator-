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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The floating button's live preview shows the end of the transcript, like a subtitle (discussion #453):
 * whole words, an ellipsis where the beginning was let go, and never more than its budget.
 */
class BubbleLivePreviewTest {

    @Test
    fun `a short transcript is shown as it is`() {
        assertEquals("Hello there", BubbleLivePreview.tail("  Hello   there ", maxChars = 20))
    }

    @Test
    fun `a long transcript keeps its end and starts at a word`() {
        val shown = BubbleLivePreview.tail("one two three four five six", maxChars = 12)
        assertEquals("…five six", shown)
        assertTrue(shown.length <= 12)
    }

    /** When the lines still do not fit, the oldest word goes next, and the ellipsis stays a single one. */
    @Test
    fun `letting go of the first word keeps one ellipsis`() {
        assertEquals("…two three", BubbleLivePreview.dropFirstWord("one two three"))
        assertEquals("…three", BubbleLivePreview.dropFirstWord("…two three"))
    }

    /** One word left: nothing more to let go, or the card would be empty. */
    @Test
    fun `the last word is never dropped`() {
        assertEquals(null, BubbleLivePreview.dropFirstWord("…three"))
        assertEquals(null, BubbleLivePreview.dropFirstWord("three"))
    }

    @Test
    fun `a word longer than the budget is cut rather than lost`() {
        val shown = BubbleLivePreview.tail("Donaudampfschifffahrtsgesellschaft", maxChars = 10)
        assertEquals("…ellschaft", shown)
        assertEquals(10, shown.length)
    }
}
