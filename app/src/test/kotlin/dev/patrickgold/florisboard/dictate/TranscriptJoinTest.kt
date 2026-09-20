/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TranscriptJoinTest : FunSpec({

    test("word-shaped pieces keep the space that separates them") {
        TranscriptJoin.join("Hey there", "how are you") shouldBe "Hey there how are you"
    }

    test("a piece that is only a sentence mark binds to the word in front of it (issue #356)") {
        // Exactly the reported case: the transducer releases the period as its own segment, seconds
        // after the words settled, because it needs the trailing silence to be sure of it.
        TranscriptJoin.join("Hey there", ".") shouldBe "Hey there."
        TranscriptJoin.join("Was it", "?") shouldBe "Was it?"
        TranscriptJoin.join("Wait", ",") shouldBe "Wait,"
    }

    test("a piece that merely opens with a mark carries its own words along") {
        TranscriptJoin.join("Hey there", ". How are you") shouldBe "Hey there. How are you"
    }

    test("the tightening set decides, so French keeps the space it wants before ? and !") {
        val french = ".,"
        TranscriptJoin.join("Ça va", "?", french) shouldBe "Ça va ?"
        TranscriptJoin.join("Ça va", ".", french) shouldBe "Ça va."
    }

    test("a mark swallows spacing that is already there") {
        TranscriptJoin.join("Hey there ", ".") shouldBe "Hey there."
        TranscriptJoin.join("Hey there", "  .  ") shouldBe "Hey there."
    }

    test("an empty side is not a reason to invent a space") {
        TranscriptJoin.join("", "Hey there") shouldBe "Hey there"
        TranscriptJoin.join("Hey there", "") shouldBe "Hey there"
        TranscriptJoin.join("Hey there", "   ") shouldBe "Hey there"
        TranscriptJoin.join("", ".") shouldBe "."
    }

    test("appendPiece builds the same string the pieces arrive in") {
        val out = StringBuilder()
        listOf("Hey there", ".", "How are you", "?").forEach { TranscriptJoin.appendPiece(out, it) }
        out.toString() shouldBe "Hey there. How are you?"
    }

    test("appendPiece leaves a blank piece alone, including the very first one") {
        val out = StringBuilder()
        TranscriptJoin.appendPiece(out, "  ")
        out.toString() shouldBe ""
        TranscriptJoin.appendPiece(out, "Hey")
        TranscriptJoin.appendPiece(out, "")
        out.toString() shouldBe "Hey"
    }

    test("tighten takes the space out from in front of a mark inside one piece") {
        // Verbatim from NVIDIA's German FastConformer, whose vocabulary spells `▁,` and `▁.` — "space,
        // then the mark" — as its two most frequent tokens, so this is what it genuinely predicts.
        TranscriptJoin.tighten("Alles hat ein Ende , nur die Wurst hat zwei .") shouldBe
            "Alles hat ein Ende, nur die Wurst hat zwei."
        TranscriptJoin.tighten("Hast du die Datei schon abgeschickt ?") shouldBe
            "Hast du die Datei schon abgeschickt?"
    }

    test("tighten leaves a transcript that was already spaced correctly untouched") {
        // The English Parakeet and GigaAM write their marks against the word; this must be a no-op there.
        val clean = "Well, I don't wish to see it any more. It is very like the old portrait."
        TranscriptJoin.tighten(clean) shouldBe clean
        TranscriptJoin.tighten("") shouldBe ""
    }

    test("tighten obeys the same set, so French keeps the space before ? and !") {
        val french = ".,"
        TranscriptJoin.tighten("Ça va ?", french) shouldBe "Ça va ?"
        TranscriptJoin.tighten("Ça va , vraiment .", french) shouldBe "Ça va, vraiment."
        // An empty set is a caller saying "no mark binds backwards here" and must change nothing.
        TranscriptJoin.tighten("Ça va ?", "") shouldBe "Ça va ?"
    }

    test("tighten closes a whole run of spaces in front of a mark, and consecutive marks too") {
        TranscriptJoin.tighten("Moment   , bitte") shouldBe "Moment, bitte"
        TranscriptJoin.tighten("Warte .  .  .") shouldBe "Warte..."
    }

    test("tighten reflows nothing else — only the space in front of a mark is its business") {
        // Spacing *after* a mark is left exactly as the model wrote it. Collapsing that too would mean
        // rewriting whitespace nobody complained about, on every transcript, to fix one tokenizer.
        TranscriptJoin.tighten("Moment   ,  bitte") shouldBe "Moment,  bitte"
        TranscriptJoin.tighten("zwei  Wörter") shouldBe "zwei  Wörter"
    }

    test("the default set is the conservative rule, not a superset invented here") {
        // Mirrors the "default" punctuation rule in the localization extension; a caller that can reach
        // the active rule passes it instead.
        TranscriptJoin.DEFAULT_TIGHTENING_SYMBOLS shouldBe ".,;:?!‽"
    }
})
