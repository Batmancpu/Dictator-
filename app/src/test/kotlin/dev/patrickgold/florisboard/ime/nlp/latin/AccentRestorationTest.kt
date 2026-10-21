/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules [AccentRestoration] applies on top of the dictionary (issue #451). How well they do on real
 * text is `PolishRestorationEvalTest`; this holds down what each rule is for.
 */
class AccentRestorationTest {

    private fun evidence(spelling: String, count: Int, total: Long) = AccentRestoration.Evidence(spelling, count, total)

    @Test
    fun `without a sentence the commonest spelling leads`() {
        val forms = listOf("cześć", "część")
        assertEquals(forms, AccentRestoration.restorations("pl", "czesc", forms, null))
    }

    /** `jedna czesc` is a part, not a greeting — the sentence moves część to the front. */
    @Test
    fun `the sentence puts its own spelling first`() {
        val forms = listOf("cześć", "część")
        assertEquals(
            listOf("część", "cześć"),
            AccentRestoration.restorations("pl", "czesc", forms, evidence("część", 120, 121)),
        )
        // Evidence for a spelling the dictionary does not hold changes nothing.
        assertEquals(forms, AccentRestoration.restorations("pl", "czesc", forms, evidence("czesc", 5, 5)))
    }

    /**
     * A restoration only adds accents. Typed with its ą, `kompozycją` is a correct word the list lacks,
     * and the fold must not turn it into the `kompozycja` the list happens to have.
     */
    @Test
    fun `accents already typed are never taken away`() {
        assertEquals(emptyList(), AccentRestoration.restorations("pl", "kompozycją", listOf("kompozycja"), null))
        // One of two accents typed: only the spelling that keeps it is offered.
        assertEquals(
            listOf("dziękuję"),
            AccentRestoration.restorations("pl", "dziekuję", listOf("dziękuję", "dziękuje"), null),
        )
        assertTrue(AccentRestoration.keepsTypedAccents("Zolw", "żółw"))
        assertTrue(AccentRestoration.keepsTypedAccents("ŻOLW", "żółw"))
        assertFalse(AccentRestoration.keepsTypedAccents("żolw", "zółw"))
    }

    /** Arabic folds letter forms, not accents, and keeps offering every one of them as it always did. */
    @Test
    fun `arabic keeps every form`() {
        val forms = listOf("أن", "إن", "آن")
        assertEquals(forms, AccentRestoration.restorations("ar", "ان", forms, null))
    }

    /** `ze` is a word, so only an overwhelming sentence may swap it — and only ever towards more accents. */
    @Test
    fun `a correct word yields only to a dominant sentence`() {
        val forms = listOf("że", "ze")
        assertEquals("że" to true, AccentRestoration.rival("ze", forms, evidence("że", 6400, 6500)))
        assertEquals("że" to false, AccentRestoration.rival("ze", forms, evidence("że", 900, 1000)))
        // Strong, but too few sightings to be a fact about the language.
        assertEquals("że" to false, AccentRestoration.rival("ze", forms, evidence("że", 40, 40)))
        // The sentence agrees with what was typed, or says nothing.
        assertNull(AccentRestoration.rival("ze", forms, evidence("ze", 400, 410)))
        assertNull(AccentRestoration.rival("ze", forms, null))
        // `że` typed in a sentence that usually writes `ze`: the accent stays.
        assertNull(AccentRestoration.rival("że", forms, evidence("ze", 6400, 6410)))
    }

    @Test
    fun `dominance counts every other spelling`() {
        assertTrue(AccentRestoration.mayReplace(evidence("że", 64, 65)))
        assertFalse(AccentRestoration.mayReplace(evidence("że", 127, 129)))
        assertTrue(AccentRestoration.mayReplace(evidence("że", 128, 130)))
        assertFalse(AccentRestoration.mayReplace(evidence("że", 49, 49)))
    }
}
