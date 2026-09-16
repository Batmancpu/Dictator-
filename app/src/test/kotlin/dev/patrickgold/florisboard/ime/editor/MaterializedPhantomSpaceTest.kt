/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.editor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The space an accepted candidate promises, written at once instead of in front of the next word
 * (issue #393).
 *
 * The pair of rules has one job between them: the field must end up holding exactly the text it held
 * before, only a keystroke earlier. So the two lists they read are the two halves of the phantom
 * space's own rule — what may stand in front of a promised space, and what may stand behind one.
 */
class MaterializedPhantomSpaceTest {

    /** What the shipped `default` punctuation rule declares. */
    private val preceding = ".,;:?‽!&%)]}»©®™"
    private val following = "¿⸘¡([{"

    private fun materializes(
        candidate: String,
        textAfter: String = "",
        supportsAutoSpace: Boolean = true,
    ) = shouldMaterializePhantomSpace(
        candidate = candidate,
        textAfterCandidate = textAfter,
        supportsAutoSpace = supportsAutoSpace,
        symbolsPrecedingPhantomSpace = preceding,
    )

    private fun survives(next: String) = materializedSpaceSurvives(next, following)

    @Test
    fun `a picked word gets its space at once`() {
        assertTrue(materializes("hello"))
        assertTrue(materializes("top10"))
    }

    @Test
    fun `a candidate ending in a mark that takes a space after it gets one too`() {
        for (mark in preceding) {
            assertTrue(materializes("etc$mark"), "expected $mark to take a space after it")
        }
    }

    @Test
    fun `an emoji does not`() {
        // The phantom space never put one there either: nothing but a letter, a digit or one of the
        // opening marks below ever redeemed it, so an emoji followed by a word stayed tight.
        assertFalse(materializes("😄"))
    }

    @Test
    fun `a language that does not space its words gets nothing`() {
        // Chinese, Japanese: the phantom space refuses these outright, and a visible space would be a
        // typo rather than a preview of one.
        assertFalse(materializes("你好", supportsAutoSpace = false))
    }

    @Test
    fun `completing a word in the middle of a sentence does not double the space that is already there`() {
        assertFalse(materializes("hello", textAfter = " world"))
        assertFalse(materializes("hello", textAfter = "\nworld"))
    }

    @Test
    fun `completing a word at the end of the field does`() {
        assertTrue(materializes("hello", textAfter = ""))
    }

    @Test
    fun `completing a word right in front of a punctuation mark does`() {
        // The mark takes the space back when it is typed; what is already standing there does not.
        assertTrue(materializes("hello", textAfter = ", world"))
    }

    @Test
    fun `an empty candidate materializes nothing`() {
        assertFalse(materializes(""))
    }

    @Test
    fun `a word behind it keeps the space`() {
        assertTrue(survives("w"))
        assertTrue(survives("4"))
    }

    @Test
    fun `an opening mark keeps it`() {
        for (mark in following) {
            assertTrue(survives(mark.toString()), "expected $mark to keep the space")
        }
    }

    @Test
    fun `punctuation takes it back`() {
        assertFalse(survives(","))
        assertFalse(survives("."))
        assertFalse(survives(")"))
        assertFalse(survives("'"))
    }

    @Test
    fun `a space takes it back, so the two never add up to a double space`() {
        assertFalse(survives(" "))
        assertFalse(survives("\n"))
    }

    @Test
    fun `an empty commit has no opinion`() {
        // Deleting a selection commits `""`. It is not a character arriving behind the space and must
        // not be read as one.
        assertTrue(survives(""))
    }
}
