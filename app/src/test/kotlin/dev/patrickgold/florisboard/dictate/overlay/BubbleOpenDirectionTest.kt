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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which way the pill opens when a recording starts (issue #399).
 *
 * The report is that start and stop are not in the same place: the button is tapped at the right wall,
 * the pill opens inwards from there, and the stop glyph is suddenly a pill's width away. The fix mirrors
 * the pill so the icon stays at the wall — but only where the pill really does open leftwards. Mirroring
 * it in the other cases would move the icon by the same amount in the other direction, so the direction
 * is what these tests pin down, not the side.
 *
 * Measurements are a 1080 px screen with a pill that is 144 px at rest and 486 px open, roughly what the
 * stock size produces at a phone's density.
 */
class BubbleOpenDirectionTest {

    private val screen = 1080
    private val open = 486
    private val resting = 144
    private val margin = 24

    /** The x of a snapped bubble at either wall. */
    private val atLeftWall = margin
    private val atRightWall = screen - resting - margin

    private fun opens(
        edge: BubbleEdge,
        x: Int,
        snapToEdge: Boolean = true,
    ) = bubbleOpensLeftwards(
        edge = edge,
        x = x,
        expandedWidth = open,
        screenWidth = screen,
        snapToEdge = snapToEdge,
    )

    @Test
    fun `a snapped bubble opens away from the wall it is parked at`() {
        assertTrue(opens(BubbleEdge.RIGHT, atRightWall))
        assertFalse(opens(BubbleEdge.LEFT, atLeftWall))
    }

    @Test
    fun `a snapped bubble reads its wall, not the room it happens to have`() {
        // The x a snapped window sits at is about to be recomputed from the anchored edge anyway, so the
        // room left of it says nothing. Right-anchored with the whole screen free to the right still opens
        // leftwards, because the snap will put it back at the wall before it is drawn open.
        assertTrue(opens(BubbleEdge.RIGHT, x = 0))
    }

    @Test
    fun `a bubble dropped in the open grows to the right past a stationary icon`() {
        // Nothing is snapping it anywhere: the window keeps the x it was dropped at, so the left edge —
        // and the icon laid out from it — does not move. Even on the right half of the screen.
        assertFalse(opens(BubbleEdge.LEFT, x = 100, snapToEdge = false))
        assertFalse(opens(BubbleEdge.RIGHT, x = screen - open, snapToEdge = false))
    }

    @Test
    fun `a bubble dropped too near the wall to open has to open inwards`() {
        // One pixel further than the screen can hold is already the case that moves the window's left
        // edge, and with it the icon.
        assertTrue(opens(BubbleEdge.RIGHT, x = screen - open + 1, snapToEdge = false))
        assertTrue(opens(BubbleEdge.RIGHT, x = atRightWall, snapToEdge = false))
    }
}
