/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.translate

/**
 * Translates a text line by line, and each line only once (issue #433).
 *
 * The translate bar can hold line breaks now, and a line break is the one thing the translation must
 * keep exactly where the user put it — a message's paragraphs are not the model's to rearrange. So each
 * line goes to the engine on its own and the results are joined with the same breaks; blank lines stay
 * as they are and cost nothing.
 *
 * The bar asks again after every keystroke, with the whole text, while only the line being typed in has
 * changed. The lines already translated are remembered, so a keystroke in the fourth line costs one
 * line's translation, not four.
 */
class LineTranslator(private val capacity: Int = DEFAULT_CAPACITY) {
    /** Translations by source line, for [route] only; the least recently used goes once [capacity] is full. */
    private val known = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity
    }

    private var route: String? = null

    /**
     * [text] translated on [route] (any key naming the language pair and its models). [engine] gets the
     * lines not translated before, in order and each once, and returns their translations in the same order.
     */
    fun translate(route: String, text: String, engine: (List<String>) -> List<String>): String {
        if (route != this.route) {
            known.clear()
            this.route = route
        }
        val lines = text.split('\n')
        // Everything this text needs is collected here before anything new is remembered: a text with more
        // new lines than [capacity] would otherwise evict its own lines before they are read back.
        val translations = HashMap<String, String>()
        for (line in lines) {
            if (line.isNotBlank()) known[line]?.let { translations[line] = it }
        }
        val missing = lines.filter { it.isNotBlank() && it !in translations }.distinct()
        if (missing.isNotEmpty()) {
            val translated = engine(missing)
            require(translated.size == missing.size) { "engine answered ${translated.size} of ${missing.size} lines" }
            missing.zip(translated).forEach { (line, translation) ->
                translations[line] = translation
                known[line] = translation
            }
        }
        return lines.joinToString("\n") { line -> if (line.isBlank()) line else translations.getValue(line) }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
