/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.audio.WaveformTrace

/**
 * The live recording waveform (issue #371): the last couple of seconds of microphone peaks as a row of
 * mirrored bars that scrolls leftwards, silence drawn as a flat run of dots.
 *
 * Chosen over a bouncing equaliser because it is the only shape that answers the question people
 * actually have their finger over the stop button for — *has it been quiet for a moment yet?* An
 * equaliser, like the dot, only ever shows the current instant.
 *
 * Two things make it smooth rather than steppy, which the request was explicit about:
 *
 *  - It is fed [DictateController.audioPeak], not `audioLevel`. The dot's smoothing would carry every
 *    loud window half a second into the quiet ones and blur exactly the edge being looked for.
 *  - The bars are not moved a whole place twenty times a second. Each sample only *appends*; the
 *    position of the whole row comes from the display clock via [WaveformTrace.scrollFraction], so the
 *    motion runs at the refresh rate and a new bar slides in from under the right edge.
 *
 * Only the draw phase re-runs per frame: the trace is a plain object, and the single piece of state the
 * frame loop writes is read inside the draw lambda.
 */
@Composable
internal fun RecordingWaveform(
    color: Color,
    paused: Boolean,
    frozen: Boolean,
    modifier: Modifier = Modifier,
) {
    val trace = remember { WaveformTrace(samplePeriodNanos = DictateController.AUDIO_LEVEL_SAMPLE_MS * 1_000_000L) }

    // The one piece of observable state, read in the draw lambda below and written from both drivers: the
    // frame loop while animating, and the sample collector otherwise. One state instead of two keeps the
    // reduced-motion case (redraw once per sample, no interpolation) on the same code path as the
    // animated one, where the sample's own write is simply overtaken by the next frame.
    val redrawNanos = remember { mutableLongStateOf(0L) }

    // Nothing is being captured while paused, and a discarded recording is only still on screen for the
    // throw animation — in both cases the row holds exactly where it was rather than scrolling on into
    // silence it never heard.
    val running = !paused && !frozen
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        DictateController.audioPeak.collect { peak ->
            val now = System.nanoTime()
            trace.push(peak, now)
            redrawNanos.longValue = now
        }
    }

    // With animations switched off system-wide, the row still advances a bar per sample — it just does
    // not interpolate between them. Same choice the floating button's surfaces make.
    val animate = running && ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        while (true) {
            withFrameNanos { redrawNanos.longValue = it }
        }
    }

    val barColor = if (paused) color.copy(alpha = PAUSED_ALPHA) else color
    Canvas(modifier = modifier.clipToBounds()) {
        val barWidth = BarWidth.toPx()
        val step = barWidth + BarGap.toPx()
        // Squeezed to a stub — a narrow screen in long-form, or the push-to-talk hint taking the row —
        // a handful of bars would read as debris rather than as a waveform, so draw nothing at all.
        if (size.width < step * MIN_BARS) return@Canvas

        val fraction = trace.scrollFraction(redrawNanos.longValue)
        val midY = size.height / 2f
        // Silence is a dot rather than a gap: a row of dots is recognisably "the microphone is open and
        // hearing nothing", while a gap looks like the indicator has stopped working.
        val minHeight = barWidth
        val span = (size.height - minHeight).coerceAtLeast(0f)

        var i = 0
        while (i < WaveformTrace.DEFAULT_CAPACITY) {
            // The newest bar starts just outside the right edge and slides in over its own 50 ms, so no
            // bar ever pops into existence at full height.
            val centerX = size.width + barWidth / 2f - (i + fraction) * step
            if (centerX + barWidth / 2f <= 0f) break
            val height = minHeight + trace.peakAt(i) * span
            drawRoundRect(
                color = barColor,
                topLeft = Offset(centerX - barWidth / 2f, midY - height / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
            i++
        }
    }
}

/**
 * 2 dp of bar and 1.5 dp of air: about 34 bars, so roughly 1.7 s of history, in the ~120 dp the Smartbar
 * has left over on a 360 dp phone — and proportionally more wherever there is more room. Thinner bars
 * would buy more seconds than anyone reads back and start to shimmer at a 20 Hz scroll.
 */
private val BarWidth = 2.dp
private val BarGap = 1.5.dp

/** Below this many bars the waveform is not a waveform any more; see the guard in the draw. */
private const val MIN_BARS = 8

/** The same dimming the dot uses while paused, so the two modes read as the same state. */
private const val PAUSED_ALPHA = 0.4f
