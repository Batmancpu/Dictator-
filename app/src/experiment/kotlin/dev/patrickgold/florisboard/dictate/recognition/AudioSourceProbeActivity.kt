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

/**
 * A test-only, separate-process microphone source for EXTRA_AUDIO_SOURCE.
 * This is intentionally not the final keyboard UI. The two taps here prove whether a
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
                    else -> Unit
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
        phase = Phase.CONNECTING
        button.isEnabled = false
        status.text = "Binding to brrSpeak recognition service; waiting for ready…"
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            pipeRead = pipe[0]
            pipeWrite = pipe[1]

            val target = ComponentName(this, DictateRecognitionService::class.java)
            val engine = SpeechRecognizer.createSpeechRecognizer(this, target)
            recognizer = engine
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = startMicSender()
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (phase == Phase.TRANSCRIBING) status.text = "Audio sent. Transcribing…"
                }
                override fun onError(error: Int) {
                    finish("Recognition failed: Android SpeechRecognizer error $error. " +
                        "(9 = insufficient permissions; 3 = audio; 5 = client; 7 = no match.)")
                }
                override fun onResults(results: Bundle?) {
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

    private fun startMicSender() {
        if (phase != Phase.CONNECTING) return
        val writer = pipeWrite ?: return finish("Audio pipe was closed before recognition became ready.")
        captureWanted.set(true)
        Thread({
            var mic: AudioRecord? = null
            try {
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
                mic.startRecording()
                if (mic.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IOException("Microphone did not start recording")
                }
                runOnUiThread {
                    if (phase == Phase.CONNECTING) {
                        phase = Phase.RECORDING
                        button.text = "Stop & transcribe"
                        button.isEnabled = true
                        status.text = "Recording in sender process (16 kHz PCM16). Speak now; " +
                            "tap Stop to close the audio pipe."
                    }
                }

                ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
                    val buffer = ByteArray(size)
                    while (captureWanted.get()) {
                        val read = mic.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (!captureWanted.get()) break
                        if (read < 0) throw IOException("AudioRecord.read failed: $read")
                        if (read > 0) output.write(buffer, 0, read)
                    }
                }
            } catch (t: Throwable) {
                if (captureWanted.getAndSet(false)) {
                    runOnUiThread {
                        if (phase == Phase.CONNECTING || phase == Phase.RECORDING) {
                            finish("Audio capture/pipe failed: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
            } finally {
                runCatching { writer.close() } // EOF triggers recognition/transcription on the service side.
                runCatching { mic?.stop() }
                runCatching { mic?.release() }
                recorder = null
            }
        }, "brrSpeak-probe-microphone").apply { isDaemon = true }.start()
    }

    private fun stopSending() {
        if (phase != Phase.RECORDING) return
        phase = Phase.TRANSCRIBING
        button.isEnabled = false
        status.text = "Stopping local microphone, closing the PCM pipe, then transcribing…"
        captureWanted.set(false)
        runCatching { recorder?.stop() } // Wake the blocking read so the writer closes promptly.
        // Do not call stopListening here: the receiver must first consume every queued PCM byte.
    }

    private fun finish(message: String) {
        releaseSession()
        phase = Phase.IDLE
        button.text = "Start audio test"
        button.isEnabled = true
        status.text = message
    }

    private fun releaseSession() {
        captureWanted.set(false)
        runCatching { recorder?.stop() }
        runCatching { pipeWrite?.close() }
        runCatching { pipeRead?.close() }
        pipeWrite = null
        pipeRead = null
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    override fun onDestroy() {
        releaseSession()
        super.onDestroy()
    }

    private companion object {
        const val MIC_REQUEST = 7001
        const val SAMPLE_RATE = 16_000
    }
}
