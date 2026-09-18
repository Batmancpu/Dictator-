/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.scan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.StringRes
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Survives a release build, unlike Flog — see the catch in [ScanController.read]. */
private const val SCAN_LOG_TAG = "DictateScan"

/**
 * What the scan panel is showing, and everything the keyboard does about a scan (issue #390).
 *
 * The session lives here and **only** here: in memory, for as long as the keyboard window is up. Nothing
 * a scan produces is written anywhere — not to the clipboard, not to the dictation history, not to a
 * preference, not to the word learner. That is what makes incognito and password fields need no special
 * case: there is no store to keep out of. The photo itself is gone from disk before the recogniser even
 * starts, and the bitmap dies with [clear].
 */
object ScanController {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    sealed interface Session {
        /** The photo is being read. The panel is already on screen saying so. */
        data object Working : Session

        data class Ready(
            val bitmap: Bitmap,
            val scan: ScanText,
        ) : Session

        data class Failed(@StringRes val messageRes: Int) : Session
    }

    val session = MutableStateFlow<Session?>(null)

    /** Indices into [ScanText.lines] the user has tapped. Order is irrelevant; reading order wins. */
    val selection = MutableStateFlow<Set<Int>>(emptySet())

    /** The app the current session belongs to, so a scan cannot outlive the field it was started for. */
    private var sessionPackage: String? = null

    /**
     * Sends the user to the camera (or the picker). The session is left alone on purpose: backing out of
     * the camera should return to the photo that was already there, not to an empty panel.
     */
    fun startCapture(context: Context, pickExisting: Boolean = false) {
        val appContext = context.applicationContext
        val sourcePackage = runCatching {
            appContext.editorInstance().value.activeInfo.packageName
        }.getOrNull()
        ScanHandoff.reset(appContext)
        runCatching {
            context.startActivity(ScanCaptureActivity.intentFor(appContext, sourcePackage, pickExisting))
        }.onFailure {
            flogError { "scan: could not start the capture activity: $it" }
            session.value = Session.Failed(R.string.scan__no_camera)
        }
    }

    /**
     * Picks up a finished capture, if there is one. Called from both `onStartInputView` and
     * `onWindowShown` — whichever runs first claims it, and the other finds nothing.
     *
     * Returns `true` when a scan was taken, which the service uses to keep an instant recording from
     * starting on top of the panel that is about to open.
     */
    fun consumePendingScan(context: Context): Boolean {
        val appContext = context.applicationContext
        // Look before taking. A scan that cannot be judged yet has to be left exactly where it is, and
        // claiming moves it — so the verdict comes first and the rename second.
        val pending = ScanHandoff.peek(appContext) ?: run {
            ScanHandoff.sweep(appContext)
            return false
        }
        val currentPackage = runCatching {
            appContext.editorInstance().value.activeInfo.packageName
        }.getOrNull()
        when (ScanHandoffRules.verdict(pending, System.currentTimeMillis(), currentPackage)) {
            ScanVerdict.DEFER -> {
                // Coming back from the camera, the window can be shown before the editor is reattached,
                // and the editor info is blank rather than wrong. The next hook will know.
                flogInfo { "scan: deferring, the editor has not said which app it belongs to yet" }
                return false
            }
            ScanVerdict.DISCARD -> {
                flogInfo { "scan: discarding a scan from ${pending.sourcePackage} (now $currentPackage)" }
                ScanHandoff.discardPending(appContext)
                return false
            }
            ScanVerdict.ACCEPT -> Unit
        }

        val claim = ScanHandoff.claim(appContext) ?: return false
        when (claim.result.status) {
            ScanStatus.CAPTURED -> {
                val photo = claim.photo
                if (photo == null) {
                    Log.e(SCAN_LOG_TAG, "the note says CAPTURED but there is no photo next to it")
                    flogError { "scan: the note says CAPTURED but there is no photo next to it" }
                    ScanHandoff.discardClaim(claim.dir)
                    session.value = Session.Failed(R.string.scan__failed)
                } else {
                    clear()
                    sessionPackage = currentPackage
                    session.value = Session.Working
                    scope.launch { read(claim.dir, photo) }
                }
            }
            // Backing out of the camera is not a failure and must not look like one: the panel comes
            // back exactly as it was left.
            ScanStatus.CANCELLED -> ScanHandoff.discardClaim(claim.dir)
            ScanStatus.FAILED -> {
                ScanHandoff.discardClaim(claim.dir)
                session.value = Session.Failed(R.string.scan__failed)
            }
        }
        openPanel(appContext)
        return true
    }

    private suspend fun read(claimDir: java.io.File, photo: java.io.File) {
        val recognised = try {
            ScanRecognizer.read(photo)
        } catch (e: Exception) {
            // android.util.Log, not flogError: Flog is compiled to nothing outside a debug build, and a
            // failure the user is told about ("the photo could not be read") must never be the only trace
            // of itself. This one cost a release build to find out the hard way.
            Log.e(SCAN_LOG_TAG, "recognition failed", e)
            null
        } finally {
            // The photo has been decoded into memory (or has failed to be), so it has no further use.
            // Deleting here rather than at the end of the panel's life is what keeps #390's promise that
            // the picture does not linger — it is off the disk seconds after the shutter. Only this
            // claim's own directory: another consume may be reading from its own at the same moment.
            ScanHandoff.discardClaim(claimDir)
        }
        flogInfo { "scan: recognised ${recognised?.scan?.lines?.size ?: -1} lines" }
        if (recognised == null) Log.e(SCAN_LOG_TAG, "nothing came back from the recogniser")
        session.value = when {
            recognised == null -> Session.Failed(R.string.scan__failed)
            recognised.scan.isEmpty -> {
                recognised.bitmap.recycle()
                Session.Failed(R.string.scan__no_text)
            }
            else -> Session.Ready(bitmap = recognised.bitmap, scan = recognised.scan)
        }
    }

    /** Commits [text] at the cursor, through the same path as every other insert in the app. */
    fun insert(context: Context, text: String) {
        if (text.isBlank()) return
        runCatching { context.applicationContext.editorInstance().value.commitText(text) }
    }

    /**
     * Drops the session. The bitmap is released rather than recycled on purpose: it has been handed to
     * Compose, and a recycled bitmap that one more frame still wants to draw is a crash, while one that
     * is merely unreferenced is a few megabytes the collector takes at its leisure.
     */
    fun clear() {
        session.value = null
        selection.value = emptySet()
        sessionPackage = null
    }

    /**
     * Drops a session that belongs to a different app than the one now being typed into. A scan of an
     * IBAN started in a banking app has no business still being on screen in a messenger.
     */
    fun clearIfForeign(context: Context) {
        if (session.value == null) return
        val currentPackage = runCatching {
            context.applicationContext.editorInstance().value.activeInfo.packageName
        }.getOrNull()
        if (sessionPackage != null && sessionPackage != currentPackage) clear()
    }

    fun toggleLine(index: Int) {
        val current = selection.value
        selection.value = if (index in current) current - index else current + index
    }

    fun selectBlockOf(index: Int) {
        val scan = (session.value as? Session.Ready)?.scan ?: return
        val block = scan.lines.getOrNull(index)?.blockIndex ?: return
        val whole = scan.lines.indices.filter { scan.lines[it].blockIndex == block }.toSet()
        selection.value = if (whole.all { it in selection.value }) selection.value - whole else selection.value + whole
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    /** Everything on the page, for the panel's "select all". */
    fun selectAll() {
        val scan = (session.value as? Session.Ready)?.scan ?: return
        selection.value = scan.lines.indices.toSet()
    }

    private fun openPanel(context: Context) {
        runCatching { context.keyboardManager().value.activeState.imeUiMode = ImeUiMode.SCAN }
    }
}
