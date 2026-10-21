/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * What goes out as `reasoning_effort` while the user's setting is Off. A preset speaks for its own default
 * model and for nothing else: every other model keeps the provider's default, as Off has always meant.
 */
class ReasoningEffortWhenOffTest : FunSpec({

    val openAi = ProviderRegistry.OPENAI

    test("OpenAI's default model runs without thinking") {
        openAi.defaultChatModel shouldBe "gpt-6-luna"
        openAi.reasoningEffortWhenOff("gpt-6-luna") shouldBe "none"
    }

    test("a model the user picked keeps the provider's default") {
        // gpt-6.1-sol refuses "none" outright (measured 2026-10-16), so this is not just caution.
        openAi.reasoningEffortWhenOff("gpt-6.1-sol") shouldBe null
        openAi.reasoningEffortWhenOff("gpt-4o-mini") shouldBe null
    }

    test("a preset that says nothing sends nothing") {
        ProviderRegistry.OPENROUTER.reasoningEffortWhenOff("openai/gpt-6-luna") shouldBe null
        ProviderRegistry.custom("http://localhost:8080/v1/").reasoningEffortWhenOff("gpt-6-luna") shouldBe null
    }
})
