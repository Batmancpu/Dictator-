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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible trampoline for the scan panel (issue #390): opens the system camera or the photo picker,
 * leaves the photo and a note in the cache, and finishes.
 *
 * **It declares no permission and needs none.** An app that does not declare `CAMERA` may still fire
 * `ACTION_IMAGE_CAPTURE` — the system camera app takes the picture and hands the file back — and the
 * photo picker was built precisely so that reading one image never costs access to all of them. The
 * moment this app declared `CAMERA`, the intent would start demanding it be granted, so the absence is
 * load-bearing, not an oversight. This is what made #390 possible at all, under the rule #316 set: a
 * feature is not worth a permission that sits in the manifest forever, for everyone.
 *
 * Recognition deliberately does **not** happen here. The panel shows the photo, so the bitmap has to
 * reach the keyboard anyway, and doing the work there means the panel is on screen with a spinner
 * instead of the user staring at their own app wondering whether anything happened.
 */
class ScanCaptureActivity : ComponentActivity() {

    private var sourcePackage: String? = null

    // No grantUriPermission loop: TakePicture.createIntent already adds FLAG_GRANT_READ_URI_PERMISSION
    // and FLAG_GRANT_WRITE_URI_PERMISSION itself (verified in the androidx.activity 1.13.0 artifact).
    // Older versions did not, so if this ever starts throwing a SecurityException, that is where to look.
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        // The file is the evidence; the result code is only a claim about it. Several camera apps —
        // Samsung's among the ones reported — hand back RESULT_CANCELED after writing a perfectly good
        // photo, and believing them throws the user's picture away. A real cancel leaves the empty file
        // this activity created, so length is the honest test either way.
        val written = ScanHandoff.photoFile(this).length() > 0L
        if (!taken && written) {
            flogInfo { "scan: the camera reported a cancel but wrote a photo; keeping it" }
        }
        finishWith(if (taken || written) ScanStatus.CAPTURED else ScanStatus.CANCELLED)
    }

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            finishWith(ScanStatus.CANCELLED)
        } else {
            copyThenFinish(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
        // Only on a fresh start. After a process death the result registry restores itself and delivers
        // the photo that was already taken; launching again here would send the user back to the camera
        // for a picture they have already got.
        if (savedInstanceState != null) return

        val prepared = ScanHandoff.prepare(this)
        if (prepared == null) {
            finishWith(ScanStatus.FAILED)
            return
        }
        val (_, uri) = prepared
        val launched = runCatching {
            if (intent.getBooleanExtra(EXTRA_PICK_EXISTING, false)) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } else {
                camera.launch(uri)
            }
        }
        // Package visibility hides *queries*, not launches, so there is nothing to ask in advance —
        // a phone with no camera app is simply one where this throws, and says so once.
        if (launched.isFailure) {
            Toast.makeText(this, R.string.scan__no_camera, Toast.LENGTH_LONG).show()
            finishWith(ScanStatus.FAILED)
        }
    }

    /** The picked image is copied into our own cache so both routes end at exactly the same file. */
    private fun copyThenFinish(uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val target = ScanHandoff.photoFile(this@ScanCaptureActivity)
                    // Recreate the directory rather than assume it: a copy that lands nowhere is the
                    // failure this whole route reported for a while, and it said nothing about why.
                    target.parentFile?.mkdirs()
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching false
                    target.length() > 0L
                }.onFailure {
                    flogError { "scan: could not copy the picked image: $it" }
                }.getOrDefault(false)
            }
            finishWith(if (ok) ScanStatus.CAPTURED else ScanStatus.FAILED)
        }
    }

    private fun finishWith(status: ScanStatus) {
        flogInfo { "scan: capture finished as $status for $sourcePackage" }
        // A note is left even for a cancel. Without one nothing reopens the panel, and backing out of the
        // camera would drop the user on the bare keyboard as though the button had done nothing.
        ScanHandoff.writeResult(
            this,
            ScanResult(
                status = status,
                sourcePackage = sourcePackage,
                capturedAtMs = System.currentTimeMillis(),
            ),
        )
        finish()
    }

    companion object {
        const val EXTRA_SOURCE_PACKAGE = "scan_source_package"
        const val EXTRA_PICK_EXISTING = "scan_pick_existing"

        fun intentFor(
            context: android.content.Context,
            sourcePackage: String?,
            pickExisting: Boolean,
        ): Intent = Intent(context, ScanCaptureActivity::class.java)
            .putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
            .putExtra(EXTRA_PICK_EXISTING, pickExisting)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
