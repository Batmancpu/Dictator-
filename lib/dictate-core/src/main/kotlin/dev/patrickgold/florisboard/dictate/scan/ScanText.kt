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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the recogniser found, in a form that has nothing Android in it (issue #390).
 *
 * The one design decision worth knowing: **coordinates are normalised to 0..1 of the source image**,
 * not pixels. The panel draws the photo scaled to fit and then lets the user zoom into it, so a pixel
 * coordinate would have to be re-derived on every layout pass and would silently rot the moment the
 * display bitmap is downscaled (which it is — the recogniser keeps a 2048 px bitmap and the panel a
 * 1280 px one). Normalised, the same numbers are correct for both and for whatever comes next.
 */
data class ScanLine(
    val text: String,
    /** Index of the [ScanText] block this line belongs to — what decides space vs. newline when joined. */
    val blockIndex: Int,
    /**
     * The line's four corners, clockwise from the top-left, as `x0, y0, x1, y1, x2, y2, x3, y3`
     * normalised to 0..1. A quad rather than a rectangle because text photographed at an angle draws
     * an absurdly fat axis-aligned box, and the box is what the user aims at.
     */
    val quad: List<Float>,
) {
    val left: Float get() = minOf(quad[0], quad[2], quad[4], quad[6])
    val top: Float get() = minOf(quad[1], quad[3], quad[5], quad[7])
    val right: Float get() = maxOf(quad[0], quad[2], quad[4], quad[6])
    val bottom: Float get() = maxOf(quad[1], quad[3], quad[5], quad[7])

    /** Whether [x]/[y] (normalised image coordinates) fall inside this line's bounding box. */
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    /** Distance from [x]/[y] to the bounding box, 0 inside it. Used to forgive a near miss. */
    fun distanceTo(x: Float, y: Float): Float {
        val dx = maxOf(left - x, 0f, x - right)
        val dy = maxOf(top - y, 0f, y - bottom)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    companion object {
        /** Convenience for tests and for the axis-aligned case. */
        fun ofRect(text: String, blockIndex: Int, left: Float, top: Float, right: Float, bottom: Float) =
            ScanLine(text, blockIndex, listOf(left, top, right, top, right, bottom, left, bottom))
    }
}

/** Every recognised line of one photo, already in reading order (top to bottom, then left to right). */
data class ScanText(val lines: List<ScanLine>) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /** The bounding box of everything recognised, or null when nothing was. The panel opens on this. */
    fun bounds(): FloatArray? {
        if (lines.isEmpty()) return null
        return floatArrayOf(
            lines.minOf { it.left },
            lines.minOf { it.top },
            lines.maxOf { it.right },
            lines.maxOf { it.bottom },
        )
    }

    companion object {
        val Empty = ScanText(emptyList())
    }
}

/** How a capture ended. Written by the trampoline activity, read by the keyboard. */
@Serializable
enum class ScanStatus {
    /** A photo was taken (or picked) and is waiting to be read. */
    CAPTURED,

    /** The user backed out of the camera or the picker. Nothing to read, but the panel still reopens. */
    CANCELLED,

    /** Something went wrong before a usable image existed. */
    FAILED,
}

/**
 * The handoff note the trampoline activity leaves next to the photo.
 *
 * Every field has a default, and [Json] coerces input values, so a note written by an older version of
 * the app and read by a newer one degrades to `FAILED` instead of throwing — the update can happen
 * between the photo and the keyboard coming back.
 */
@Serializable
data class ScanResult(
    val status: ScanStatus = ScanStatus.FAILED,
    /** The package of the field the scan was started from. See [ScanHandoffRules]. */
    val sourcePackage: String? = null,
    val capturedAtMs: Long = 0L,
) {
    companion object {
        val Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

        const val FILE_NAME = "result.json"
    }
}
