/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.wear

import android.os.PowerManager
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.audio.SpeechGate
import dev.patrickgold.florisboard.dictate.provider.DictateApiException
import dev.patrickgold.florisboard.dictate.sync.DictateWearProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Phone-side endpoint of the Wear OS Data Layer (#106).
 *
 * Handles requests coming from the watch:
 *  - [DictateWearProtocol.PATH_SYNC_REQUEST]: publish a fresh settings snapshot the watch can cache.
 *  - [DictateWearProtocol.PATH_SET_STANDALONE]: store the standalone opt-in and re-publish settings
 *    (the API key is only included while standalone is on).
 *  - [DictateWearProtocol.PATH_TRANSCRIBE_REQUEST] (ChannelClient): receive recorded audio, transcribe
 *    it with the phone's active provider and send the transcript back.
 *
 * The phone advertises the [DictateWearProtocol.CAPABILITY_PHONE_APP] capability (res/values/wear.xml)
 * so the watch's CapabilityClient can discover it.
 */
class DictateWearService : WearableListenerService() {

    private val prefs by FlorisPreferenceStore

    override fun onDestroy() {
        super.onDestroy()
        // NOTE: deliberately does not cancel [tetherScope]. A WearableListenerService is torn down by the
        // system as soon as it looks idle — and onChannelOpened() returns immediately while the upload +
        // provider call keep running. Cancelling here killed transcriptions mid-flight, so the watch never
        // got an answer and sat in "Transcribing…" until its timeout (#218).
    }

    override fun onMessageReceived(event: MessageEvent) {
        DictateWearProtocol.cancelledIdOf(event.path)?.let { requestId ->
            callOff(event.sourceNodeId, requestId)
            return
        }
        when (event.path) {
            DictateWearProtocol.PATH_SYNC_REQUEST -> tetherScope.launch { publishSettings() }
            DictateWearProtocol.PATH_SET_STANDALONE -> {
                val enabled = event.data.firstOrNull() == 1.toByte()
                tetherScope.launch {
                    prefs.dictate.wearStandaloneEnabled.set(enabled)
                    publishSettings()
                }
            }
            DictateWearProtocol.PATH_SET_AUTO_REWORDING -> {
                val enabled = event.data.firstOrNull() == 1.toByte()
                tetherScope.launch {
                    prefs.dictate.wearAutoRewordingEnabled.set(enabled)
                    publishSettings()
                }
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        // "" is a watch on protocol 0, which names no request and can neither be told progress nor cancel.
        val requestId = DictateWearProtocol.requestIdOf(channel.path) ?: return
        val key = "${channel.nodeId}/$requestId"
        if (requestId.isNotEmpty() && calledOffEarly.remove(key)) {
            Log.i(TAG, "tether: request $requestId was called off before it arrived")
            runCatching { Wearable.getChannelClient(applicationContext).close(channel) }
            return
        }
        // Now, while Play services is still bound to us: once this callback returns, Android freezes the
        // process and the request stalls halfway (#363). See WearTetherService.
        WearTetherService.hold(applicationContext)
        val job = tetherScope.launch { handleTranscribeChannel(channel, requestId) }
        job.invokeOnCompletion { WearTetherService.release() }
        if (requestId.isNotEmpty()) {
            runningRequests[key] = job
            job.invokeOnCompletion { runningRequests.remove(key, job) }
        }
    }

    /**
     * The watch no longer wants the answer (#363): its user pressed cancel, or it stopped hearing from us.
     * Stops the provider call, so a dictation nobody will read is not paid for to the end.
     */
    private fun callOff(nodeId: String, requestId: String) {
        val key = "$nodeId/$requestId"
        val job = runningRequests.remove(key)
        if (job != null) {
            Log.i(TAG, "tether: request $requestId called off by the watch")
            job.cancel()
        } else {
            // A message can overtake the channel it is about, so remember the id for when the audio comes.
            // Ids of requests that already finished end up here too; they are few and never match again.
            if (calledOffEarly.size >= MAX_EARLY_CANCELS) calledOffEarly.clear()
            calledOffEarly.add(key)
        }
    }

    private suspend fun handleTranscribeChannel(channel: ChannelClient.Channel, requestId: String) {
        // Hold the CPU for the upload + provider call. Without this the phone can doze off mid-request
        // (the screen is usually off while dictating from the watch) and the watch waits for nothing.
        val wakeLock = runCatching {
            (applicationContext.getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dictate:wear-tether")
                .apply { acquire(WAKELOCK_TIMEOUT_MS) }
        }.getOrNull()
        try {
            coroutineScope {
                // Protocol 1 (#363): a sign of life every few seconds. The watch used to wait a fixed two
                // minutes without knowing whether anyone was still working on its audio; now it gives up
                // only when these stop, and a slow provider is never cut off while it is still answering.
                val stage = AtomicInteger(DictateWearProtocol.STAGE_TRANSCRIBING.toInt())
                val heartbeat = if (requestId.isEmpty()) null else launch {
                    while (true) {
                        sendProgress(channel.nodeId, requestId, stage.get().toByte())
                        delay(PROGRESS_INTERVAL_MS)
                    }
                }
                try {
                    transcribeForWatch(channel, requestId) {
                        stage.set(DictateWearProtocol.STAGE_REWORDING.toInt())
                        if (heartbeat != null) launch { sendProgress(channel.nodeId, requestId, DictateWearProtocol.STAGE_REWORDING) }
                    }
                } finally {
                    heartbeat?.cancel()
                }
            }
        } finally {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        }
    }

    private suspend fun sendProgress(nodeId: String, requestId: String, stage: Byte) {
        runCatching {
            Wearable.getMessageClient(applicationContext)
                .sendMessage(nodeId, DictateWearProtocol.progressPath(requestId), byteArrayOf(stage))
                .await()
        }
    }

    private suspend fun transcribeForWatch(
        channel: ChannelClient.Channel,
        requestId: String,
        onRewording: () -> Unit,
    ) {
        val channelClient = Wearable.getChannelClient(applicationContext)
        // One file per request: a called-off request can still be draining while the next one arrives.
        val audio = File(cacheDir, "wear_tether_${channel.nodeId}${if (requestId.isEmpty()) "" else "_$requestId"}.wav")
        var transcript = ""
        // Human-readable detail for failures, forwarded to the watch so the user sees the actual provider
        // error ("model not found", "insufficient quota", …) instead of a blanket "Transcription failed".
        var errorDetail = ""
        // Report the real reason to the watch instead of collapsing every failure into an empty result
        // (which the watch could only blame on the phone key). Defaults to a generic error until we know.
        var status = DictateWearProtocol.RESP_ERROR
        var calledOff = false
        try {
            // Drain the watch's audio into a temp file.
            channelClient.getInputStream(channel).await().use { input ->
                audio.outputStream().use { input.copyTo(it) }
            }
            // The copy blocks and cannot be interrupted; a cancel that came in meanwhile lands here.
            coroutineContext.ensureActive()
            Log.i(TAG, "tether: received ${audio.length()} bytes from ${channel.nodeId}, transcribing…")
            // Communicate the phone's silence gate to the watch (#93): skip the upload for silent clips
            // and tell the watch "no speech" rather than letting the provider echo an empty result.
            if (prefs.dictate.skipSilentRecordings.get() && !SpeechGate.hasSpeech(applicationContext, audio)) {
                status = DictateWearProtocol.RESP_NO_SPEECH
            } else {
                transcript = PhoneTranscriber.transcribe(applicationContext, prefs, audio, onRewording)
                status = if (transcript.isBlank()) {
                    DictateWearProtocol.RESP_NO_SPEECH
                } else {
                    DictateWearProtocol.RESP_OK
                }
            }
            Log.i(TAG, "tether: status=$status, transcript length=${transcript.length}")
        } catch (e: CancellationException) {
            // Called off by the watch (#363). Nobody is waiting for an answer, so none is sent.
            calledOff = true
            throw e
        } catch (e: DictateApiException) {
            Log.e(TAG, "tether: phone transcription failed (${e.kind})", e)
            status = when (e.kind) {
                DictateApiException.Kind.INVALID_API_KEY -> DictateWearProtocol.RESP_BAD_KEY
                DictateApiException.Kind.QUOTA_EXCEEDED -> DictateWearProtocol.RESP_QUOTA
                DictateApiException.Kind.NETWORK,
                DictateApiException.Kind.TIMEOUT,
                DictateApiException.Kind.SERVER_ERROR -> DictateWearProtocol.RESP_OFFLINE
                else -> DictateWearProtocol.RESP_ERROR
            }
            transcript = ""
            errorDetail = e.message.orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "tether: phone transcription failed", e)
            status = DictateWearProtocol.RESP_ERROR
            transcript = ""
            errorDetail = e.message.orEmpty()
        } finally {
            audio.delete()
            runCatching { channelClient.close(channel) }
            // Always answer, even on failure, so the watch never hangs waiting for a reply. Success stays
            // a raw transcript (byte-identical to older builds → a not-yet-updated watch still works); only
            // failures use the status envelope, which older watches simply render as a short error string.
            if (!calledOff) {
                val payload = if (status == DictateWearProtocol.RESP_OK) {
                    transcript.toByteArray(Charsets.UTF_8)
                } else {
                    DictateWearProtocol.encodeTranscribeResponse(status, errorDetail)
                }
                // A named request is answered on its own path, so its answer can never be read as the
                // answer to another dictation; an unnamed one on the shared path an old watch listens on.
                val path = if (requestId.isEmpty()) {
                    DictateWearProtocol.PATH_TRANSCRIBE_RESPONSE
                } else {
                    DictateWearProtocol.responsePath(requestId)
                }
                sendResponseWithRetry(channel.nodeId, path, payload)
            }
        }
    }

    /**
     * Delivers the transcript back to the watch, confirming it actually went out. The send used to be
     * fire-and-forget, so a momentary Bluetooth drop silently threw away a transcript the provider had
     * already produced (and billed) and left the watch waiting for its timeout (#218). Retries a few times
     * with a short backoff, which is enough to ride out a brief connection hiccup.
     */
    private suspend fun sendResponseWithRetry(nodeId: String, path: String, payload: ByteArray) {
        val messageClient = Wearable.getMessageClient(applicationContext)
        repeat(RESPONSE_SEND_ATTEMPTS) { attempt ->
            val sent = runCatching {
                messageClient.sendMessage(nodeId, path, payload).await()
            }
            if (sent.isSuccess) {
                if (attempt > 0) Log.i(TAG, "tether: response delivered on attempt ${attempt + 1}")
                return
            }
            Log.w(TAG, "tether: response send failed (attempt ${attempt + 1})", sent.exceptionOrNull())
            if (attempt < RESPONSE_SEND_ATTEMPTS - 1) delay(RESPONSE_RETRY_DELAY_MS * (attempt + 1))
        }
        Log.e(TAG, "tether: giving up delivering the response to $nodeId")
    }

    /** Serialize the active transcription settings and put them on the Data Layer for the watch. */
    private suspend fun publishSettings() {
        DictateWearPublisher.publish(applicationContext)
    }

    private companion object {
        const val TAG = "DictateWear"

        /**
         * How long the CPU is held for one tethered dictation before the lock times out on its own. Only a
         * safety net: the lock is released as soon as the request ends. Three minutes was shorter than a
         * phone may honestly work on one — a transcription may retry up to four times at the Request
         * timeout (#438), and rewording comes on top — and a phone that dozes off mid-request stops the
         * signs of life the watch now waits on (#363).
         */
        const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L
        const val RESPONSE_SEND_ATTEMPTS = 4
        const val RESPONSE_RETRY_DELAY_MS = 400L

        /** How often a protocol-1 watch hears that its request is still being worked on (#363). */
        const val PROGRESS_INTERVAL_MS = 5_000L
        const val MAX_EARLY_CANCELS = 16

        /** Named requests still at work, by "nodeId/requestId", so a cancel from the watch can stop them. */
        val runningRequests = ConcurrentHashMap<String, Job>()

        /** Cancels that arrived before their request did. */
        val calledOffEarly: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * Process-lifetime scope for tethered work. Deliberately NOT tied to the service instance: the
         * system stops a [WearableListenerService] once its callbacks return, which would otherwise cancel
         * an in-flight transcription and leave the watch hanging (#218).
         */
        val tetherScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
