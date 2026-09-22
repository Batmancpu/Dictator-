/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.ui

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import dev.patrickgold.florisboard.dictate.ui.LegacyEditAction.Companion.MAX_PER_ROW
import dev.patrickgold.florisboard.dictate.ui.LegacyEditAction.Companion.MAX_TOTAL

/** The first [n] actions of the enum — a list of a given length whose contents don't matter. */
private fun some(n: Int) = LegacyEditAction.entries.take(n)

class LegacyEditActionTest : FunSpec({
    context("wrapping onto a second row (#226)") {
        test("everything up to a full row stays on one row") {
            for (n in 1..MAX_PER_ROW) {
                LegacyEditAction.rows(some(n)) shouldBe listOf(some(n))
            }
        }

        test("one button past a full row splits balanced, not filled-then-spilled") {
            val eleven = some(MAX_PER_ROW + 1)
            LegacyEditAction.rows(eleven) shouldBe listOf(eleven.take(6), eleven.drop(6))
        }

        test("an odd count puts the extra button on the first row") {
            LegacyEditAction.rows(some(13)).map { it.size } shouldBe listOf(7, 6)
        }

        test("a full house is two full rows") {
            LegacyEditAction.rows(some(MAX_TOTAL)).map { it.size } shouldBe listOf(MAX_PER_ROW, MAX_PER_ROW)
        }

        test("no row ever exceeds the per-row cap, at any count") {
            for (n in 1..MAX_TOTAL) {
                LegacyEditAction.rows(some(n)).filter { it.size > MAX_PER_ROW } shouldBe emptyList()
            }
        }

        test("the split preserves the order the user arranged") {
            val all = some(MAX_TOTAL)
            LegacyEditAction.rows(all).flatten() shouldBe all
        }

        test("no buttons means no rows at all — the layout skips the whole block") {
            LegacyEditAction.rows(emptyList()) shouldBe emptyList()
        }
    }

    context("reading the pref") {
        test("a value written before #226 loads unchanged") {
            LegacyEditAction.parse("SELECT_ALL,UNDO,REDO,CUT,COPY,PASTE,EMOJI,NUMBERS") shouldBe
                LegacyEditAction.DEFAULT
        }

        test("the shipped default is the default row") {
            LegacyEditAction.parse(LegacyEditAction.serialize(LegacyEditAction.DEFAULT)) shouldBe
                LegacyEditAction.DEFAULT
        }

        test("serialize and parse round-trip") {
            val arrangement = listOf(
                LegacyEditAction.SCAN,
                LegacyEditAction.HISTORY,
                LegacyEditAction.GIF,
                LegacyEditAction.EDITING,
            )
            LegacyEditAction.parse(LegacyEditAction.serialize(arrangement)) shouldBe arrangement
        }

        test("an unknown name is skipped rather than failing the whole row") {
            LegacyEditAction.parse("UNDO,WAKE_ON_LAN,REDO") shouldBe
                listOf(LegacyEditAction.UNDO, LegacyEditAction.REDO)
        }

        test("whitespace around a name is tolerated") {
            LegacyEditAction.parse(" UNDO , REDO ") shouldBe
                listOf(LegacyEditAction.UNDO, LegacyEditAction.REDO)
        }

        test("an empty value falls back to the default row") {
            LegacyEditAction.parse("") shouldBe LegacyEditAction.DEFAULT
        }

        test("a duplicate is kept once, in its first position") {
            LegacyEditAction.parse("UNDO,REDO,UNDO") shouldBe
                listOf(LegacyEditAction.UNDO, LegacyEditAction.REDO)
        }

        test("more buttons than fit are cut to what the layout can draw") {
            val tooMany = LegacyEditAction.entries.joinToString(",") { it.name } + ",UNDO,REDO"
            LegacyEditAction.parse(tooMany).size shouldBe MAX_TOTAL
        }
    }

    // The request was "all the extras visible at once". That only stays true while the palette fits into
    // the two rows — the day a 21st action is added, this fails and the cap has to be re-argued rather
    // than silently leaving one button unplaceable.
    test("every action there is can be placed at once — that was the point of #226") {
        (LegacyEditAction.entries.size <= MAX_TOTAL) shouldBe true
    }
})
