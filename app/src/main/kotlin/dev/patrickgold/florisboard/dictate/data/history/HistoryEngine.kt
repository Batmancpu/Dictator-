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

/**
 * The engine a history entry's text came from: the provider's id, the name the list shows for it, and the
 * model (issue #457).
 *
 * That is not always the configured provider. The offline fallback (#104) and the held stop button (#270)
 * finish a cloud dictation on the phone, and a resend or a re-transcribe from the list runs on whatever
 * provider is active *now*. The row was written before any of that happened, so it has to be renamed
 * once the text exists — otherwise the one place that answers "what transcribed this?" names an engine
 * that never saw the audio.
 */
data class HistoryEngine(
    val providerId: String,
    val providerName: String,
    val model: String,
) {
    /**
     * This engine after a fallback took over from it: the phone's own model (#104) or another provider
     * (#455). When the fallback took the whole dictation the entry is simply [fallback]'s. When it took only
     * [partly] — a long dictation whose connection dropped halfway, some segments answered by the provider
     * and the rest by the fallback — the entry names both, provider first, and keeps the provider's id. A
     * model both of them run is named once.
     */
    fun fellBackTo(fallback: HistoryEngine, partly: Boolean = false): HistoryEngine =
        if (partly) {
            copy(
                providerName = listOf(providerName, fallback.providerName).distinct().joinToString(" + "),
                model = listOf(model, fallback.model).distinct().joinToString(" + "),
            )
        } else {
            fallback
        }
}
