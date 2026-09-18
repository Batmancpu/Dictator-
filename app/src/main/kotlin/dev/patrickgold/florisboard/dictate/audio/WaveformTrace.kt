/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.audio

/**
 * The scrolling history behind the recording waveform (issue #371): a ring buffer of microphone peaks,
 * newest first, plus the sub-sample offset that lets the renderer slide them instead of stepping.
 *
 * The renderer draws one bar per 50 ms window and needs two things from this class. [peakAt] gives the
 * bar heights — index 0 is the window that just ended, index 1 the one before it, and every index that
 * has not been written yet reads 0, so a fresh recording starts as a flat row rather than growing out of
 * nothing. [scrollFraction] gives how far *through* the current window the display clock is, which is
 * what turns twenty jumps a second into continuous motion.
 *
 * Deliberately free of Android and of Compose so the arithmetic can be tested without either.
 */
internal class WaveformTrace(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val samplePeriodNanos: Long = DEFAULT_SAMPLE_PERIOD_NANOS,
) {
    private val peaks = FloatArray(capacity)

    /** Where the *newest* sample sits in [peaks]; walking backwards from here walks back in time. */
    private var newest = -1
    private var lastSampleNanos = 0L

    /** Records one finished 50 ms window. [atNanos] must come from the same clock as [scrollFraction]. */
    fun push(peak: Float, atNanos: Long) {
        newest = if (newest < 0) 0 else (newest + 1) % capacity
        peaks[newest] = peak.coerceIn(0f, 1f)
        lastSampleNanos = atNanos
    }

    /**
     * The peak [index] windows back in time, or 0 for a window that never happened — silence is the
     * honest thing to draw for a recording that has not been running long enough to fill the bar.
     */
    fun peakAt(index: Int): Float {
        if (newest < 0 || index < 0 || index >= capacity) return 0f
        return peaks[((newest - index) % capacity + capacity) % capacity]
    }

    /**
     * How far the clock has advanced into the window after the newest sample, 0..1, used to offset the
     * whole row by a fraction of a bar.
     *
     * Clamped at 1 rather than allowed to run on: if sampling stalls — the recording paused, the loop
     * descheduled — the row parks at the next bar boundary instead of sliding away into an empty gap,
     * and resumes from there when samples come back.
     */
    fun scrollFraction(nowNanos: Long): Float {
        if (newest < 0) return 0f
        val elapsed = nowNanos - lastSampleNanos
        if (elapsed <= 0L) return 0f
        return (elapsed.toFloat() / samplePeriodNanos).coerceAtMost(1f)
    }

    /** Back to an empty trace: every bar reads silence and nothing is scrolling. */
    fun reset() {
        peaks.fill(0f)
        newest = -1
        lastSampleNanos = 0L
    }

    companion object {
        /**
         * Bars kept in hand — 12.8 s of history, of which the renderer draws however many fit.
         *
         * Generous on purpose: how much room the waveform gets is not known until layout (it depends on
         * the screen, the orientation, the language chip and whether long-form is running), and a buffer
         * too small for a wide tablet bar would leave its left half permanently flat. A kilobyte of
         * floats is the cheaper answer than resizing from inside a measure pass.
         */
        const val DEFAULT_CAPACITY = 256

        /** Matches `DictateController.AUDIO_LEVEL_SAMPLE_MS`; the renderer passes the real value. */
        private const val DEFAULT_SAMPLE_PERIOD_NANOS = 50_000_000L
    }
}
