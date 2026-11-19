/*
 * Copyright (C) 2026 brrSpeak contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.recognition

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A test-only, separate-process microphone source for EXTRA_AUDIO_SOURCE.
 * This is intentionally not the final keyboard UI. The two taps here test whether a
 * foreground caller can send its PCM audio to the background Dictate RecognitionService
 * without that service ever opening its own microphone (Android 15 issue #489).
 */
class AudioSourceProbeActivity : Activity() {

    private enum class Phase { IDLE, CONNECTING, RECORDING, TRANSCRIBING }

    private lateinit var status: TextView
    private lateinit var button: Button
    private var phase = Phase.IDLE
    private var recognizer: SpeechRecognizer? = null
    private var pipeRead: ParcelFileDescriptor? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    @Volatile private var recorder: AudioRecord? = null
    private val captureWanted = AtomicBoolean(false)
    private val bytesSent = AtomicLong(0)
    private val nonZeroFrames = AtomicLong(0)
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    @Volatile private var sessionToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (36 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        root.addView(TextView(this).apply {
            text = "brrSpeak · EXTRA_AUDIO_SOURCE experiment"
            textSize = 21f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply {
            text = "Configure a transcription provider in the main brrSpeak app first. " +
                "Then tap Start, speak, and tap Stop. Audio is captured here and passed to " +
                "the recognition service through a PCM pipe."
            textSize = 16f
            setPadding(0, (24 * dp).toInt(), 0, (18 * dp).toInt())
        }
        root.addView(status)
        button = Button(this).apply {
            text = "Start audio test"
            setOnClickListener {
                when (phase) {
                    Phase.IDLE -> requestMicAndStart()
                    Phase.RECORDING -> stopSending()
                    Phase.CONNECTING, Phase.TRANSCRIBING -> finish("Audio-source test cancelled.")
                }
            }
        }
        root.addView(button)
        setContentView(root)
    }

    private fun requestMicAndStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            status.text = "This test requires Android 13 or newer (API 33)."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            return
        }
        startListeningWithPipe()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startListeningWithPipe()
            } else {
                status.text = "Microphone permission denied to the test sender."
            }
        }
    }

    private fun startListeningWithPipe() {
        releaseSession()
        val token = sessionToken
        bytesSent.set(0)
        nonZeroFrames.set(0)
        phase = Phase.CONNECTING
        button.text = "Cancel test"
        button.isEnabled = true
        status.text = "Binding to brrSpeak recognition service; waiting for ready…"
        scheduleTimeout(Phase.CONNECTING, READY_TIMEOUT_MS,
            "Recognition service did not become ready within 25 seconds.")
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            pipeRead = pipe[0]
            pipeWrite = pipe[1]

            val target = ComponentName(this, DictateRecognitionService::class.java)
            val engine = SpeechRecognizer.createSpeechRecognizer(this, target)
            recognizer = engine
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (sessionToken == token) startMicSender(token)
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (sessionToken == token && phase == Phase.TRANSCRIBING) {
                        status.text = "Audio sent. Transcribing…"
                    }
                }
                override fun onError(error: Int) {
                    if (sessionToken == token) {
                        finish("Recognition failed: Android SpeechRecognizer error $error. " +
                            "(9 = insufficient permissions; 3 = audio; 5 = client; 7 = no match.)")
                    }
                }
                override fun onResults(results: Bundle?) {
                    if (sessionToken != token) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    finish(if (text.isBlank()) "No text returned." else "Recognized text:\n$text")
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipeRead)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            }
            engine.startListening(intent)
        } catch (t: Throwable) {
            finish("Unable to start recognizer/pipe: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun startMicSender(token: Int) {
        if (sessionToken != token || phase != Phase.CONNECTING) return
        val writer = pipeWrite ?: return finish("Audio pipe was closed before recognition became ready.")
        captureWanted.set(true)
        Thread({
            var mic: AudioRecord? = null
            try {
                if (sessionToken != token || !captureWanted.get()) return@Thread
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuffer <= 0) throw IOException("No supported 16 kHz mono AudioRecord")
                val size = maxOf(minBuffer * 2, 4096)
                mic = AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size,
                )
                if (mic.state != AudioRecord.STATE_INITIALIZED) throw IOException("Microphone not initialized")
                recorder = mic
                if (sessionToken != token || !captureWanted.get()) return@Thread
                mic.startRecording()
                if (mic.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IOException("Microphone did not start recording")
                }
                runOnUiThread {
                    if (sessionToken == token && phase == Phase.CONNECTING) {
                        clearTimeout()
                        phase = Phase.RECORDING
                        button.text = "Stop & transcribe"
                        button.isEnabled = true
                        status.text = "Recording in sender process (16 kHz PCM16). Speak now; " +
                            "tap Stop to close the audio pipe."
                    }
                }

                ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
                    val buffer = ByteArray(size)
                    while (sessionToken == token && captureWanted.get()) {
                        val read = mic.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (sessionToken != token) break
                        if (read < 0) {
                            if (!captureWanted.get()) break // stop() interrupted the blocking read.
                            throw IOException("AudioRecord.read failed: $read")
                        }
                        if (read > 0) {
                            // Keep the last completed frame even if the user tapped Stop during read.
                            output.write(buffer, 0, read)
                            bytesSent.addAndGet(read.toLong())
                            if ((0 until read).any { buffer[it] != 0.toByte() }) {
                                nonZeroFrames.incrementAndGet()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (sessionToken == token && captureWanted.getAndSet(false)) {
                    runOnUiThread {
                        if (sessionToken == token && (phase == Phase.CONNECTING || phase == Phase.RECORDING)) {
                            finish("Audio capture/pipe failed: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
            } finally {
                runCatching { writer.close() } // EOF triggers recognition/transcription on the service side.
                runCatching { mic?.stop() }
                runCatching { mic?.release() }
                if (recorder === mic) recorder = null
            }
        }, "brrSpeak-probe-microphone").apply { isDaemon = true }.start()
    }

    private fun stopSending() {
        if (phase != Phase.RECORDING) return
        phase = Phase.TRANSCRIBING
        button.text = "Cancel test"
        button.isEnabled = true
        status.text = "Stopping local microphone, closing the PCM pipe, then transcribing…"
        scheduleTimeout(Phase.TRANSCRIBING, RESULT_TIMEOUT_MS,
            "No recognition result arrived within two minutes.")
        captureWanted.set(false)
        runCatching { recorder?.stop() } // Wake the blocking read so the writer closes promptly.
        // Do not call stopListening here: the receiver must first consume every queued PCM byte.
    }

    private fun finish(message: String) {
        if (phase == Phase.IDLE) return
        phase = Phase.IDLE
        releaseSession()
        button.text = "Start audio test"
        button.isEnabled = true
        val durationMs = bytesSent.get() * 1_000L / (SAMPLE_RATE * 2)
        val diagnostic = when {
            bytesSent.get() == 0L -> " No microphone PCM reached the pipe."
            nonZeroFrames.get() == 0L -> " All transmitted PCM samples were zero (muted source)."
            else -> " Nonzero audio was captured."
        }
        status.text = "$message\n\nPCM bytes sent: ${bytesSent.get()} (~${durationMs} ms).$diagnostic"
    }

    private fun releaseSession() {
        sessionToken++ // Ignore late SpeechRecognizer and worker callbacks from a previous run.
        clearTimeout()
        captureWanted.set(false)
        runCatching { recorder?.stop() }
        runCatching { pipeWrite?.close() }
        runCatching { pipeRead?.close() }
        pipeWrite = null
        pipeRead = null
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun scheduleTimeout(phaseToWatch: Phase, timeoutMs: Long, message: String) {
        clearTimeout()
        val token = sessionToken
        val task = Runnable {
            if (sessionToken == token && phase == phaseToWatch) finish(message)
        }
        timeoutRunnable = task
        handler.postDelayed(task, timeoutMs)
    }

    private fun clearTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    override fun onDestroy() {
        phase = Phase.IDLE
        releaseSession()
        super.onDestroy()
    }

    private companion object {
        const val MIC_REQUEST = 7001
        const val SAMPLE_RATE = 16_000
        const val READY_TIMEOUT_MS = 25_000L
        const val RESULT_TIMEOUT_MS = 120_000L
    }
}
