/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.translate

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.subtypeManager
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The translate bar's behaviour (issue #424): the user types into the bar with the keyboard below it,
 * and the translation of what they typed stands in the app's text field, replaced as the text grows.
 *
 * The query lives in [query], which `KeyboardManager` fills from the keys exactly like the emoji or
 * GIF search. Translating waits [DEBOUNCE_MS] after the last keystroke — Mozilla's models change their
 * wording as a sentence grows ("I'm coming" → "I'll be"), and without a pause the field would flicker
 * through every variant — and a newer keystroke cancels a translation still waiting for its answer.
 *
 * Nothing is sent anywhere: the engine runs on the phone, in its own process ([TranslationEngineClient]),
 * which is released as soon as the bar closes.
 */
@OptIn(FlowPreview::class)
class TranslateBarController(
    context: Context,
    private val query: MutableStateFlow<String?>,
    private val cursor: MutableStateFlow<Int>,
    private val focused: MutableStateFlow<Boolean>,
    private val selection: MutableStateFlow<IntRange?>,
) {
    private val appContext = context.applicationContext
    private val prefs by FlorisPreferenceStore
    private val editorInstance by appContext.editorInstance()
    private val subtypeManager by appContext.subtypeManager()

    sealed interface Status {
        data object Idle : Status
        data object Translating : Status
        /** No language is downloaded, so the bar has nothing to translate with. */
        data object NoLanguages : Status
        /** The chosen or detected pair needs [missing], which is not downloaded. */
        data class NotInstalled(val missing: List<String>) : Status
        data object UnsupportedDevice : Status
        data object Failed : Status
    }

    enum class Side { SOURCE, TARGET }

    data class State(
        /** The source the user picked, or `null` for "detect it". */
        val source: String? = null,
        /** What detection settled on for the current text, while [source] is `null`. */
        val detected: String? = null,
        val target: String = TranslationCatalog.ENGLISH,
        val installed: Set<String> = emptySet(),
        val status: Status = Status.Idle,
        /** Which chip's language row is open instead of the chips, if any. */
        val picking: Side? = null,
    ) {
        /** The source a translation uses right now. */
        val effectiveSource: String? get() = source ?: detected
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Everything that may write into the field while the bar is open; stopped before a final write. */
    private val live = mutableListOf<Job>()

    /** Serialises writes into the field, so two results can never interleave their delete and insert. */
    private val writeLock = Mutex()

    /**
     * One stretch of translation standing in the app: from opening the bar, an Enter or a fresh start to
     * the next. Each has its own record of what it wrote (issue #433) — shared, a segment still finishing
     * after the next had begun read the newer one's state, found nothing of its own in the field and wrote
     * its result a second time.
     */
    private class Segment {
        /** Exactly what the last write put into the field, separator included; `null` when nothing is ours. */
        var written: String? = null

        /** The query the text in the field is the translation of. */
        var writtenFor: String? = null
    }

    private var segment = Segment()

    /**
     * The Enter or close still bringing its segment's translation up to date. The first translation after
     * opening waits for the engine to start (issue #433), and an Enter that seemed to do nothing got pressed
     * again — each press then wrote the result once more. Now a second Enter waits its turn by being ignored,
     * and a close waits for it.
     */
    private var finishing: Job? = null

    /**
     * Until when a selection change in the app is our own doing: a write, or the Enter after one, moves
     * the app's cursor too, and must not read as the user tapping into the app ([onSelectionChanged]).
     */
    private var ownEditUntil = 0L

    fun open() {
        if (query.value != null) return
        FlorisImeService.currentInputConnection()?.finishComposingText()
        segment = Segment()
        val installed = TranslationModelManager.installed(appContext).value
        val source = prefs.translation.sourceLanguage.get().takeIf { it.isAvailable(installed) }
        _state.value = State(
            source = source,
            target = initialTarget(installed, source),
            installed = installed,
            status = if (installed.isEmpty()) Status.NoLanguages else Status.Idle,
        )
        query.value = ""
        cursor.value = 0
        selection.value = null
        focused.value = true
        startLive()
    }

    /**
     * Closes the bar. With [finish] the translation of what is typed is brought up to date first, so
     * text typed in the last fraction of a second is not lost; without it (the field changed under us)
     * nothing more is written. Either way the text already in the field stays there — it is the result.
     */
    fun close(finish: Boolean = true) {
        val last = query.value ?: return
        val wasFocused = focused.value
        val ending = segment
        segment = Segment()
        query.value = null
        cursor.value = 0
        selection.value = null
        focused.value = true
        stopLive()
        val before = finishing
        finishing = scope.launch {
            before?.join()
            // Unfocused, the user has moved on to the app's own text: nothing more is written into it.
            if (finish && wasFocused) finishWriting(ending, last)
            // Opened again while this one was finishing: the engine belongs to the new bar now.
            if (query.value != null) return@launch
            TranslationEngineClient.release(appContext)
            _state.update { it.copy(picking = null) }
        }
    }

    /**
     * Enter in the bar where the app's Enter is an action: finish the translation, keep it, and then let
     * Enter do what it does in the app ([sendEnter]) — in a chat that is sending, and a message must never
     * go out half translated.
     *
     * The field empties at once, so the press visibly took; whatever is typed while the last translation is
     * still on its way waits in it and is translated as the next segment once the Enter has gone through.
     */
    fun submit(sendEnter: () -> Unit) {
        val last = query.value ?: return
        if (finishing?.isActive == true) return
        stopLive()
        val ending = segment
        segment = Segment()
        query.value = ""
        cursor.value = 0
        selection.value = null
        finishing = scope.launch {
            finishWriting(ending, last)
            if (query.value == null) return@launch
            ownEditUntil = SystemClock.uptimeMillis() + OWN_EDIT_WINDOW_MS
            sendEnter()
            if (focused.value) startLive()
        }
    }

    /** The ✕: empties the query, which takes its translation back out of the field. */
    fun clear() {
        if (query.value == null) return
        query.value = ""
        cursor.value = 0
        selection.value = null
        focus(0)
    }

    /**
     * The field lets go of the keys (a tap into the app's field): no more writes until it is tapped
     * again. The query and the translation stay where they are.
     */
    fun unfocus() {
        if (query.value == null || !focused.value) return
        focused.value = false
        _state.update { it.copy(picking = null) }
        stopLive()
    }

    /**
     * A tap on the bar's field: it takes the keys back, with the cursor where the finger was.
     *
     * Unless the app's text moved on while the keys were there — the user edited it, moved its cursor, or
     * the translation was replaced. Then the old translation is finished text, and the bar starts a new
     * one, empty, where the app's cursor is now (issue #433). Carrying on with the old query wrote the
     * whole of it a second time at the new place.
     */
    fun focus(offset: Int) {
        val text = query.value ?: return
        if (!focused.value && !ownsTextBeforeCursor(segment)) {
            startSegment()
        } else {
            cursor.value = offset.coerceIn(0, text.length)
            selection.value = null
        }
        if (focused.value) return
        focused.value = true
        startLive()
    }

    /**
     * The app reported a new selection. Moved by us — within [OWN_EDIT_WINDOW_MS] of a write — it means
     * nothing. An app field that is empty now was sent or cleared by the app, as a chat does with the send
     * button; the bar keeps the keys and starts afresh for the next message (issue #433). Moved by anyone
     * else it was the user tapping or dragging in the app's field, which takes the keys back there (issue
     * #424). Complements `onViewClicked`, which not every app sends.
     */
    fun onSelectionChanged(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) {
        if (query.value == null || !focused.value) return
        if (oldStart == newStart && oldEnd == newEnd) return
        if (SystemClock.uptimeMillis() < ownEditUntil) return
        if (appFieldIsEmpty()) {
            startSegment()
            return
        }
        unfocus()
    }

    /** An empty query and nothing of the app's text counted as ours: the next translation goes in fresh. */
    private fun startSegment() {
        segment = Segment()
        query.value = ""
        cursor.value = 0
        selection.value = null
    }

    /** Whether the text in front of the app's cursor still ends with what [segment] wrote last. */
    private fun ownsTextBeforeCursor(segment: Segment): Boolean {
        val written = segment.written ?: return true
        val before = FlorisImeService.currentInputConnection()?.getTextBeforeCursor(written.length, 0) ?: return false
        return before.toString().endsWith(written)
    }

    private fun appFieldIsEmpty(): Boolean {
        val ic = FlorisImeService.currentInputConnection() ?: return false
        return ic.getTextBeforeCursor(1, 0)?.isEmpty() == true && ic.getTextAfterCursor(1, 0)?.isEmpty() == true
    }

    fun openPicker(side: Side?) = _state.update { it.copy(picking = side) }

    fun pick(side: Side, code: String?) {
        _state.update {
            when (side) {
                Side.SOURCE -> it.copy(source = code, detected = null, picking = null)
                Side.TARGET -> it.copy(target = code ?: TranslationCatalog.ENGLISH, picking = null)
            }
        }
        persist()
        segment.writtenFor = null
        retranslate()
    }

    /** ⇄: the target becomes the source and the other way round. Detection resolves first. */
    fun swap() {
        _state.update {
            val from = it.effectiveSource ?: return@update it
            it.copy(source = it.target, detected = null, target = from, picking = null)
        }
        persist()
        segment.writtenFor = null
        retranslate()
    }

    private fun startLive() {
        live += scope.launch {
            query.filterNotNull()
                // Emptying the query takes the result out at once; only a growing text waits.
                .debounce { if (it.isEmpty()) 0L else DEBOUNCE_MS }
                // Refocusing replays the current query; what is already in the field needs no rewrite.
                .collectLatest { text ->
                    val current = segment
                    if (text != current.writtenFor) translateAndWrite(current, text, live = true)
                }
        }
        live += scope.launch {
            TranslationModelManager.installed(appContext).collect { installed ->
                _state.update {
                    val status = when {
                        installed.isEmpty() -> Status.NoLanguages
                        it.status == Status.NoLanguages -> Status.Idle
                        else -> it.status
                    }
                    it.copy(installed = installed, status = status)
                }
            }
        }
    }

    private fun stopLive() {
        live.forEach { it.cancel() }
        live.clear()
    }

    private fun persist() {
        val current = _state.value
        scope.launch {
            prefs.translation.sourceLanguage.set(current.source.orEmpty())
            prefs.translation.targetLanguage.set(current.target)
        }
    }

    private fun retranslate() {
        val text = query.value ?: return
        if (!focused.value) return
        val current = segment
        live += scope.launch { translateAndWrite(current, text, live = true) }
    }

    /** The last write of [segment], which has ended: its translation brought up to date with [text]. */
    private suspend fun finishWriting(segment: Segment, text: String) {
        if (text != segment.writtenFor) translateAndWrite(segment, text, live = false)
    }

    /**
     * [text] translated and written into the app for [segment]. [live] is a result of the running segment,
     * which is dropped if the segment has ended by the time it arrives; the finishing write of a segment
     * that has just ended is not.
     */
    private suspend fun translateAndWrite(segment: Segment, text: String, live: Boolean) {
        if (text.isBlank()) {
            write(segment, "", text, live)
            _state.update { if (it.status is Status.NoLanguages) it else it.copy(status = Status.Idle, detected = null) }
            return
        }
        val current = _state.value
        if (current.installed.isEmpty()) return
        val source = current.source ?: detect(text, current).also { detected ->
            _state.update { it.copy(detected = detected) }
        }
        if (source == null) {
            _state.update { it.copy(status = Status.Failed) }
            return
        }
        val missing = TranslationCatalog.requiredLanguages(source, current.target).filter { it !in current.installed }
        if (missing.isNotEmpty()) {
            _state.update { it.copy(status = Status.NotInstalled(missing)) }
            return
        }
        if (source == current.target) {
            write(segment, text, text, live)
            _state.update { it.copy(status = Status.Idle) }
            return
        }
        _state.update { it.copy(status = Status.Translating) }
        when (val result = TranslationEngineClient.translate(appContext, source, current.target, text)) {
            is TranslationEngineClient.Result.Translated -> {
                write(segment, result.text, text, live)
                _state.update { it.copy(status = Status.Idle) }
            }
            is TranslationEngineClient.Result.Failed -> _state.update {
                it.copy(
                    status = when (result.error) {
                        TranslationProtocol.ERROR_UNSUPPORTED_DEVICE -> Status.UnsupportedDevice
                        TranslationProtocol.ERROR_NOT_INSTALLED ->
                            Status.NotInstalled(TranslationCatalog.requiredLanguages(source, current.target).toList())
                        else -> Status.Failed
                    },
                )
            }
        }
    }

    private suspend fun write(segment: Segment, translation: String, forQuery: String, live: Boolean) = writeLock.withLock {
        // A result of a segment that an Enter or a fresh start has ended belongs to text that is finished.
        if (live && segment !== this.segment) return@withLock
        val ic = FlorisImeService.currentInputConnection() ?: return@withLock
        val previous = segment.written
        // One character more than we wrote, so the separator can see what stands in front of it.
        val before = ic.getTextBeforeCursor((previous?.length ?: 0) + 1, 0)?.toString().orEmpty()
        val edit = TranslationInsertion.edit(before, previous, translation)
        if (edit == null) {
            // Our last result is no longer in front of the cursor: the app's text changed under the bar
            // (issue #433). The keys go back to the app, as after a tap into it, and a tap on the bar starts
            // over wherever the cursor is then — writing on here put the translation in twice.
            if (live) unfocus()
            return@withLock
        }
        if (edit.deleteBefore == 0 && edit.text.isEmpty()) {
            segment.written = null
            segment.writtenFor = forQuery
            return@withLock
        }
        ownEditUntil = SystemClock.uptimeMillis() + OWN_EDIT_WINDOW_MS
        editorInstance.replaceTextBeforeCursor(edit.deleteBefore, edit.text)
        segment.written = edit.text.ifEmpty { null }
        segment.writtenFor = forQuery
    }

    /**
     * The language of [text], by the system's language detector (API 29). Short fragments are not worth
     * asking about — a single word is too often a word of several languages — so until the text is long
     * enough, and whenever the detector is unsure or names a language that is not downloaded, the last
     * detection stands, and before the first one the keyboard's own language is the guess.
     */
    private suspend fun detect(text: String, current: State): String? {
        val fallback = current.detected ?: subtypeLanguage()?.takeIf { it.isAvailable(current.installed) }
            ?: current.installed.firstOrNull { it != current.target } ?: TranslationCatalog.ENGLISH
        if (text.trim().length < MIN_DETECT_CHARS || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return fallback
        val detected = withContext(Dispatchers.Default) {
            runCatching {
                val manager = appContext.getSystemService(TextClassificationManager::class.java) ?: return@runCatching null
                val result = manager.textClassifier.detectLanguage(TextLanguage.Request.Builder(text).build())
                if (result.localeHypothesisCount == 0) return@runCatching null
                val best = result.getLocale(0)
                if (result.getConfidenceScore(best) < MIN_DETECT_CONFIDENCE) null else best.toLanguageTag()
            }.getOrNull()
        }
        // Only a language the bar can translate from counts: a text detected as Dutch on a phone without
        // Dutch is far more often a short or mixed text misread than a wish to translate Dutch.
        return detected?.let(TranslationCatalog::codeFor)?.takeIf { it.isAvailable(current.installed) } ?: fallback
    }

    private fun subtypeLanguage(): String? =
        TranslationCatalog.codeFor(subtypeManager.activeSubtype.primaryLocale.localeTag())

    /**
     * The target the bar opens with: the one used last, if it can still be translated into; otherwise
     * English for anyone whose phone is not English, and for those whose phone is, the first language
     * they downloaded — which is presumably why they downloaded it.
     */
    private fun initialTarget(installed: Set<String>, source: String?): String {
        prefs.translation.targetLanguage.get().takeIf { it.isNotEmpty() && it.isAvailable(installed) }?.let { return it }
        val device = TranslationCatalog.codeFor(Locale.getDefault().toLanguageTag())
        return if (device == TranslationCatalog.ENGLISH) {
            installed.firstOrNull { it != source } ?: TranslationCatalog.ENGLISH
        } else {
            TranslationCatalog.ENGLISH
        }
    }

    private fun String.isAvailable(installed: Set<String>) = this == TranslationCatalog.ENGLISH || this in installed

    companion object {
        /** Gboard waits 300–600 ms; Bergamot answers in ~60–100 ms on a mid-range phone, so the low end. */
        const val DEBOUNCE_MS = 350L

        /**
         * How long after our own edit a selection change in the app is still ours. The emoji search uses
         * the same window for the emojis it inserts (issue #394).
         */
        const val OWN_EDIT_WINDOW_MS = 800L
        private const val MIN_DETECT_CHARS = 12
        private const val MIN_DETECT_CONFIDENCE = 0.5f

        /**
         * Whether the next letter typed into [query] starts a sentence, for auto-capitalisation — also the
         * first of a new line, which the bar can hold now (issue #433).
         */
        fun startsSentence(query: String): Boolean =
            query.isBlank() || Regex("""(?:[.!?…]\s+|\n\s*)$""").containsMatchIn(query)
    }
}
