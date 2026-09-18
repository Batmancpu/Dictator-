/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import dev.patrickgold.florisboard.dictate.audio.WaveformTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformTraceTest {
    private val period = 50_000_000L

    private fun trace(capacity: Int = 4) = WaveformTrace(capacity, period)

    @Test
    fun `an empty trace is silent and still`() {
        val trace = trace()

        assertEquals(0f, trace.peakAt(0))
        assertEquals(0f, trace.peakAt(3))
        assertEquals(0f, trace.scrollFraction(period * 10))
    }

    @Test
    fun `the newest sample is index zero and history runs backwards`() {
        val trace = trace()
        trace.push(0.1f, period)
        trace.push(0.2f, period * 2)
        trace.push(0.3f, period * 3)

        assertEquals(0.3f, trace.peakAt(0))
        assertEquals(0.2f, trace.peakAt(1))
        assertEquals(0.1f, trace.peakAt(2))
        // Never written: silence, so a young recording starts flat instead of undefined.
        assertEquals(0f, trace.peakAt(3))
    }

    @Test
    fun `wrapping past the capacity drops the oldest bars only`() {
        val trace = trace(capacity = 4)
        repeat(6) { trace.push((it + 1) / 10f, period * (it + 1L)) }

        // Pushed 0.1 … 0.6 into four slots: the last four survive, newest first.
        assertEquals(0.6f, trace.peakAt(0))
        assertEquals(0.5f, trace.peakAt(1))
        assertEquals(0.4f, trace.peakAt(2))
        assertEquals(0.3f, trace.peakAt(3))
        // Out of range reads as silence rather than wrapping back round to a bar still in the buffer.
        assertEquals(0f, trace.peakAt(4))
    }

    @Test
    fun `repeated silence keeps advancing the trace`() {
        val trace = trace(capacity = 4)
        trace.push(0.7f, period)
        repeat(3) { trace.push(0f, period * (it + 2L)) }

        // The loud bar has to have been carried three places back by three quiet windows — this is the
        // whole point of the mode, and of feeding it an event stream rather than a conflating state.
        assertEquals(0.7f, trace.peakAt(3))
        assertEquals(0f, trace.peakAt(0))
    }

    @Test
    fun `peaks are clamped into the drawable range`() {
        val trace = trace()
        trace.push(-4f, period)
        trace.push(9f, period * 2)

        assertEquals(1f, trace.peakAt(0))
        assertEquals(0f, trace.peakAt(1))
    }

    @Test
    fun `the scroll fraction crosses one bar per sample period`() {
        val trace = trace()
        trace.push(0.5f, 1_000_000_000L)

        assertEquals(0f, trace.scrollFraction(1_000_000_000L))
        assertEquals(0.5f, trace.scrollFraction(1_000_000_000L + period / 2))
        assertEquals(1f, trace.scrollFraction(1_000_000_000L + period))
    }

    @Test
    fun `a stalled sampler parks at the next bar instead of sliding away`() {
        val trace = trace()
        trace.push(0.5f, 1_000_000_000L)

        // Paused for two seconds: the row must not have drifted forty bars to the left.
        assertEquals(1f, trace.scrollFraction(3_000_000_000L))
    }

    @Test
    fun `a frame timed before the sample does not scroll backwards`() {
        val trace = trace()
        trace.push(0.5f, 2_000_000_000L)

        // The draw can run on a frame whose timestamp predates the sample that arrived during it.
        assertEquals(0f, trace.scrollFraction(1_999_000_000L))
    }

    @Test
    fun `reset empties the trace`() {
        val trace = trace()
        repeat(4) { trace.push(0.9f, period * (it + 1L)) }
        trace.reset()

        repeat(4) { assertEquals(0f, trace.peakAt(it)) }
        assertEquals(0f, trace.scrollFraction(period * 100))
        // And it still takes samples afterwards.
        trace.push(0.4f, period * 101)
        assertTrue(trace.peakAt(0) == 0.4f)
    }
}
