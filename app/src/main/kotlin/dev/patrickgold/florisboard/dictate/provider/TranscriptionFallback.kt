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
 * The provider that takes over a dictation the active transcription provider cannot handle: the on-device
 * engine when the phone is offline (#104), or since #455 any other set-up provider when the active one
 * cannot be reached — a server at home, seen from anywhere else.
 *
 * Answered here rather than in the controller so the rules can be tested without Android.
 */
object TranscriptionFallback {

    /** The stored value for "no fallback". */
    const val OFF = ""

    /**
     * The fallback that actually applies, or null.
     *
     * [stored] is what the user picked. It does not apply while it is the active provider ([activeId]) —
     * picking the fallback as the main provider later must not have it fall back onto itself — or while it
     * is no longer among the set-up providers ([setUpIds]): a deleted key or a removed model. Nor does
     * anything fall back from the on-device engine, which never fails for want of a connection.
     *
     * The choice is kept rather than cleared in those cases, so setting the key up again or switching the
     * main provider back brings it straight back.
     */
    fun effectiveId(stored: String, activeId: String, setUpIds: Collection<String>): String? = stored.takeIf {
        it != OFF && it != activeId && activeId != ProviderRegistry.LOCAL.id && it in setUpIds
    }

    /**
     * Whether [error] from the active provider hands the dictation to [fallbackId].
     *
     * The two kinds of fallback answer different questions. The on-device engine costs nothing and needs no
     * connection, so it takes over whenever the request failed for want of one — offline from the start,
     * dropped halfway, or timed out — as it always has. Another provider only takes over when the active
     * one could not be reached at all ([DictateApiException.isConnectFailure]): one that was reached has
     * the audio, and sending it elsewhere then would pay for the dictation twice and throw away an answer
     * that may still be coming, the "switch after N seconds" that #391 declined.
     */
    fun takesOver(fallbackId: String, error: DictateApiException): Boolean =
        if (fallbackId == ProviderRegistry.LOCAL.id) {
            error.kind == DictateApiException.Kind.NETWORK || error.kind == DictateApiException.Kind.TIMEOUT
        } else {
            error.isConnectFailure
        }
}
