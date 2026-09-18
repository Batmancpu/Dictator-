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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.io.path.createTempFile

/**
 * Issue #384: a connection test that cannot fail is not a test.
 *
 * The bug was that `listModels()` answered from a list compiled into the app for the three providers
 * without a usable catalog endpoint, so "Connected · 2 models" came back from a hostname that does not
 * exist, offline, with a key nothing had looked at. Every test here therefore asserts two things that
 * the old code could not satisfy at once: that a request actually left the app (`requestCount`), and
 * that the verdict claims no more than that request established.
 */
class ConnectionCheckTest : FunSpec({

    fun clientFor(preset: ProviderPreset, baseUrl: String, apiKey: String) =
        OpenAiCompatibleClient.from(preset, apiKey, baseUrlOverride = baseUrl)

    // --- Azure: the provider the issue was reported against ------------------------------------

    test("Azure asks its own resource before saying anything about the key") {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"values":[]}"""))
            val check = clientFor(ProviderRegistry.AZURE, server.url("/").toString(), "resource-key")
                .checkCredentials()

            val recorded = server.takeRequest()
            recorded.method shouldBe "GET"
            recorded.path shouldBe "/speechtotext/models/base?api-version=2025-10-15"
            recorded.getHeader("Ocp-Apim-Subscription-Key") shouldBe "resource-key"
            check.scope shouldBe ConnectionCheckScope.CREDENTIALS
            // The two curated ids are a bundled list, not an answer from Azure, so no count is claimed.
            check.liveModelCount.shouldBeNull()
            server.requestCount shouldBe 1
        }
    }

    test("a rejected Azure key fails the check instead of counting the bundled ids") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(401)
                    .setBody("""{"error":{"code":"Unauthorized","message":"Access denied."}}"""),
            )
            val e = shouldThrow<DictateApiException> {
                clientFor(ProviderRegistry.AZURE, server.url("/").toString(), "wrong").checkCredentials()
            }

            e.kind shouldBe DictateApiException.Kind.INVALID_API_KEY
            server.requestCount shouldBe 1
        }
    }

    test("an Azure endpoint nobody filled in is refused with the page to go to, before any request") {
        listOf("", "https://<your-resource>.cognitiveservices.azure.com/").forEach { baseUrl ->
            val e = shouldThrow<DictateApiException> {
                clientFor(ProviderRegistry.AZURE, baseUrl, "key").checkCredentials()
            }
            e.kind shouldBe DictateApiException.Kind.INVALID_API_KEY
            e.message.orEmpty() shouldContain "Keys and Endpoint"
        }
    }

    // --- The other two curated-catalog providers ------------------------------------------------

    test("ElevenLabs is checked against its own models endpoint rather than the bundled list") {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[]}"""))
            val check = clientFor(ProviderRegistry.ELEVENLABS, server.url("/v1/").toString(), "el-key")
                .checkCredentials()

            val recorded = server.takeRequest()
            recorded.path shouldBe "/v1/models"
            recorded.getHeader("xi-api-key") shouldBe "el-key"
            check.scope shouldBe ConnectionCheckScope.CREDENTIALS
            check.liveModelCount.shouldBeNull()
            server.requestCount shouldBe 1
        }
    }

    test("AssemblyAI, which publishes no catalog at all, is checked against an authenticated GET") {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"transcripts":[]}"""))
            val check = clientFor(ProviderRegistry.ASSEMBLYAI, server.url("/").toString(), "aai-key")
                .checkCredentials()

            val recorded = server.takeRequest()
            recorded.path shouldBe "/v2/transcript?limit=1"
            recorded.getHeader("authorization") shouldBe "aai-key"
            check.scope shouldBe ConnectionCheckScope.CREDENTIALS
            check.liveModelCount.shouldBeNull()
            server.requestCount shouldBe 1
        }
    }

    test("a rejected AssemblyAI key fails the check") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(401)
                    .setBody("""{"error":"Authentication error, API token missing/invalid"}"""),
            )
            shouldThrow<DictateApiException> {
                clientFor(ProviderRegistry.ASSEMBLYAI, server.url("/").toString(), "wrong").checkCredentials()
            }.kind shouldBe DictateApiException.Kind.INVALID_API_KEY
        }
    }

    // --- A live-catalog provider, where the count is real ---------------------------------------

    test("a live catalog is an authenticated request, so its count may be shown") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"data":[{"id":"gpt-transcribe"},{"id":"gpt-4o"}]}"""),
            )
            val check = clientFor(ProviderRegistry.OPENAI, server.url("/v1/").toString(), "sk-live")
                .checkCredentials()

            val recorded = server.takeRequest()
            recorded.path shouldBe "/v1/models"
            recorded.getHeader("Authorization") shouldBe "Bearer sk-live"
            check.scope shouldBe ConnectionCheckScope.CREDENTIALS
            check.liveModelCount shouldBe 2
        }
    }

    // --- Keyless: reaching a server is not the same as verifying a credential -------------------

    test("a keyless server of one's own reports what it is, and no Authorization goes out") {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"whisper-1"}]}"""))
            val preset = ProviderRegistry.custom(server.url("/v1/").toString())
            val check = clientFor(preset, preset.baseUrl, "").checkCredentials()

            server.takeRequest().getHeader("Authorization").shouldBeNull()
            check.scope shouldBe ConnectionCheckScope.ENDPOINT
            check.liveModelCount shouldBe 1
        }
    }

    // --- The transcription check, which is the only one that proves dictation -------------------

    test("the sample goes through the selected model and the transcript comes back as the evidence") {
        val sample = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-sample".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setResponseCode(200)
                        .setBody("""{"text":"This is a Dictate connection test."}"""),
                )
                val preset = ProviderRegistry.custom(server.url("/v1/").toString())
                val check = clientFor(preset, preset.baseUrl, "sk-live")
                    .checkTranscription(sample, model = "whisper-1", language = "en")

                val body = server.takeRequest().body.readUtf8()
                body shouldContain "whisper-1"
                body shouldContain "RIFF-sample"
                check.scope shouldBe ConnectionCheckScope.TRANSCRIPTION
                check.transcript shouldBe "This is a Dictate connection test."
            }
        } finally {
            sample.delete()
        }
    }

    test("a provider that accepts the sample and answers with nothing reports an empty transcript") {
        val sample = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-sample".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"   "}"""))
                val preset = ProviderRegistry.custom(server.url("/v1/").toString())
                val check = clientFor(preset, preset.baseUrl, "sk-live")
                    .checkTranscription(sample, model = "whisper-1", language = "en")

                // Still a pass of the transcription step — the request was accepted — but the UI has to
                // be able to tell it apart from a transcript, so the emptiness survives to it.
                check.scope shouldBe ConnectionCheckScope.TRANSCRIPTION
                check.transcript shouldBe ""
            }
        } finally {
            sample.delete()
        }
    }
})
