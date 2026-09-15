/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.mappings

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class DictateMappingsTextTest : FunSpec({
    context("parse reads the tab format and the shapes a person actually writes") {
        test("four columns carry both flags") {
            val parsed = DictateMappingsText.parse("github\tGitHub\tany\tcase").mappings
            parsed shouldContainExactly listOf(
                DictateMappings.Mapping(from = "github", to = "GitHub", matchCase = true, wholeWord = false),
            )
        }

        test("two columns fall back to the editor's own defaults") {
            DictateMappingsText.parse("teh\tthe").mappings shouldContainExactly listOf(
                DictateMappings.Mapping(from = "teh", to = "the", matchCase = false, wholeWord = true),
            )
        }

        test("an empty replacement survives, because deleting a word is a real rule") {
            val parsed = DictateMappingsText.parse("Untertitel von Stephanie Geiges\t\tany\tnocase").mappings
            parsed.single().to shouldBe ""
            parsed.single().wholeWord.shouldBeFalse()
        }

        test("arrows and commas stand in for the tab") {
            DictateMappingsText.parse("github → GitHub\nteh -> the\nfoo => bar\nbaz, qux").mappings
                .map { it.from to it.to } shouldContainExactly
                listOf("github" to "GitHub", "teh" to "the", "foo" to "bar", "baz" to "qux")
        }

        test("comments, blank lines and a line naming no replacement") {
            val parse = DictateMappingsText.parse("# from\tto\n\ngithub\tGitHub\nnonsense")
            parse.mappings.single().from shouldBe "github"
            parse.unusable shouldBe 1
        }
    }

    context("format writes what parse reads back") {
        test("a round trip keeps every field, flags included") {
            val items = listOf(
                DictateMappings.Mapping(from = "github", to = "GitHub", matchCase = true, wholeWord = false),
                DictateMappings.Mapping(from = "teh", to = "the"),
                DictateMappings.Mapping(from = "Stephanie Geiges", to = ""),
            )
            DictateMappingsText.parse(DictateMappingsText.format(items)).mappings shouldContainExactly items
        }

        test("the header line is a comment, so it does not read back as a rule") {
            DictateMappingsText.parse(DictateMappingsText.format(emptyList())).mappings shouldContainExactly
                emptyList()
        }
    }

    context("plan merges and never lets two rules fight over one word") {
        test("new rules are appended behind the existing ones") {
            val existing = DictateMappings(items = listOf(DictateMappings.Mapping("teh", "the")))
            val report = DictateMappingsText.plan(existing, "github\tGitHub")
            report.added.single().from shouldBe "github"
            report.merged.items.map { it.from } shouldContainExactly listOf("teh", "github")
        }

        test("a rule whose `from` is already taken is skipped, flags and all") {
            val existing = DictateMappings(items = listOf(DictateMappings.Mapping("teh", "the")))
            val report = DictateMappingsText.plan(existing, "TEH\tthee\tany\tcase")
            report.alreadyKnown shouldBe 1
            report.hasSomethingToAdd.shouldBeFalse()
            report.merged.items shouldContainExactly existing.items
        }
    }
})
