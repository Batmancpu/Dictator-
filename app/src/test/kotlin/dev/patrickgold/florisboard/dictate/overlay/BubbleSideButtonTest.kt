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

/**
 * Where the buttons beside the floating button stand: always on the inward side, cancel right beside the
 * bubble where it has always been, pause one place further out — or in cancel's place when cancel is not
 * wanted, which is the same slot arithmetic with a smaller number.
 *
 * Measurements are a 1080 px screen, a bubble window 200 px wide with its visible shape 10 px inside it,
 * buttons of 90 px and a gap of 36 px — roughly what the stock sizes come to at a phone's density.
 */
class BubbleSideButtonTest {

    private val screen = 1080
    private val width = 200
    private val inset = 10
    private val gap = 36
    private val size = 90

    private fun x(slot: Int, onRight: Boolean, left: Int) =
        BubbleSideButton.slotX(slot, onRight, left, width, inset, gap, size, screen)

    /** On the right wall the buttons line up to the left of the shape, one gap apart. */
    @Test
    fun `on the right the slots run leftwards from the shape`() {
        val left = screen - width // parked against the right wall
        assertEquals(left + inset - gap - size, x(0, onRight = true, left = left))
        assertEquals(left + inset - gap - size - (size + gap), x(1, onRight = true, left = left))
    }

    /** On the left wall they line up to the right of it, mirrored. */
    @Test
    fun `on the left the slots run rightwards from the shape`() {
        assertEquals(width - inset + gap, x(0, onRight = false, left = 0))
        assertEquals(width - inset + gap + size + gap, x(1, onRight = false, left = 0))
    }

    /** A bubble dragged so far that a button would leave the screen keeps the button on it. */
    @Test
    fun `a slot never leaves the screen`() {
        assertEquals(0, x(1, onRight = true, left = 100))
        assertEquals(screen - size, x(1, onRight = false, left = screen - width))
    }

    /** The dialog and the row read one list: cancel first, then pause, then undo. */
    @Test
    fun `the buttons keep their order outwards`() {
        assertEquals(
            listOf(BubbleSideButton.CANCEL, BubbleSideButton.PAUSE, BubbleSideButton.UNDO),
            BubbleSideButton.entries.toList(),
        )
    }
}
