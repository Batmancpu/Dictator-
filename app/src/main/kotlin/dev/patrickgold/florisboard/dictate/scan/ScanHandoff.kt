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
import android.net.Uri
import androidx.core.content.FileProvider
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import java.io.File

/**
 * How a photo gets from the camera app back to the keyboard (issue #390).
 *
 * The keyboard is an [android.inputmethodservice.InputMethodService] and cannot start an activity for a
 * result, so [ScanCaptureActivity] does it and leaves the answer on disk. Disk rather than a preference
 * for the reason [dev.patrickgold.florisboard.dictate.FileTranscriptionActivity] already gives: the IME
 * process is routinely killed while a camera — the hungriest app on the phone — is in the foreground,
 * and a file is the only thing guaranteed to survive that.
 *
 * The note and the photo sit in **one directory**, and claiming it is a **single rename of that
 * directory**. That is deliberate: two files claimed in two steps can be interrupted between them, and
 * then a second consume finds half a scan. One rename either happened or it did not, and the loser of a
 * race finds nothing at all.
 *
 * **Each claim gets its own directory**, named after the moment it was claimed. That is not tidiness:
 * two consume hooks fire on the way back, the first one's photo is still being decoded when the second
 * runs, and a single shared claim directory meant the second one's housekeeping deleted the file out
 * from under the first one's decode. The photo then failed to read for no reason the user could see.
 * With a name per claim, [sweep] can collect abandoned directories without ever touching a live one.
 *
 * There is intentionally no "a scan is pending" flag beside the file. [InstantRecordingArm] needs one
 * because what it remembers is a bit with nothing behind it; here the file *is* the message, and a flag
 * next to it would be a second truth able to disagree with the first.
 */
object ScanHandoff {

    /** Declared as a `<cache-path>` in `res/xml/file_paths.xml`; the camera writes into it through it. */
    private const val PENDING_DIR = "scan-pending"

    /** Not declared anywhere, and must not be: once claimed, nobody outside this app may read it. */
    private const val CLAIM_PREFIX = "scan-claim-"

    private const val PHOTO_NAME = "capture.jpg"

    /** How long a directory may sit untouched before [sweep] treats it as abandoned. */
    private const val GRACE_MS = 2 * 60 * 1000L

    private fun authority(context: Context) = "${context.packageName}.provider.file"

    fun pendingDir(context: Context): File = File(context.cacheDir, PENDING_DIR)

    /** Where both routes put their image. A fixed name, so it never has to be restored from state. */
    fun photoFile(context: Context): File = File(pendingDir(context), PHOTO_NAME)

    /**
     * Empties the pending directory and hands back the file the camera should write into, plus the URI to
     * hand it. A fixed name, so nothing has to be restored after a process death — it can always be
     * recomputed.
     */
    fun prepare(context: Context): Pair<File, Uri>? = runCatching {
        val dir = pendingDir(context)
        dir.deleteRecursively()
        dir.mkdirs()
        val photo = File(dir, PHOTO_NAME)
        photo.delete()
        photo.createNewFile()
        photo to FileProvider.getUriForFile(context, authority(context), photo)
    }.onFailure {
        flogError { "scan: could not prepare the capture file: $it" }
    }.getOrNull()

    /**
     * Leaves the note. Written to a temporary name and renamed into place, because a note read while it
     * is still half written is worse than no note: it would parse as a failure and throw away a photo
     * that is perfectly good.
     */
    fun writeResult(context: Context, result: ScanResult) {
        runCatching {
            val dir = pendingDir(context).apply { mkdirs() }
            val tmp = File(dir, "${ScanResult.FILE_NAME}.tmp")
            tmp.writeText(ScanResult.Json.encodeToString(ScanResult.serializer(), result))
            tmp.renameTo(File(dir, ScanResult.FILE_NAME))
            flogInfo { "scan: wrote ${result.status} for ${result.sourcePackage}" }
        }.onFailure {
            flogError { "scan: could not write the result note: $it" }
        }
    }

    /**
     * Reads the note without taking it, so the caller can decide whether this scan is theirs before
     * anything is moved or deleted. A [ScanVerdict.DEFER] has to leave the cache exactly as it found it.
     */
    fun peek(context: Context): ScanResult? = runCatching {
        val note = File(pendingDir(context), ScanResult.FILE_NAME)
        if (!note.isFile) return@runCatching null
        ScanResult.Json.decodeFromString(ScanResult.serializer(), note.readText())
    }.onFailure {
        flogError { "scan: unreadable result note, dropping it: $it" }
        discardPending(context)
    }.getOrNull()

    /** What a claim yields: the note, the photo if there is one, and the directory both live in. */
    data class Claim(val result: ScanResult, val photo: File?, val dir: File)

    /**
     * Takes the pending scan by renaming its directory out of the way. Calling it twice returns null the
     * second time, which is what makes the two consume hooks in
     * [dev.patrickgold.florisboard.FlorisImeService] safe to both fire.
     */
    fun claim(context: Context): Claim? = runCatching {
        val pending = pendingDir(context)
        if (!File(pending, ScanResult.FILE_NAME).isFile) return@runCatching null
        val claimed = File(context.cacheDir, "$CLAIM_PREFIX${System.currentTimeMillis()}")
        claimed.deleteRecursively()
        if (!pending.renameTo(claimed)) {
            // Same filesystem, so this should not happen; if it somehow does, do not leave the note
            // lying around to be found again on the next keystroke.
            flogError { "scan: could not claim the pending directory, discarding it" }
            pending.deleteRecursively()
            return@runCatching null
        }
        val photo = File(claimed, PHOTO_NAME).takeIf { it.isFile && it.length() > 0L }
        flogInfo { "scan: claimed ${claimed.name}, photo=${photo?.length() ?: -1} bytes" }
        Claim(ScanResult.Json.decodeFromString(
            ScanResult.serializer(),
            File(claimed, ScanResult.FILE_NAME).readText(),
        ), photo, claimed)
    }.onFailure {
        flogError { "scan: claim failed: $it" }
    }.getOrNull()

    /** Deletes one claimed directory. Called by whoever claimed it, once its photo has been decoded. */
    fun discardClaim(dir: File) {
        runCatching { dir.deleteRecursively() }
    }

    /** Throws away an unclaimed scan — the [ScanVerdict.DISCARD] case. */
    fun discardPending(context: Context) {
        runCatching { pendingDir(context).deleteRecursively() }
    }

    /**
     * Collects what nobody is going to come back for, and **nothing else**.
     *
     * Everything here hangs on one thing: *a directory with no note in it is not rubbish, it is a
     * capture in flight.* The note is the last thing written, so between the moment the camera is handed
     * its target and the moment it returns, `scan-pending/` holds an empty `capture.jpg` and no note at
     * all. An earlier version read exactly that state as "nothing pending, tidy it away" — and since
     * this runs on every keyboard show, it deleted the directory the camera was about to save into. The
     * camera then reported a cancel it had not been given, the photo picker's copy hit a missing parent
     * directory, and both failures looked to the user like a photo that could not be read.
     *
     * So age, never emptiness, decides. A live capture's directory was touched seconds ago; an
     * abandoned one — the process died while the camera was up — has not been touched for
     * [GRACE_MS]. Claim directories are swept on the same rule, for the same reason: one of them may be
     * feeding a decode that is still running.
     */
    fun sweep(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - GRACE_MS
            val pending = pendingDir(context)
            if (pending.exists() && !File(pending, ScanResult.FILE_NAME).isFile &&
                pending.lastModified() < cutoff
            ) {
                flogInfo { "scan: sweeping an abandoned capture" }
                pending.deleteRecursively()
            }
            context.cacheDir.listFiles { file -> file.isDirectory && file.name.startsWith(CLAIM_PREFIX) }
                ?.filter { it.lastModified() < cutoff }
                ?.forEach {
                    flogInfo { "scan: sweeping abandoned ${it.name}" }
                    it.deleteRecursively()
                }
        }
    }

    /**
     * Clears the way for a new capture: the pending directory goes unconditionally, note or not.
     * Claimed directories are left to [sweep]'s age check — one of them may belong to a decode that is
     * still running, and a photo the user is about to replace anyway is not worth risking a crash for.
     */
    fun reset(context: Context) {
        runCatching { pendingDir(context).deleteRecursively() }
        sweep(context)
    }
}
