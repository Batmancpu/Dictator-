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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class PromptRulesTest : FunSpec({

    test("a snippet is the text between its brackets, as written") {
        snippetBodyOf("[Best regards,]") shouldBe "Best regards,"
        snippetBodyOf("[\nBest regards,\nJannis]") shouldBe "\nBest regards,\nJannis"
        snippetBodyOf("[]") shouldBe ""
        snippetBodyOf("Fix the grammar [if any]").shouldBeNull()
        snippetBodyOf("[").shouldBeNull()
        snippetBodyOf(null).shouldBeNull()
    }

    test("an automatic prompt works on the dictation even when it was saved with the switch off") {
        // The shape of the e-mail report: an automatic prompt without "requires selection" got only its
        // own instruction, and the model's "please send me the text" replaced every dictation.
        promptRequiresSelection("Rewrite the dictated text.", requiresSelection = false, autoApply = true) shouldBe true
        promptRequiresSelection("Rewrite the dictated text.", requiresSelection = true, autoApply = true) shouldBe true
    }

    test("a prompt that is only tapped keeps what was chosen for it") {
        promptRequiresSelection("Share a fun fact.", requiresSelection = false, autoApply = false) shouldBe false
        promptRequiresSelection("Make it formal.", requiresSelection = true, autoApply = false) shouldBe true
    }

    test("a snippet never works on text, automatic or not") {
        promptRequiresSelection("[Best regards,]", requiresSelection = true, autoApply = true) shouldBe false
        promptRequiresSelection("[Best regards,]", requiresSelection = true, autoApply = false) shouldBe false
    }
})
