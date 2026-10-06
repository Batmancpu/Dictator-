/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.prompts

/**
 * The literal text of a snippet prompt — everything between the square brackets — or `null` if the
 * prompt is an instruction for the AI model instead.
 *
 * A prompt whose text is wrapped in `[…]` is inserted verbatim, with no network call: that is the
 * snippet mechanism the prompt chips, the typed triggers (issue #283), the strip icon and the automatic
 * chain on the phone and the watch all share.
 */
fun snippetBodyOf(raw: String?): String? {
    val text = raw.orEmpty()
    return if (text.length >= 2 && text.startsWith("[") && text.endsWith("]")) {
        text.substring(1, text.length - 1)
    } else {
        null
    }
}

/**
 * Whether a prompt works on text — the selection, or the dictation it runs on — given what was stored
 * for it.
 *
 * An automatic prompt always does. It runs on a dictation, and that is the only text it has: sent
 * without it, the model answers the bare instruction ("I'm ready to rewrite your text, please send
 * it"), and that answer replaced every dictation. The editor locks the switch on for such a prompt;
 * this also covers prompts saved before it did, imports and library entries.
 *
 * A snippet never does, because it is written as it stands.
 */
fun promptRequiresSelection(prompt: String?, requiresSelection: Boolean, autoApply: Boolean): Boolean = when {
    snippetBodyOf(prompt) != null -> false
    autoApply -> true
    else -> requiresSelection
}
