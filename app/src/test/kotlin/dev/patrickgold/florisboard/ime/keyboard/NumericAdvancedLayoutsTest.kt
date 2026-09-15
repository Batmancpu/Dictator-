/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.keyboard

import dev.patrickgold.florisboard.ime.text.key.KeyCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The way off the number pad (issue #388).
 *
 * The pad is a latched keyboard mode: whatever brings you to it — a numeric field, a keyboard-mode
 * cycle, or the Smartbar action added for #388 — leaves you there until a key on the pad itself says
 * otherwise. That key is `view_characters`, and it is data, not code: it lives in each layout's JSON,
 * so a layout shipped without it would strand whoever opened it with no way back but closing the
 * field. Reading the assets off disk is the only place that can be checked, for the same reason
 * [dev.patrickgold.florisboard.ime.popup.PopupMappingsTest] does it.
 */
class NumericAdvancedLayoutsTest {

    private val layoutsDir: File by lazy {
        // Gradle runs unit tests from the module directory, but that has moved before; walk up until
        // the assets turn up rather than depend on it.
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val suffix = "src/main/assets/ime/keyboard/org.florisboard.layouts/layouts/numericAdvanced"
        while (dir != null) {
            File(dir, "app/$suffix").takeIf { it.isDirectory }?.let { return@lazy it }
            File(dir, suffix).takeIf { it.isDirectory }?.let { return@lazy it }
            dir = dir.parentFile
        }
        error("numericAdvanced layouts not found from ${System.getProperty("user.dir")}")
    }

    private fun codesIn(file: File): List<Int> {
        return Json.parseToJsonElement(file.readText()).jsonArray
            .flatMap { row -> row.jsonArray }
            .mapNotNull { key -> key.jsonObject["code"]?.jsonPrimitive?.int }
    }

    @Test
    fun `every shipped number pad offers a way back to the letters`() {
        val layouts = layoutsDir.listFiles { file -> file.extension == "json" }.orEmpty()
        assertTrue(layouts.isNotEmpty(), "no numericAdvanced layouts found in $layoutsDir")
        for (layout in layouts) {
            assertTrue(
                KeyCode.VIEW_CHARACTERS in codesIn(layout),
                "${layout.name} has no view_characters key — it would strand the user on the pad",
            )
        }
    }
}
