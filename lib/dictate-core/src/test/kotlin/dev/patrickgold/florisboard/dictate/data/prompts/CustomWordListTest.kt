/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.prompts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class CustomWordListTest : FunSpec({
    context("parse reads a preference value exactly as the prompt assembler does") {
        test("splits on commas and newlines, trims, drops blanks, keeps duplicates") {
            CustomWordList.parse(" Acme , Kubernetes\n\n OAuth ,, Acme ") shouldContainExactly
                listOf("Acme", "Kubernetes", "OAuth", "Acme")
        }

        test("a duplicate is kept, because the assembled prompt keeps it too") {
            // The count and the token estimate must describe the request that goes out, not a
            // tidier version of it — de-duplication belongs to the import, not to the reading.
            val words = CustomWordList.parse("Acme, Acme")
            words.size shouldBe 2
            DictatePromptDefaults.appendCustomWords(null, "Acme, Acme") shouldBe "Acme, Acme"
        }

        test("null and blank are the empty list") {
            CustomWordList.parse(null) shouldContainExactly emptyList()
            CustomWordList.parse("  ,\n , ") shouldContainExactly emptyList()
        }
    }

    context("parseFile repairs what a file brings that a settings dialog never would") {
        test("one word per line, bullets, numbering, quotes and a stray full stop") {
            val parse = CustomWordList.parseFile(
                """
                # my vocabulary
                - Kubernetes
                1. FlorisBoard
                "Anthropic"
                OAuth.
                • Renée
                """.trimIndent(),
            )
            parse.words shouldContainExactly
                listOf("Kubernetes", "FlorisBoard", "Anthropic", "OAuth", "Renée")
            parse.unusable shouldBe 0
            parse.truncated.shouldBeFalse()
        }

        test("an abbreviation keeps its own full stops") {
            CustomWordList.parseFile("e.V.\nDipl.-Ing.\nKubernetes.").words shouldContainExactly
                listOf("e.V.", "Dipl.-Ing.", "Kubernetes")
        }

        test("a comma-separated file is read too, since that is the preference's own format") {
            CustomWordList.parseFile("Acme, Kubernetes\nOAuth").words shouldContainExactly
                listOf("Acme", "Kubernetes", "OAuth")
        }

        test("prose, punctuation-only lines and over-long entries are counted as unusable") {
            val parse = CustomWordList.parseFile(
                """
                Kubernetes
                ---
                This is a whole sentence that wandered into the word list by accident
                Deutsche Bahn AG
                """.trimIndent(),
            )
            parse.words shouldContainExactly listOf("Kubernetes", "Deutsche Bahn AG")
            parse.unusable shouldBe 2
        }

        test("a blank line is nothing, not a rejection") {
            CustomWordList.parseFile("Acme\n\n\nOAuth").unusable shouldBe 0
        }

        test("a file longer than the entry cap is truncated and says so") {
            val parse = CustomWordList.parseFile((1..CustomWordList.MAX_IMPORT_ENTRIES + 50)
                .joinToString("\n") { "Wort$it" })
            parse.words.size shouldBe CustomWordList.MAX_IMPORT_ENTRIES
            parse.truncated.shouldBeTrue()
        }
    }

    context("the token estimate is what the cap is expressed in") {
        test("empty is zero, and the estimate grows with the list") {
            CustomWordList.estimateTokens(emptyList()) shouldBe 0
            CustomWordList.estimateTokens(listOf("Acme", "Kubernetes")) shouldBeGreaterThan
                CustomWordList.estimateTokens(listOf("Acme"))
        }

        test("non-ASCII costs more per character than ASCII") {
            CustomWordList.estimateTokens(listOf("北京大学")) shouldBeGreaterThan
                CustomWordList.estimateTokens(listOf("Bejin"))
        }

        test("countWithinBudget never exceeds the budget it is given") {
            val words = (1..500).map { "Fachbegriff$it" }
            val fitting = CustomWordList.countWithinBudget(words)
            fitting shouldBeGreaterThan 0
            CustomWordList.estimateTokens(words.take(fitting)) shouldBeLessThanOrEqual
                CustomWordList.TOKEN_BUDGET
            CustomWordList.estimateTokens(words.take(fitting + 1)) shouldBeGreaterThan
                CustomWordList.TOKEN_BUDGET
        }
    }

    context("plan merges instead of replacing, and stops at the budget") {
        test("new words are appended behind what is already there") {
            val report = CustomWordList.plan("Acme, Kubernetes", "OAuth\nRenée")
            report.accepted shouldContainExactly listOf("OAuth", "Renée")
            report.merged shouldBe "Acme, Kubernetes, OAuth, Renée"
            report.alreadyKnown shouldBe 0
        }

        test("a one-word-per-line field stays one word per line") {
            CustomWordList.plan("Acme\nKubernetes", "OAuth").merged shouldBe "Acme\nKubernetes\nOAuth"
        }

        test("an empty field is written one word per line") {
            CustomWordList.plan("", "Acme\nOAuth").merged shouldBe "Acme\nOAuth"
        }

        test("a word already in the list is counted, not added twice") {
            val report = CustomWordList.plan("Acme, Kubernetes", "acme\nOAuth")
            report.alreadyKnown shouldBe 1
            report.accepted shouldContainExactly listOf("OAuth")
        }

        test("a duplicate inside the file is only taken once") {
            val report = CustomWordList.plan("", "OAuth\nOAuth\noauth")
            report.accepted shouldContainExactly listOf("OAuth")
            report.alreadyKnown shouldBe 2
        }

        test("nothing to add reports a null merge, so the caller writes nothing") {
            val report = CustomWordList.plan("Acme", "Acme")
            report.hasSomethingToAdd.shouldBeFalse()
            report.merged shouldBe null
        }

        test("the import stops at the budget and says how many did not fit") {
            val file = (1..600).joinToString("\n") { "Fachbegriff$it" }
            val report = CustomWordList.plan("", file)
            report.didNotFit shouldBeGreaterThan 0
            report.accepted.size + report.didNotFit shouldBe 600
            report.tokensAfter shouldBeLessThanOrEqual CustomWordList.TOKEN_BUDGET
        }

        test("a list already over budget accepts nothing and is left untouched") {
            // Hand-typed lists are never truncated — we do not delete what somebody wrote — so the
            // existing value simply stays as it is and the screen reports on it instead.
            val existing = (1..600).joinToString(", ") { "Fachbegriff$it" }
            val report = CustomWordList.plan(existing, "NeuesWort")
            report.accepted shouldContainExactly emptyList()
            report.didNotFit shouldBe 1
            report.merged shouldBe null
            report.tokensBefore shouldBeGreaterThan CustomWordList.TOKEN_BUDGET
        }

        test("the file's own entries survive in fromFile even when they did not fit") {
            val file = (1..600).joinToString("\n") { "Fachbegriff$it" }
            // Everything readable is offered to the typing dictionary, which has no token budget.
            CustomWordList.plan("", file).fromFile.size shouldBe 600
        }
    }

    context("export round-trips through import") {
        test("a written file reads back as the same list") {
            val words = listOf("Acme", "Deutsche Bahn AG", "e.V.", "Renée", "北京大学")
            val text = CustomWordList.toFileText(words)
            text shouldContain "Deutsche Bahn AG\n"
            CustomWordList.parseFile(text).words shouldContainExactly words
        }

        test("an empty list writes an empty file rather than a blank line") {
            CustomWordList.toFileText(emptyList()) shouldBe ""
        }
    }
})
