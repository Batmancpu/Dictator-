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
 * button off in the other direction instead. The window is therefore measured from the wall the bubble
 * is on, and the window manager pins that edge itself; what the controller owns is the conversion it
 * reads and writes that window through. So what these tests pin down is the promise itself: the end the
 * finger is at does not move, whatever the pill does to the other one.
 *
 * Measurements are a 1080 px screen with a pill that is 144 px at rest and 486 px open, roughly what the
 * stock size produces at a phone's density.
 */
class BubbleOpenDirectionTest {

    private val screen = 1080
    private val resting = 144
    private val open = 486
    private val margin = 24

    private fun fromWall(x: Int, width: Int, onRight: Boolean) = bubbleXFromWall(x, width, screen, onRight)

    /** A pill at the right wall keeps its gap to that wall whatever its width, so its right edge stays put. */
    @Test
    fun `a pill on the right grows inwards from a fixed right edge`() {
        val restingLeft = fromWall(margin, resting, onRight = true)
        val openLeft = fromWall(margin, open, onRight = true)
        assertEquals(screen - margin, restingLeft + resting)
        assertEquals(restingLeft + resting, openLeft + open, "the right edge moved")
    }

    /** On the left the gap to the wall *is* the left edge, so there is nothing to convert. */
    @Test
    fun `a pill on the left grows outwards from a fixed left edge`() {
        assertEquals(margin, fromWall(margin, resting, onRight = false))
        assertEquals(margin, fromWall(margin, open, onRight = false))
    }

    /** Dropped in the open with snapping off, the same gap to the right wall still holds the right edge. */
    @Test
    fun `a pill dropped in the open still grows away from the side it is on`() {
        val left = 400
        val gap = fromWall(left, resting, onRight = true)
        assertEquals(left + resting, fromWall(gap, open, onRight = true) + open, "the right edge moved")
    }

    /** The conversion is its own inverse, so a left edge written from the wall reads back as itself. */
    @Test
    fun `reading back a left edge through the wall gives the same edge`() {
        for (left in listOf(0, margin, 400, screen - open)) {
            assertEquals(left, fromWall(fromWall(left, open, onRight = true), open, onRight = true))
            assertEquals(left, fromWall(fromWall(left, open, onRight = false), open, onRight = false))
        }
    }

    /** The resting place is the margin on either side, which is where the old left-edge rule put it. */
    @Test
    fun `the margin from the wall is the resting spot on both sides`() {
        assertEquals(screen - resting - margin, fromWall(margin, resting, onRight = true))
        assertEquals(margin, fromWall(margin, resting, onRight = false))
    }
}
