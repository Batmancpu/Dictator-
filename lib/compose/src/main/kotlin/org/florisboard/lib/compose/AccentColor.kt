/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.florisboard.lib.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * What to draw **on** a surface painted in this colour, so it stays readable.
 *
 * The accent is the user's own choice and can be anything from near-black to a pale yellow, so nothing
 * that sits on top of it may assume a fixed colour. This is the same rule Snygg applies to its own
 * backgrounds, hoisted out of the two places that had each written it by hand.
 *
 * It exists because of the alternative it replaces. The cheap way to make something "look accent-ish"
 * is to paint the accent at low opacity — `accent.copy(alpha = 0.3f)` — which over a dark keyboard
 * composites to a muddy, desaturated version of itself. It reads as "some dark colour", not as *the*
 * accent, and it cannot be told apart from a disabled control. Where a surface is meant to *be* the
 * accent, paint it at full opacity and pick the content colour with this.
 *
 * Low-opacity accent is still right for things drawn **over** content that must remain visible through
 * them — a highlight wash behind text, the region overlays on a scanned photo, a waveform's envelope.
 * The distinction is whether the colour is the surface or a veil over one.
 */
fun Color.onAccent(): Color = if (luminance() > 0.5f) Color.Black else Color.White
