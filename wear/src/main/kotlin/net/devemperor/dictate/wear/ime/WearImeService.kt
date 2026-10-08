/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.devemperor.dictate.wear.ime

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import net.devemperor.dictate.wear.R
import net.devemperor.dictate.wear.audio.WearAudioRecorder
import net.devemperor.dictate.wear.sync.WearSettingsStore
import net.devemperor.dictate.wear.sync.WearSyncClient
import net.devemperor.dictate.wear.transcribe.WearTranscription

/**
 * The Dictate Wear OS keyboard: a lightweight [InputMethodService] hosting a Jetpack Compose UI.
 *
 * This is NOT the FlorisBoard engine — on a watch we want a voice-first input with compact
 * number/emoji fallbacks, so the input view is a small Wear Compose surface.
 *
 * An IME window is not backed by a ComponentActivity, so we implement the ViewTree owners
 * ([LifecycleOwner], [ViewModelStoreOwner], [SavedStateRegistryOwner]) ourselves and attach them
 * to the [ComposeView]; otherwise Compose refuses to compose inside the input view.
 *
 * Transcription wiring (record -> provider/tether -> commit) is deferred to P0/P2 of the Wear
 * roadmap; for now [toggleDictation] flips the visual state so the input surface is testable.
 */
class WearImeService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    private val dictationState = mutableStateOf(WearDictationState.IDLE)
    private val recordingInfo = mutableStateOf(WearRecordingInfo())
    // Short, human-readable reason shown on the voice page when a dictation fails, so problems are
    // diagnosable on the wrist without a logcat round-trip.
    private val errorMessage = mutableStateOf<String?>(null)
    /**
     * Audio of a failed or interrupted dictation, kept so the user can re-send it instead of losing it
     * (#218). State, because the voice page offers to send it again or throw it away.
     */
    private val retainedAudio = mutableStateOf<File?>(null)
    /** A finished dictation the field did not take (#294), kept to insert again without transcribing. */
    private val retainedText = mutableStateOf<String?>(null)
    /** In [WearDictationState.READY]: the text that went in, and the field action the ✓ triggers. */
    private val readyText = mutableStateOf<String?>(null)
    private val readyAction = mutableIntStateOf(EditorInfo.IME_ACTION_DONE)

    /** The transcription in flight, so the user can cancel it (#363). */
    private var transcribeJob: Job? = null
    /**
     * Bumped whenever a transcription starts or is abandoned. A callback or result that arrives for an
     * older one — the phone's "rewording" signal, the text of a cancelled request — is dropped.
     */
    private var dictationGeneration = 0
    /** The generation whose field went away mid-wait: its audio is kept, its text never typed. */
    private var interruptedGeneration = 0

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val recorder by lazy { WearAudioRecorder(applicationContext) }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        // Warm the cached settings synced from the phone so the voice page is ready to transcribe.
        WearSettingsStore.load(applicationContext)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // What kind of field this is decides whether Enter sends and whether a dictation may close the
        // keyboard (#294); logged because "the text never arrived" reports hinge on it.
        attribute?.let {
            Log.i(TAG, "field: ${it.packageName} inputType=0x${Integer.toHexString(it.inputType)} " +
                "imeOptions=0x${Integer.toHexString(it.imeOptions)} restarting=$restarting")
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Opening the keyboard is a good moment to reconcile with the phone: read the latest replicated
        // settings DataItem (accent/provider/key/prompt) and nudge a republish. The cached copy is used
        // meanwhile so input never blocks on the sync.
        scope.launch {
            runCatching { WearSyncClient.requestSettingsSync(applicationContext) }
            runCatching { WearSyncClient.fetchPublishedSettings(applicationContext) }
                .getOrNull()?.let { WearSettingsStore.save(applicationContext, it) }
        }
    }

    override fun onCreateInputView(): View {
        // The input view becomes visible; drive the lifecycle to RESUMED so Compose runs.
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        // Compose resolves its window recomposer from the IME window's decor view (the root), not from
        // our ComposeView — and the IME wraps our view in its own parentPanel. So the ViewTree owners
        // MUST be set on the decor view, otherwise AbstractComposeView.onAttachedToWindow crashes with
        // "ViewTreeLifecycleOwner not found". We set them on both to be safe.
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }

        return ComposeView(this).apply {
            // Opaque host so the input view never lets the app behind show through (uniform background).
            setBackgroundColor(android.graphics.Color.BLACK)
            setViewTreeLifecycleOwner(this@WearImeService)
            setViewTreeViewModelStoreOwner(this@WearImeService)
            setViewTreeSavedStateRegistryOwner(this@WearImeService)
            setContent {
                WearKeyboard(
                    actions = actions,
                    dictationState = dictationState.value,
                    recordingInfo = recordingInfo.value,
                    errorMessage = errorMessage.value,
                    canResend = retainedAudio.value != null || retainedText.value != null,
                    readyText = readyText.value,
                    readyAction = readyAction.intValue,
                    peakProvider = { recorder.maxAmplitude() },
                )
            }
        }
    }

    /**
     * The keyboard is going away while it listens — swiped down, the field left, another app opened. Until
     * #363 the recording simply ran on, microphone open, until the keyboard was next used. Now it stops, and
     * like the phone's keyboard does when it is collapsed mid-recording, the audio is kept and offered on
     * the next open rather than thrown away or typed into whatever field happens to be focused by then.
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        when (dictationState.value) {
            WearDictationState.RECORDING -> interruptRecording()
            // Closed instead of confirmed: the text stays in the field, unsent, as the user chose.
            WearDictationState.READY -> leaveReady()
            else -> Unit
        }
    }

    /**
     * The field itself is gone, so a transcription still in flight has nowhere to land. It is called off
     * (the phone stops paying for it) and its audio kept to send again. A keyboard merely hidden over the
     * same field keeps transcribing, and the text still lands there.
     */
    override fun onFinishInput() {
        super.onFinishInput()
        when (dictationState.value) {
            WearDictationState.TRANSCRIBING, WearDictationState.REWORDING -> interruptTranscription()
            // A ✓ for a field that is gone would act on whatever field comes next.
            WearDictationState.READY -> leaveReady()
            else -> Unit
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        if (recorder.isRecording) recorder.cancel()
        abandonTranscription()
        discardRetained()
        scope.cancel()
    }

    private val actions = WearImeActions(
        commitText = { text -> ic()?.commitText(text, 1) },
        deleteBackward = { ic()?.deleteSurroundingText(1, 0) },
        performEnter = { performEnter() },
        toggleDictation = { toggleDictation() },
        togglePause = { togglePause() },
        cancelDictation = { cancelDictation() },
        dismiss = { requestHideSelf(0) },
    )

    private fun ic(): InputConnection? = currentInputConnection

    /**
     * ⏎: the field's action if it has one (send, search, …), a line break otherwise — what Enter does on
     * any keyboard, and what `sendKeyChar('\n')` does. Committing "\n" never triggered the action, so
     * WhatsApp could not send (#294). Once an action has fired the field is done with, so the keyboard
     * closes rather than sit opaque over the app's result — as m5991's #351 had it.
     */
    private fun performEnter() {
        if (sendDefaultEditorAction(true)) {
            requestHideSelf(0)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /**
     * Voice-page record button. Tap once to start recording, tap again to stop; on stop the audio is
     * transcribed (tethered via the phone when reachable, else standalone) and committed at the cursor.
     */
    private fun toggleDictation() {
        when (dictationState.value) {
            WearDictationState.RECORDING -> stopAndTranscribe()
            // Ignore taps while transcription/rewording is in flight; the X below cancels it.
            WearDictationState.TRANSCRIBING, WearDictationState.REWORDING -> Unit
            WearDictationState.READY -> finishWith(readyAction.intValue)
            // A failure with kept text or audio: the button tries that again rather than discarding it.
            WearDictationState.ERROR if retainedText.value != null -> insertRetainedText()
            WearDictationState.ERROR if retainedAudio.value != null -> retryTranscription()
            else -> startRecording()
        }
    }

    private fun startRecording() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // The IME can't request permissions; the settings activity grants RECORD_AUDIO.
            fail(getString(R.string.wear_err_mic_grant))
            return
        }
        val result = runCatching { recorder.start() }
        if (result.isSuccess) {
            // A new recording supersedes the kept one, and anything an older dictation still delivers.
            dictationGeneration++
            discardRetained()
            errorMessage.value = null
            recordingInfo.value = WearRecordingInfo(startedAtMs = SystemClock.elapsedRealtime())
            dictationState.value = WearDictationState.RECORDING
            WearHaptics.short(this) // recording started (#166)
        } else {
            fail(result.exceptionOrNull()?.shortReason() ?: getString(R.string.wear_err_mic_unavailable))
        }
    }

    /** Pause or resume the in-progress recording, accumulating elapsed time across segments. */
    private fun togglePause() {
        val info = recordingInfo.value
        if (dictationState.value != WearDictationState.RECORDING) return
        if (info.paused) {
            recorder.resume()
            recordingInfo.value = info.copy(startedAtMs = SystemClock.elapsedRealtime(), paused = false)
        } else {
            recorder.pause()
            val segment = SystemClock.elapsedRealtime() - info.startedAtMs
            recordingInfo.value = info.copy(accumulatedMs = info.accumulatedMs + segment, paused = true)
        }
    }

    /**
     * The X: throws away whatever is under way — the recording, the wait for its text (#363), or the
     * audio kept from a failed one — and goes back to the start. Under a finished dictation (#294) it
     * closes the keyboard without the action: the text is already in the field and stays there.
     */
    private fun cancelDictation() {
        if (dictationState.value == WearDictationState.READY) {
            leaveReady()
            requestHideSelf(0)
            return
        }
        if (recorder.isRecording) recorder.cancel()
        abandonTranscription()
        discardRetained()
        errorMessage.value = null
        recordingInfo.value = WearRecordingInfo()
        dictationState.value = WearDictationState.IDLE
    }

    private fun stopAndTranscribe() {
        WearHaptics.short(this) // recording stopped (#166)
        // The stop itself runs to the end even if the transcription is cancelled during it, so the file it
        // writes is always handed over and deleted, never left behind.
        transcribe { withContext(NonCancellable + Dispatchers.IO) { recorder.stop() } }
    }

    /**
     * Re-sends the audio of a dictation whose transcription failed (#218). The recording is kept on the
     * watch after a failure, so a dropped Bluetooth connection or a phone-side hiccup costs a tap instead
     * of everything the user just said.
     */
    private fun retryTranscription() {
        val audio = retainedAudio.value ?: return
        retainedAudio.value = null
        transcribe { audio }
    }

    /**
     * Into the transcribing state at once — spinner, and the seconds counting from now (#363) — then the
     * work, off the main thread so the IME never blocks long enough to trigger an ANR.
     */
    private fun transcribe(audioSource: suspend () -> File) {
        val generation = ++dictationGeneration
        errorMessage.value = null
        recordingInfo.value = WearRecordingInfo(startedAtMs = SystemClock.elapsedRealtime())
        dictationState.value = WearDictationState.TRANSCRIBING
        transcribeJob = scope.launch {
            // The X can reach the recorder before its stop does, and then there is no file to hand over.
            val audio = runCatching { audioSource() }.getOrElse { e ->
                Log.w(TAG, "No audio to transcribe", e)
                if (generation == dictationGeneration) fail(e.shortReason())
                return@launch
            }
            transcribeAudio(audio, generation)
        }
    }

    /** Drops the transcription in flight: its job tells the phone to stop, and its text is never typed. */
    private fun abandonTranscription() {
        dictationGeneration++
        transcribeJob?.cancel()
        transcribeJob = null
    }

    /** Stops a recording whose keyboard went away, keeping its audio to send later. */
    private fun interruptRecording() {
        val generation = ++dictationGeneration
        fail(getString(R.string.wear_status_interrupted))
        scope.launch {
            val audio = runCatching { withContext(NonCancellable + Dispatchers.IO) { recorder.stop() } }.getOrNull()
            when {
                // A new dictation, or the X, came in while the recorder was stopping.
                generation != dictationGeneration -> audio?.delete()
                audio != null -> retainedAudio.value = audio
                // Nothing was saved, so there is nothing to offer: back to the start, not a dead "Tap to send".
                else -> {
                    errorMessage.value = null
                    dictationState.value = WearDictationState.IDLE
                }
            }
        }
    }

    /**
     * Calls off a transcription whose field went away. The state says so at once; the audio is handed
     * back by [transcribeAudio] once the job has told the phone to stop.
     */
    private fun interruptTranscription() {
        val job = transcribeJob ?: return
        transcribeJob = null
        interruptedGeneration = dictationGeneration
        fail(getString(R.string.wear_status_interrupted))
        job.cancel()
    }

    private suspend fun transcribeAudio(audio: File, generation: Int) {
        run {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    WearTranscription.transcribe(
                        applicationContext,
                        audio,
                        // Fired when rewording starts, on the watch or on the phone → show "Rewording…".
                        // The transcript is ready at this point, so buzz "transcription done" (#166).
                        onRewording = {
                            scope.launch {
                                if (generation != dictationGeneration || generation == interruptedGeneration) return@launch
                                dictationState.value = WearDictationState.REWORDING
                                WearHaptics.double(applicationContext)
                            }
                        },
                    )
                }
            }
            if (generation != dictationGeneration) {
                // Cancelled with the X, or superseded by a new recording: the dictation is gone.
                audio.delete()
                return
            }
            if (generation == interruptedGeneration) {
                // Its field went away mid-wait; the state already says so, and the audio waits for a resend.
                retainedAudio.value = audio
                return
            }
            transcribeJob = null
            val text = outcome.getOrNull()
            when {
                outcome.isFailure -> {
                    val e = outcome.exceptionOrNull()
                    Log.e(TAG, "Dictation failed", e)
                    // Keep the audio so the user can re-send it with a tap instead of losing the dictation.
                    retainedAudio.value = audio
                    val reason = e?.shortReason() ?: getString(R.string.wear_err_transcribe_failed)
                    fail(getString(R.string.wear_err_tap_to_retry, reason))
                }
                text.isNullOrBlank() -> {
                    audio.delete()
                    fail(getString(R.string.wear_err_no_speech))
                }
                else -> {
                    audio.delete()
                    // Final buzz (#166): a longer one if a rewording just finished, else the double for a
                    // plain transcription (the double for the rewording case already fired at its start).
                    if (dictationState.value == WearDictationState.REWORDING) {
                        WearHaptics.medium(applicationContext)
                    } else {
                        WearHaptics.double(applicationContext)
                    }
                    deliver(text)
                }
            }
        }
    }

    /**
     * Puts a finished dictation into the field and decides how the keyboard leaves it (#294).
     *
     * A field without an action to finish with gets the text and the keyboard out of the way, so the user
     * sees it again. A field that sends, searches or goes cannot be left like that: the watch keyboard
     * covers it, apps such as WhatsApp offer no send button of their own there, and Samsung Browser's
     * search field is discarded as soon as the keyboard closes — the dictation used to land and vanish.
     * So the keyboard either takes the action itself (auto-send) or shows the text with a ✓ for it.
     */
    private fun deliver(text: String) {
        recordingInfo.value = WearRecordingInfo()
        // commitText answers false (or there is no connection at all) when the field went away meanwhile;
        // the text is kept to insert again rather than vibrating "done" over nothing.
        if (ic()?.commitText(text, 1) != true) {
            Log.w(TAG, "the field did not take the dictation, keeping it")
            retainedText.value = text
            fail(getString(R.string.wear_err_not_inserted))
            return
        }
        val action = currentInputEditorInfo?.dictationAction()
        when {
            action == null -> {
                dictationState.value = WearDictationState.IDLE
                requestHideSelf(0)
            }
            WearKeyboardPrefs.autoSend(this) -> finishWith(action)
            else -> {
                readyText.value = text
                readyAction.intValue = action
                dictationState.value = WearDictationState.READY
            }
        }
    }

    /** The ✓, or auto-send: the field's own action, as its own keyboard's action key would trigger it. */
    private fun finishWith(action: Int) {
        Log.i(TAG, "finishing the field with action $action")
        ic()?.performEditorAction(action)
        leaveReady()
        requestHideSelf(0)
    }

    private fun leaveReady() {
        readyText.value = null
        dictationState.value = WearDictationState.IDLE
    }

    /** Tries again to put a dictation the field did not take into whatever field is open now. */
    private fun insertRetainedText() {
        val text = retainedText.value ?: return
        retainedText.value = null
        errorMessage.value = null
        deliver(text)
    }

    /** Drops any kept failed-dictation audio or text. */
    private fun discardRetained() {
        retainedAudio.value?.let { runCatching { it.delete() } }
        retainedAudio.value = null
        retainedText.value = null
    }

    private fun fail(reason: String) {
        errorMessage.value = reason
        recordingInfo.value = WearRecordingInfo()
        dictationState.value = WearDictationState.ERROR
    }

    /** A compact, user-facing reason from a thrown error (class name fallback when there's no message). */
    private fun Throwable.shortReason(): String =
        (message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: getString(R.string.wear_err_unknown)).take(80)

    private companion object {
        const val TAG = "WearIme"
    }
}
