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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The only file in the app that knows ML Kit exists (issue #390).
 *
 * Everything it hands out is plain data with normalised coordinates, so the panel, the selection rules
 * and their tests never have to import a vision library — and so replacing the recogniser one day is a
 * change to one file rather than to a feature.
 *
 * The model is the **bundled** one. The Play-services-delivered variant is a megabyte smaller in the
 * download but fetches its model over the network on first use, and #390's promise is that nothing about
 * this feature talks to a network. Latin script only, which is why the panel says so out loud.
 */
object ScanRecognizer {

    /**
     * A 12-megapixel photo decoded whole is ~48 MB of bitmap in a process that also holds a keyboard.
     * 2048 px on the long edge keeps small print legible — it is roughly what a page of A4 needs for
     * 8 pt text — at a quarter of that.
     */
    private const val OCR_MAX_EDGE = 2048

    /** What the panel draws. It is shown inside a keyboard, so 1280 px is already generous at 8× zoom. */
    private const val DISPLAY_MAX_EDGE = 1280

    /** Everything a finished scan hands back: what to draw, and what was read off it. */
    data class Recognised(val bitmap: Bitmap, val scan: ScanText)

    /**
     * Reads [file], **deletes nothing** — the caller owns that, and does it the moment this returns —
     * and hands back the display bitmap plus the recognised lines.
     */
    suspend fun read(file: File): Recognised? {
        val source = withContext(Dispatchers.IO) { decode(file) } ?: return null
        flogInfo { "scan: decoded ${source.width}x${source.height}" }
        val scan = try {
            recognise(source)
        } catch (e: Exception) {
            source.recycle()
            throw e
        }
        val display = withContext(Dispatchers.Default) { scaleForDisplay(source) }
        if (display !== source) source.recycle()
        return Recognised(display, scan)
    }

    /**
     * Decodes at a bounded size and puts the photo the right way up. The rotation is the part that is
     * easy to forget and impossible to miss: the camera writes it into EXIF rather than into the pixels,
     * so a photo taken in portrait recognises as a column of nonsense without this.
     */
    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // Worth naming rather than returning a bare null: every way this fails — the file gone, the
            // file empty, a format the platform will not decode — looks identical to the user, who is
            // told only that the photo could not be read.
            android.util.Log.e(
                "DictateScan",
                "nothing to decode in ${file.name} " +
                    "(exists=${file.exists()}, ${file.length()} bytes, type=${bounds.outMimeType})",
            )
            return null
        }

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= OCR_MAX_EDGE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

        val degrees = runCatching {
            when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees == 0f) return decoded

        val rotated = Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(degrees) }, true,
        )
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private fun scaleForDisplay(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= DISPLAY_MAX_EDGE) return source
        val factor = DISPLAY_MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Runs the recogniser and flattens its result into [ScanText].
     *
     * ML Kit answers on a [com.google.android.gms.tasks.Task], which is bridged here by hand rather than
     * by pulling in `kotlinx-coroutines-play-services` for a single call site. The recogniser is closed
     * on both paths: it holds native model memory, and it is living in the keyboard's process.
     */
    private suspend fun recognise(bitmap: Bitmap): ScanText {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
            return flatten(result, bitmap.width.toFloat(), bitmap.height.toFloat())
        } finally {
            runCatching { recognizer.close() }
        }
    }

    /**
     * Blocks into reading order, then their lines, with every coordinate divided by the image size.
     *
     * The sort is here and not in the model because it needs the boxes, and keeping the boxes out of
     * `:lib:dictate-core` is the whole point of this file. Blocks come out of ML Kit in a useful order
     * most of the time and in a surprising one often enough to matter — a caption printed beside a
     * paragraph, for instance — and the order decides what a multi-line selection reads like.
     */
    private fun flatten(
        result: com.google.mlkit.vision.text.Text,
        width: Float,
        height: Float,
    ): ScanText {
        if (width <= 0f || height <= 0f) return ScanText.Empty
        val lines = mutableListOf<ScanLine>()
        result.textBlocks
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .forEachIndexed { blockIndex, block ->
                for (line in block.lines) {
                    val text = line.text.trim()
                    if (text.isEmpty()) continue
                    val quad = quadOf(line, width, height) ?: continue
                    lines += ScanLine(text, blockIndex, quad)
                }
            }
        return ScanText(lines)
    }

    /**
     * The line's four corners, normalised. Corners rather than the bounding box because text
     * photographed at an angle draws an axis-aligned box far bigger than the words in it, and the box is
     * what the user is aiming at. Falls back to the box when ML Kit gives no corners.
     */
    private fun quadOf(
        line: com.google.mlkit.vision.text.Text.Line,
        width: Float,
        height: Float,
    ): List<Float>? {
        val corners = line.cornerPoints
        if (corners != null && corners.size == 4) {
            return corners.flatMap { listOf(it.x / width, it.y / height) }
        }
        val box = line.boundingBox ?: return null
        val l = box.left / width
        val t = box.top / height
        val r = box.right / width
        val b = box.bottom / height
        return listOf(l, t, r, t, r, b, l, b)
    }
}
