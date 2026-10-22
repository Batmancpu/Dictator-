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

import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptionFallbackTest {

    private val local = ProviderRegistry.LOCAL.id
    private val home = "custom:home"
    private val setUp = listOf(local, "openai", home)

    private val refused = DictateApiException.fromIo(ConnectException("Failed to connect to /192.168.1.20:8000"))
    private val readTimeout = DictateApiException.fromIo(SocketTimeoutException("timeout"))
    private val badGateway = DictateApiException.fromHttp(502, "Bad gateway")

    @Test
    fun theStoredChoiceAppliesWhileItIsSetUpAndNotTheActiveProvider() {
        assertEquals("openai", TranscriptionFallback.effectiveId("openai", home, setUp))
        assertEquals(local, TranscriptionFallback.effectiveId(local, home, setUp))
    }

    @Test
    fun nothingAppliesWhenOffOrNoLongerSetUp() {
        assertNull(TranscriptionFallback.effectiveId(TranscriptionFallback.OFF, home, setUp))
        // The key was removed: the choice stays stored but stands down.
        assertNull(TranscriptionFallback.effectiveId("groq", home, setUp))
    }

    @Test
    fun aProviderNeverFallsBackOntoItself() {
        assertNull(TranscriptionFallback.effectiveId("openai", "openai", setUp))
    }

    @Test
    fun theOnDeviceEngineHasNothingToFallBackFrom() {
        assertNull(TranscriptionFallback.effectiveId("openai", local, setUp))
    }

    @Test
    fun anotherProviderTakesOverOnlyFromAServerThatWasNeverReached() {
        assertTrue(TranscriptionFallback.takesOver("openai", refused))
        // Reached, and then slow or failing: that provider has the audio.
        assertFalse(TranscriptionFallback.takesOver("openai", readTimeout))
        assertFalse(TranscriptionFallback.takesOver("openai", badGateway))
    }

    @Test
    fun theOnDeviceEngineTakesOverOnAnyLostConnectionAsBefore() {
        assertTrue(TranscriptionFallback.takesOver(local, refused))
        assertTrue(TranscriptionFallback.takesOver(local, readTimeout))
        assertFalse(TranscriptionFallback.takesOver(local, badGateway))
    }
}
