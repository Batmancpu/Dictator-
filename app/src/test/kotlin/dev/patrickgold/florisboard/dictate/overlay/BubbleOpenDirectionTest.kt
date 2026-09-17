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
 * Which end of the bubble holds still when it changes width (issue #399).
 *
 * The report is that start and stop are not in the same place: the button is tapped, the pill opens to
 * make room for a timer and a waveform, and the stop glyph has moved. Mirroring the row is only half of
 * the answer — the window underneath it has to grow away from the same side, or the mirror carries the
 * button off in the other direction instead. So what these tests pin down is the promise itself: the end
 * the finger is at does not move, whatever the pill does to the other one.
 *
 * Measurements are a 1080 px screen with a pill that is 144 px at rest and 486 px open, roughly what the
 * stock size produces at a phone's density.
 */
class BubbleOpenDirectionTest {

    private val screen = 1080
    private val resting = 144
    private val open = 486
    private val margin = 24
    private val growth = open - resting

    /** The free travel left for a bubble [width] px wide. */
    private fun maxX(width: Int) = screen - width

    private fun placed(
        x: Int,
        widthDelta: Int,
        onRight: Boolean,
        newWidth: Int,
        snapToEdge: Boolean = true,
    ) = bubbleXAfterResize(
        x = x,
        widthDelta = widthDelta,
        onRight = onRight,
        maxX = maxX(newWidth),
        margin = margin,
        snapToEdge = snapToEdge,
    )

    /** Opening a pill snapped at the right wall leaves its right edge exactly where it was. */
    @Test
    fun `a snapped pill on the right grows inwards from a fixed right edge`() {
        val x = maxX(resting) - margin
        val opened = placed(x, growth, onRight = true, newWidth = open)
        assertEquals(x + resting, opened + open, "the right edge moved")
        assertEquals(maxX(open) - margin, opened)
    }

    /** And on the left, its left edge. */
    @Test
    fun `a snapped pill on the left grows outwards from a fixed left edge`() {
        val opened = placed(margin, growth, onRight = false, newWidth = open)
        assertEquals(margin, opened, "the left edge moved")
    }

    /**
     * The same promise with snapping off, which is where it used to break: the x was merely clamped, so a
     * pill with room to its right grew straight out from under the finger that had opened it.
     */
    @Test
    fun `a pill dropped in the open still grows away from the side it is on`() {
        // Right-hand side, with room to spare to the right: the right edge is still the one that holds.
        val x = 400
        val opened = placed(x, growth, onRight = true, newWidth = open, snapToEdge = false)
        assertEquals(x + resting, opened + open, "the right edge moved")
        // Left-hand side: the x it was dropped at is the one that holds.
        assertEquals(x, placed(x, growth, onRight = false, newWidth = open, snapToEdge = false))
    }

    /** Collapsing is the same rule with the sign turned round, so the button lands back where it started. */
    @Test
    fun `collapsing returns the pill to the spot it opened from`() {
        val x = 400
        val opened = placed(x, growth, onRight = true, newWidth = open, snapToEdge = false)
        val closed = placed(opened, -growth, onRight = true, newWidth = resting, snapToEdge = false)
        assertEquals(x, closed)
    }

    /** Nothing may be placed off-screen, however the arithmetic came out. */
    @Test
    fun `a pill too wide for the room it has is kept on screen`() {
        assertEquals(0, placed(x = 8, widthDelta = growth, onRight = true, newWidth = open, snapToEdge = false))
        assertEquals(
            maxX(open),
            placed(x = screen, widthDelta = 0, onRight = false, newWidth = open, snapToEdge = false),
        )
    }
}
