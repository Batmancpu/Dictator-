/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.dictate

import androidx.compose.runtime.Composable
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.dictate.DictateLanguages
import dev.patrickgold.florisboard.dictate.provider.LocalModelSpec
import kotlin.math.roundToInt
import org.florisboard.lib.compose.pluralsRes

/**
 * The line under a model's name, assembled from what the catalog knows instead of written out once per
 * model.
 *
 * Every entry used to carry a hand-written English sentence — "25 European languages · ~670 MB" — which
 * no locale ever translated, so the whole picker was English on a Japanese phone. Built from
 * [LocalModelSpec.languages] the language names come from the system in the reader's own language, the
 * size comes from the bytes that are actually downloaded, and adding a model costs no new string in any
 * of the twenty translated locales.
 */

/**
 * Up to this many languages are named one by one; past it the line says how many there are instead.
 *
 * Five rather than three: Canary speaks four and SenseVoice five, and both of those read perfectly as a
 * list today. Twenty-five and ninety-nine were never going to be spelled out.
 */
private const val NAMED_LANGUAGE_LIMIT = 5

/** "German", "English, German, French, Spanish", "99 languages". */
@Composable
fun modelLanguagesLabel(spec: LocalModelSpec): String =
    if (spec.languages.size <= NAMED_LANGUAGE_LIMIT) {
        spec.languages.joinToString(", ") { DictateLanguages.displayNameOf(it) }
    } else {
        pluralsRes(
            R.plurals.dictate__local_model_languages,
            spec.languages.size,
            "count" to spec.languages.size,
        )
    }

/**
 * "137 MB" — whole megabytes of a million bytes each, the way a mobile plan is written, matching
 * `LanguageDataDownload.formatMegabytes` and its reasoning. Whole rather than one decimal because these
 * run from 71 to 670 MB and the tenth of a megabyte is noise at that scale.
 */
fun modelSizeLabel(bytes: Long): String = "${(bytes / 1_000_000.0).roundToInt()} MB"

/** What a model costs to install, as the picker shows it before it is installed. */
@Composable
fun modelLanguagesAndSize(spec: LocalModelSpec): String =
    "${modelLanguagesLabel(spec)} · ${modelSizeLabel(spec.totalBytes)}"
