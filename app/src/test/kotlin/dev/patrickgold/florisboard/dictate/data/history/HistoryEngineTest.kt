/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.history

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryEngineTest {

    private val cloud = HistoryEngine("openai", "OpenAI", "gpt-transcribe")
    private val phone = HistoryEngine("local", "On-device (offline)", "whisper-base")

    @Test
    fun aDictationTheFallbackTookWhollyIsThePhonesOwn() {
        // The row names the model that wrote the text, not the provider whose request failed (#457).
        assertEquals(phone, cloud.fellBackTo(phone))
    }

    @Test
    fun aDictationTheFallbackTookPartlyNamesBothProviderFirst() {
        val mixed = cloud.fellBackTo(phone, partly = true)
        assertEquals("OpenAI + On-device (offline)", mixed.providerName)
        assertEquals("gpt-transcribe + whisper-base", mixed.model)
        // Still grouped under the provider the dictation was sent to.
        assertEquals("openai", mixed.providerId)
    }

    @Test
    fun aModelBothProvidersRunIsNamedOnce() {
        // A home server and a cloud fallback can serve the same model under the same name (#455).
        val home = HistoryEngine("custom:home", "Home server", "whisper-1")
        val cloud = HistoryEngine("custom:cloud", "Cloud", "whisper-1")
        val mixed = home.fellBackTo(cloud, partly = true)
        assertEquals("Home server + Cloud", mixed.providerName)
        assertEquals("whisper-1", mixed.model)
    }
}
