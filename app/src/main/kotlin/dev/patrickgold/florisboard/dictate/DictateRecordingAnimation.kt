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

/**
 * How the recording indicator moves while a dictation is running (issue #238) — the Smartbar's red dot
 * and, in the classic layout, the big record button.
 *
 * Movement in the corner of the eye is distracting for exactly as long as one is trying to concentrate
 * on speaking, so this is a user choice rather than a fixed style.
 *
 *  - [STATIC]: no movement at all. The dot stays a solid red circle, the record button keeps its size.
 *  - [PULSE]: a steady pulse at a fixed rate, as the pre-rewrite app did.
 *  - [LEVEL]: size and opacity follow the live microphone level, so the indicator doubles as feedback
 *    that the mic is actually hearing something.
 *  - [WAVE]: the dot gives way to a scrolling waveform of the last two seconds (issue #371). A single
 *    dot is too small to read "I have stopped speaking" off, which is the moment one wants to press
 *    stop; a run of flat dots trailing the last bar says it at a glance. Only the Smartbar has room for
 *    it — the classic layout's record button treats this as [LEVEL].
 */
enum class DictateRecordingAnimation {
    STATIC,
    PULSE,
    LEVEL,
    WAVE;

    /**
     * Whether this mode is driven by the microphone at all. Surfaces with no room for a waveform — the
     * classic layout's record button — use it to give [WAVE] the same voice-reactive treatment as
     * [LEVEL] rather than leaving the one user who picked it with a dead indicator.
     */
    val followsVoice: Boolean
        get() = this == LEVEL || this == WAVE
}
