/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.field

import dev.patrickgold.florisboard.dictate.field.TranscriptHandover.continuation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** A live dictation the user has edited carries on after what they took over, and only after it (#421). */
class TranscriptHandoverTest : FunSpec({

    test("nothing handed over: the whole transcript is still the dictation's") {
        continuation("", " Hello there") shouldBe " Hello there"
    }

    test("words said after the handover follow it, the seam left to the caller") {
        continuation("This is the first sentence.", "This is the first sentence. And here is more") shouldBe
            "And here is more"
        continuation("Hello there", "Hello there, friend") shouldBe ", friend"
        continuation("Hello there", "Hello there") shouldBe ""
    }

    test("a revision of the taken-over words does not bring them back") {
        // The provider capitalised and finished the sentence the user had taken over as a partial.
        continuation(
            "First. someone in the room said something",
            "First. Someone in the room said something wrong here. and now",
        ) shouldBe "wrong here. and now"
        // ...and corrected its last word: "taste" stays as the user has it, the full stop is new.
        continuation(
            "and now the speaker comes back to the taste",
            "And now the speaker comes back to the test. The fourth sentence",
        ) shouldBe ". The fourth sentence"
        // A filler word the final dropped does not shift the cut into the next word.
        continuation("so um I think", "So I think that we should") shouldBe "that we should"
    }

    test("a word taken over half-written keeps no tail") {
        continuation("of a long dicta", "of a long dictation. someone in") shouldBe ". someone in"
        continuation("we don'", "we don't know") shouldBe "know"
        continuation("an ice-cr", "an ice-cream please") shouldBe "please"
    }

    test("scripts without spaces go on at the next character") {
        continuation("我今天去", "我今天去商店买东西。") shouldBe "商店买东西。"
    }

    test("a provider that took words back owes nothing more than the mark it added") {
        continuation("this is a test and", "this is a test") shouldBe ""
        continuation("this is a test and so", "This is a test.") shouldBe "."
    }

    test("a whole other transcript of the same audio continues after the matching stretch") {
        // The batch fallback re-transcribes the recording when a stream fails; its wording differs a little.
        continuation(
            "okay so the meeting is on tuesday at ten we need the slides",
            "Okay, so the meeting is on Tuesday at 10. We need the slides and the budget by then.",
        ) shouldBe "and the budget by then."
    }
})
