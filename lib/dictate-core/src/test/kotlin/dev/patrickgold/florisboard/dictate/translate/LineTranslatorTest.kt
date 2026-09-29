/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.translate

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Line breaks kept where the user typed them, and each line sent to the engine once (issue #433). */
class LineTranslatorTest : FunSpec({

    /** An engine that upper-cases, and records every batch it was asked for. */
    class Engine {
        val asked = mutableListOf<List<String>>()
        fun translate(lines: List<String>): List<String> {
            asked.add(lines)
            return lines.map { it.uppercase() }
        }
    }

    test("a text without breaks is one line, as before") {
        val engine = Engine()
        LineTranslator().translate("de-en", "Hallo Welt", engine::translate) shouldBe "HALLO WELT"
        engine.asked shouldBe listOf(listOf("Hallo Welt"))
    }

    test("breaks and blank lines stay where they are, and blank lines never reach the engine") {
        val engine = Engine()
        LineTranslator().translate("de-en", "Hallo\n\nwie geht's\n", engine::translate) shouldBe "HALLO\n\nWIE GEHT'S\n"
        engine.asked shouldBe listOf(listOf("Hallo", "wie geht's"))
    }

    test("a keystroke in one line asks only for that line") {
        val engine = Engine()
        val lines = LineTranslator()
        lines.translate("de-en", "Hallo\nwie", engine::translate)
        lines.translate("de-en", "Hallo\nwie g", engine::translate) shouldBe "HALLO\nWIE G"
        engine.asked shouldBe listOf(listOf("Hallo", "wie"), listOf("wie g"))
    }

    test("a line that appears twice is translated once") {
        val engine = Engine()
        LineTranslator().translate("de-en", "ja\nja", engine::translate) shouldBe "JA\nJA"
        engine.asked shouldBe listOf(listOf("ja"))
    }

    test("another language pair forgets what the last one translated") {
        val engine = Engine()
        val lines = LineTranslator()
        lines.translate("de-en", "Hallo", engine::translate)
        lines.translate("en-de", "Hallo", engine::translate)
        engine.asked shouldBe listOf(listOf("Hallo"), listOf("Hallo"))
    }

    test("a text with more new lines than it remembers still comes back whole") {
        val engine = Engine()
        val lines = LineTranslator(capacity = 2)
        lines.translate("de-en", "a\nb", engine::translate)
        lines.translate("de-en", "a\nb\nc\nd", engine::translate) shouldBe "A\nB\nC\nD"
    }
})
