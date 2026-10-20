/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.overlay

/**
 * What the floating button's live preview shows of a streaming transcript (discussion #453).
 *
 * The preview is a caption, not the text: the words land in the field on stop, in one verified commit, as
 * every floating-button dictation does (#357). So it shows only the end of what was said — the part that
 * is still changing — and lets the beginning go, the way a subtitle does.
 */
internal object BubbleLivePreview {

    /** Roughly three lines of the preview at its widest; past that the oldest words give way. */
    const val MAX_CHARS = 120

    /**
     * The last [maxChars] characters of [text] or fewer, starting at a word and marked with an ellipsis
     * when something was cut. A single word longer than the whole budget is cut inside it rather than
     * shown as nothing.
     */
    fun tail(text: String, maxChars: Int = MAX_CHARS): String {
        val trimmed = text.trim().replace(Regex("\\s+"), " ")
        if (trimmed.length <= maxChars) return trimmed
        val cut = trimmed.substring(trimmed.length - (maxChars - 1))
        val wordStart = cut.indexOf(' ')
        val fromWord = if (wordStart in 0 until cut.length - 1) cut.substring(wordStart + 1) else cut
        return "…$fromWord"
    }

    /**
     * [shown] with its first word let go — the next step when it still takes more lines than the card has
     * (the controller measures; this only cuts). Null once a single word is left: cutting further would show
     * nothing at all.
     */
    fun dropFirstWord(shown: String): String? {
        val rest = shown.removePrefix("…")
        val space = rest.indexOf(' ')
        if (space < 0 || space == rest.length - 1) return null
        return "…" + rest.substring(space + 1)
    }
}
