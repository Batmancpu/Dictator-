/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.symbols

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

/** The user's own symbol pages (issue #342): fixed geometry, defaults, typed input and the file. */
class CustomSymbolsTest : FunSpec({

    fun text(vararg values: String) = values.map { SymbolValue.Text(it) }

    test("the pages have the built-in pages' rows, so their keys keep their height") {
        CustomSymbols().rows(0).map { it.size } shouldBe listOf(10, 7)
        CustomSymbols().rows(1).map { it.size } shouldBe listOf(10, 10, 7)
    }

    test("switching the pages on keeps every symbol of the built-in western pages") {
        val defaults = SymbolDefaults.PAGES.flatten().flatMap { listOf(it.value) + it.longPress }
        // Built-in page 1, then page 2, without the currency keys (those are kept as currency keys).
        defaults shouldContainAll text(
            "@", "#", "%", "&", "-", "+", "(", ")", "/", "*", "\"", "'", ":", ";", "!", "?",
            "~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆", "^", "°", "=", "{", "}", "\\",
            "_", "©", "®", "™", "✓", "[", "]",
        )
        defaults shouldContainAll (1..6).map { SymbolValue.Currency(it) }
    }

    test("a changed key replaces only itself, and setting it back to the default forgets the change") {
        val sum = SymbolKey(SymbolValue.Text("∑"))
        val changed = CustomSymbols().with(1, 5, sum)
        changed.key(1, 5) shouldBe sum
        changed.key(1, 6) shouldBe SymbolDefaults.key(1, 6)
        changed.with(1, 5, SymbolDefaults.key(1, 5)) shouldBe CustomSymbols()
        changed.with(1, 5, null) shouldBe CustomSymbols()
    }

    test("symbols separated by any number of spaces are that many symbols") {
        SymbolText.tokens("✔ ☑ ✅") shouldBe listOf("✔", "☑", "✅")
        SymbolText.tokens("  ✔   ☑\t✅ ") shouldBe listOf("✔", "☑", "✅")
        SymbolText.tokens("✔☑") shouldBe listOf("✔☑")
    }

    test("emoji sequences survive, control characters and whitespace do not") {
        SymbolText.clean("👨‍👩‍👧") shouldBe "👨‍👩‍👧"
        SymbolText.clean("❤️") shouldBe "❤️"
        SymbolText.clean("a\u0007b\n") shouldBe "ab"
        SymbolText.clean(" \n ") shouldBe null
        SymbolText.clean("x".repeat(100)) shouldBe "x".repeat(CustomSymbols.MAX_CODE_POINTS)
    }

    test("a long press keeps its order, drops repeats and stops at two popup rows") {
        SymbolText.longPress(text("b", "a", "b", "c")) shouldBe text("b", "a", "c")
        SymbolText.longPress(text(*Array(30) { "$it" })).size shouldBe CustomSymbols.MAX_LONG_PRESS
    }

    test("the app's own copy holds only the changes, an export every key, and both read back") {
        val symbols = CustomSymbols()
            .with(0, 0, SymbolKey(SymbolValue.Text("₹"), text("$", "€")))
            .with(1, 26, SymbolKey(SymbolValue.Text("❤️")))
            .with(1, 3, SymbolKey(SymbolValue.Text("•"), text("jannis@example.com")))
        val stored = CustomSymbolsJson.encode(symbols, full = false)
        CustomSymbolsJson.decode(stored) shouldBe symbols
        val exported = CustomSymbolsJson.encode(symbols, full = true)
        CustomSymbolsJson.decode(exported) shouldBe symbols
        // Every key is written out, one to a line, the currency keys included.
        exported.lines().count { it.trimStart().startsWith("{\"key\":") } shouldBe 17 + 27
        exported.contains("{\"key\":{\"currency\":1}") shouldBe true
    }

    test("a hand-written file may be short, use bare strings, and loses only what it got wrong") {
        val file = """
            { "format": "dictate-symbols", "version": 1,
              "pages": [ [ "₹", { "key": "✓", "longPress": ["✔", "", 7, "✅"] }, { "key": "" }, 12 ] ] }
        """.trimIndent()
        val symbols = CustomSymbolsJson.decode(file)!!
        symbols.key(0, 0) shouldBe SymbolKey(SymbolValue.Text("₹"))
        symbols.key(0, 1) shouldBe SymbolKey(SymbolValue.Text("✓"), text("✔", "✅"))
        symbols.key(0, 2) shouldBe SymbolDefaults.key(0, 2)
        symbols.key(0, 3) shouldBe SymbolDefaults.key(0, 3)
        symbols.key(1, 0) shouldBe SymbolDefaults.key(1, 0)
    }

    test("anything that is not a symbols file is refused") {
        CustomSymbolsJson.decode("") shouldBe null
        CustomSymbolsJson.decode("[1, 2]") shouldBe null
        CustomSymbolsJson.decode("""{ "pages": [] }""") shouldBe null
        CustomSymbolsJson.decode("""{ "format": "something-else", "pages": [] }""") shouldBe null
    }
})
