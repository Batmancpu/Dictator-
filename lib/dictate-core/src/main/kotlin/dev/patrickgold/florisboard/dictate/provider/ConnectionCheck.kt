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

/**
 * What a connection check actually established — which is the whole of issue #384.
 *
 * The old test counted whatever [OpenAiCompatibleClient.listModels] returned and called any number a
 * success. For the three providers that ship a curated catalog that number is a constant compiled into
 * the app, so Azure reported "Connected · 2 models" against a hostname that does not exist, offline, with
 * a key nobody had checked. A result that cannot fail is not a test.
 *
 * So the verdict now names its own scope, and the scopes are strictly ordered: reaching a server says
 * nothing about a key, and a key says nothing about whether the selected model will transcribe. Only the
 * step that was actually performed may be claimed.
 */
enum class ConnectionCheckScope {
    /**
     * The endpoint answered — and there was no key to check, because the account has none. True for a
     * local Ollama or a self-hosted server of one's own; saying "credentials verified" there would be
     * the same overclaim in the other direction.
     */
    ENDPOINT,

    /** An authenticated request was accepted, so the key and the endpoint belong together. */
    CREDENTIALS,

    /**
     * The provider's own audio endpoint took a sample and answered with a transcript. The only scope
     * that proves dictation itself works: the key, the endpoint, the selected model, the region and the
     * account's quota all had to hold for it.
     */
    TRANSCRIPTION,
}

/**
 * The outcome of a successful check. A failure is a [DictateApiException] instead — it already carries
 * the kind the UI needs to say what to do next.
 *
 * @param liveModelCount how many models the provider's **own live catalog** returned, or null when the
 *   catalog is bundled with the app and no number was learned from the network. Null is the honest
 *   answer for Azure, ElevenLabs and AssemblyAI, and printing the curated list's length there is exactly
 *   the bug this type exists to stop.
 * @param transcript what the sample came back as, for [ConnectionCheckScope.TRANSCRIPTION] only. May be
 *   empty: a provider that accepts the request and returns no text has proved the connection but not
 *   that this model understood the sample, and the UI says so rather than claiming a clean pass.
 */
data class ConnectionCheck(
    val scope: ConnectionCheckScope,
    val liveModelCount: Int? = null,
    val transcript: String? = null,
)
