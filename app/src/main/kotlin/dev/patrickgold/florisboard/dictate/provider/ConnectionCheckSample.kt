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

import android.content.Context
import dev.patrickgold.florisboard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The bundled recording the transcription test sends (issue #384).
 *
 * Two seconds of English saying "This is a Dictate connection test", as 16 kHz mono 16-bit PCM WAV —
 * byte for byte the shape the keyboard's own recorder produces, so the test travels the same road a
 * dictation does, through the same container rules and the same upload path. A provider that takes this
 * and answers takes a real dictation too.
 *
 * Synthesised with espeak-ng rather than recorded, so nothing here is anyone's voice and there is no
 * third-party material in the APK. It sounds like a machine, which does not matter: what is being
 * checked is that a transcript comes back, and the text that comes back is shown to the user as the
 * evidence rather than compared against an expected string. An accent the model mishears still proves
 * the key, the endpoint, the model id, the region and the quota.
 *
 * The language is pinned to English for the same reason a dictation's is not: here the answer is known,
 * and letting a provider detect it would add a way for the test to fail that has nothing to do with the
 * configuration being tested.
 */
object ConnectionCheckSample {
    /** The language actually spoken in the sample; sent as the request's language hint. */
    const val LANGUAGE = "en"

    private const val CACHE_NAME = "dictate_connection_test.wav"

    /**
     * Unpacks the sample into the cache directory and returns it. Rewritten on every call: it is 70 kB
     * and only ever runs when someone taps the button, and copying is cheaper than reasoning about a
     * stale copy left behind by an older version of the app.
     */
    suspend fun file(context: Context): File = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, CACHE_NAME)
        context.resources.openRawResource(R.raw.dictate_connection_test).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target
    }
}
