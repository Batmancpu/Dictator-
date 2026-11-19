/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.recognition

import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.patrickgold.florisboard.FlorisApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Makes Dictate a system-wide speech recognizer via the standard Android [RecognitionService] API
 * (issue #67) — the same mechanism FUTO Voice Input uses. Once selected as the device's recognition
 * service, any app/keyboard that speaks the standard voice-input protocol can dictate through Dictate's
 * engine (local sherpa/Whisper or a cloud provider) and insert the result itself.
 *
 * All the actual work (recording, transcription, endpointing) lives in the shared [RecognitionSession];
 * this class only bridges its [RecognitionSession.Host] callbacks to the caller's [Callback].
 *
 * Note: some keyboards (notably Gboard) hardwire their mic to Google and won't use a third-party
 * recognizer — this works with apps/keyboards that honour the system default recognition service. The
 * `ACTION_RECOGNIZE_SPEECH` popup path is handled separately by [RecognitionActivity].
 */
class DictateRecognitionService : RecognitionService() {

    private var session: RecognitionSession? = null
    // Dispatch, do not run inline: pendingStart must be assigned before the coroutine completes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingStart: Job? = null
    private var pendingAudio: ParcelFileDescriptor? = null

    /**
     * Android 15 binds a third-party recognition service with nothing but BIND_AUTO_CREATE (AOSP "Restrict
     * RecognitionService process capabilities"; Android 14 still passed the caller's capabilities on). Unless
     * something else keeps Dictate's process in the foreground — the floating button's accessibility service
     * does — it may not use the microphone, and the framework's own RECORD_AUDIO check after this method
     * answers the caller with ERROR_INSUFFICIENT_PERMISSIONS (#489, reproduced on an Android 15 emulator).
     *
     * Deliberately not done: making the caller's attribution context (`createContext` with
     * `setNextAttributionSource`) and recording under it. The framework then skips its check, but the audio
     * policy still silences the capture by Dictate's own process state, so the caller got a silent recording
     * and "no speech" instead of an honest permission error (measured on the same emulator).
     */
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        cancelPendingStart()
        session?.cancel()
        val externalAudio: ParcelFileDescriptor? = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            recognizerIntent.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE)
        ) {
            val source = recognizerIntent.getParcelableExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE, ParcelFileDescriptor::class.java,
            )
            val channels = recognizerIntent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            val encoding = recognizerIntent.getIntExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT,
            )
            val rate = recognizerIntent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            // Explicitly reject unsupported PCM formats. Silently interpreting another rate as 16 kHz
            // produces pitched/garbled speech that looks like a broken transcription provider.
            if (source == null || channels != 1 || encoding != AudioFormat.ENCODING_PCM_16BIT || rate != 16_000) {
                runCatching { source?.close() }
                session = null
                runCatching { listener.error(SpeechRecognizer.ERROR_AUDIO) }
                return
            }
            source
        } else null
        // An Android 15 keyboard can bind this service while the brrSpeak process is cold.
        // FlorisApplication initializes preferences asynchronously: starting transcription before
        // that completes can fail before the PCM receiver ever becomes ready. Keep the descriptor
        // alive while we wait, then start the existing recognition path as normal.
        val app = application as FlorisApplication
        if (externalAudio != null && !app.preferenceStoreLoaded.value) {
            pendingAudio = externalAudio
            pendingStart = scope.launch {
                val ready = withTimeoutOrNull(PREFS_START_TIMEOUT_MS) {
                    app.preferenceStoreLoaded.first { it }
                } != null
                if (!isActive) return@launch
                pendingStart = null
                pendingAudio = null
                if (ready) {
                    startSession(listener, externalAudio)
                } else {
                    runCatching { externalAudio.close() }
                    runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
                }
            }
        } else {
            startSession(listener, externalAudio)
        }
    }

    override fun onStopListening(listener: Callback) {
        if (pendingStart != null) {
            cancelPendingStart()
            runCatching { listener.error(SpeechRecognizer.ERROR_NO_MATCH) }
        } else {
            session?.stop()
        }
    }

    override fun onCancel(listener: Callback) {
        cancelPendingStart()
        session?.cancel()
        session = null
    }

    override fun onDestroy() {
        cancelPendingStart()
        session?.cancel()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun cancelPendingStart() {
        pendingStart?.cancel()
        pendingStart = null
        runCatching { pendingAudio?.close() }
        pendingAudio = null
    }

    private fun startSession(listener: Callback, source: ParcelFileDescriptor?) {
        try {
            session = RecognitionSession(applicationContext, hostFor(listener), externalAudio = source)
                .also { it.start() }
        } catch (t: Throwable) {
            runCatching { source?.close() }
            session = null
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
        }
    }

    private fun hostFor(listener: Callback) = object : RecognitionSession.Host {
        override fun onReadyForSpeech() {
            runCatching { listener.readyForSpeech(Bundle()) }
        }

        override fun onBeginningOfSpeech() {
            runCatching { listener.beginningOfSpeech() }
        }

        override fun onEndOfSpeech() {
            runCatching { listener.endOfSpeech() }
        }

        override fun onRmsChanged(rmsdB: Float) {
            runCatching { listener.rmsChanged(rmsdB) }
        }

        override fun onPartial(text: String) {
            runCatching { listener.partialResults(resultsBundle(text)) }
        }

        override fun onResults(text: String) {
            runCatching { listener.results(resultsBundle(text)) }
            session = null
        }

        override fun onError(code: Int) {
            runCatching { listener.error(code) }
            session = null
        }
    }

    private companion object {
        private const val PREFS_START_TIMEOUT_MS = 15_000L

        private fun resultsBundle(text: String): Bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        }
    }
}
