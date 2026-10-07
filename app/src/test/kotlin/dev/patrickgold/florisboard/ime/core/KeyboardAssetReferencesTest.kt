/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every name a subtype preset uses points at something that exists, and the popup registry and the popup
 * files agree.
 *
 * A popup mapping loads only through its registry entry: `LayoutManager` looks the name up there, and a
 * missing entry fails quietly into the default mapping, which has punctuation and nothing else. That is
 * how Slovenian lost č, š and ž on long press (its file was never registered, upstream FlorisBoard
 * included), how the Armenian alternative-phonetic popups were never reachable, and how a registered
 * `ko-KR` without a file sat in the editor's list doing nothing. None of it failed anywhere.
 */
class KeyboardAssetReferencesTest {

    private val assetsDir: File by lazy {
        // Run from the repo root or from app/, depending on how Gradle was invoked.
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val suffix = "src/main/assets/ime"
        while (dir != null) {
            File(dir, "app/$suffix").takeIf { it.isDirectory }?.let { return@lazy it }
            File(dir, suffix).takeIf { it.isDirectory }?.let { return@lazy it }
            dir = dir.parentFile
        }
        error("could not locate app/src/main/assets/ime")
    }

    private fun json(path: String) = Json.parseToJsonElement(File(assetsDir, path).readText()).jsonObject

    private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.content

    private val localization by lazy { json("keyboard/org.florisboard.localization/extension.json") }
    private val presets by lazy { localization["subtypePresets"]!!.jsonArray.map { it.jsonObject } }
    private val registeredPopups by lazy {
        localization["popupMappings"]!!.jsonArray.map { it.jsonObject.string("id")!! }.toSet()
    }

    @Test
    fun `every registered popup mapping has its file, and every file is registered`() {
        val files = File(assetsDir, "keyboard/org.florisboard.localization/popupMappings")
            .listFiles { f -> f.extension == "json" }!!
            .map { it.nameWithoutExtension }
            .toSet()
        assertEquals(emptySet(), registeredPopups - files - "default", "registered without a file")
        assertEquals(emptySet(), files - registeredPopups, "a file the loader can never reach")
    }

    @Test
    fun `every preset's popups, currency and layouts exist`() {
        val currencies = json("keyboard/org.florisboard.currencysets/extension.json")["currencySets"]!!.jsonArray
            .map { it.jsonObject.string("id")!! }.toSet()
        val layouts = json("keyboard/org.florisboard.layouts/extension.json")["layouts"]!!.jsonObject
            .mapValues { (_, list) -> list.jsonArray.map { it.jsonObject.string("id")!! }.toSet() }
        val broken = buildList {
            for (preset in presets) {
                val tag = preset.string("languageTag")
                preset.string("popupMapping")?.split(":")?.let { (ext, id) ->
                    if (ext == "org.florisboard.localization" && id !in registeredPopups) add("$tag popups $id")
                }
                preset.string("currencySet")?.split(":")?.let { (ext, id) ->
                    if (ext == "org.florisboard.currencysets" && id !in currencies) add("$tag currency $id")
                }
                for ((type, ref) in preset["preferred"]!!.jsonObject) {
                    val (ext, id) = ref.jsonPrimitive.content.split(":")
                    if (ext == "org.florisboard.layouts" && id !in layouts[type].orEmpty()) add("$tag $type $id")
                }
            }
        }
        assertEquals(emptyList(), broken)
    }
}
