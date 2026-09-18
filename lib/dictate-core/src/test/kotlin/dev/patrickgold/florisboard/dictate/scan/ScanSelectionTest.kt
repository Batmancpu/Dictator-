/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.scan

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Joining tapped lines (issue #390). The case that decides the rule is an address on an envelope: three
 * lines the recogniser saw as one block, which have to arrive as three lines and not as one run-on
 * string somebody then has to break by hand.
 *
 * The second test below is the price of that decision, written down on purpose rather than left as a
 * surprise: a sentence the camera caught across two lines also arrives as two lines.
 */
class ScanSelectionTest : FunSpec({

    // Two blocks: an address (0) and a sentence the camera caught across two lines (1).
    val scan = ScanText(
        listOf(
            ScanLine.ofRect("Erika Mustermann", blockIndex = 0, left = 0f, top = 0.0f, right = 0.6f, bottom = 0.05f),
            ScanLine.ofRect("Heidestraße 17", blockIndex = 0, left = 0f, top = 0.06f, right = 0.5f, bottom = 0.11f),
            ScanLine.ofRect("51147 Köln", blockIndex = 0, left = 0f, top = 0.12f, right = 0.4f, bottom = 0.17f),
            ScanLine.ofRect("Bitte den Beleg im", blockIndex = 1, left = 0f, top = 0.30f, right = 0.7f, bottom = 0.35f),
            ScanLine.ofRect("Original aufbewahren", blockIndex = 1, left = 0f, top = 0.36f, right = 0.7f, bottom = 0.41f),
        ),
    )

    test("lines of one block become one line each, because that block is an address") {
        ScanSelection.join(scan, setOf(0, 1, 2)) shouldBe "Erika Mustermann\nHeidestraße 17\n51147 Köln"
    }

    test("a wrapped sentence keeps its break, which is the accepted cost of the rule above") {
        // One backspace to fix. The other direction — an address arriving as one run-on line — costs
        // finding two positions in it and pressing Enter twice, so this is the cheaper way to be wrong.
        ScanSelection.join(scan, setOf(3, 4)) shouldBe "Bitte den Beleg im\nOriginal aufbewahren"
    }

    test("tap order does not matter, reading order does") {
        // Tapping the town first must still insert the name first: what was selected is a region of the
        // page, not a sequence of presses.
        ScanSelection.join(scan, setOf(2, 0, 1)) shouldBe ScanSelection.join(scan, setOf(0, 1, 2))
    }

    test("two lines far apart on the page are still just two lines") {
        // Nothing is inferred from the gap between them either — no blank line, no separator.
        ScanSelection.join(scan, setOf(2, 3)) shouldBe "51147 Köln\nBitte den Beleg im"
    }

    test("everything, and nothing") {
        ScanSelection.all(scan) shouldBe
            "Erika Mustermann\nHeidestraße 17\n51147 Köln\nBitte den Beleg im\nOriginal aufbewahren"
        ScanSelection.join(scan, emptySet()) shouldBe ""
        ScanSelection.all(ScanText.Empty) shouldBe ""
    }

    test("an index that is not on the page is ignored rather than fatal") {
        ScanSelection.join(scan, setOf(0, 99)) shouldBe "Erika Mustermann"
    }
})
