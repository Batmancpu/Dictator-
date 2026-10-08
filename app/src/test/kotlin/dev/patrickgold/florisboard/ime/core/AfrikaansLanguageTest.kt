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

import dev.patrickgold.florisboard.dictate.DictateLanguages
import dev.patrickgold.florisboard.ime.nlp.latin.BigramCatalog
import dev.patrickgold.florisboard.ime.nlp.latin.GlideDictionaryCatalog
import dev.patrickgold.florisboard.ime.nlp.latin.TrigramCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Afrikaans is a keyboard language in every place a language has a piece (asked for by e-mail,
 * 2026-10-07). A language is a dozen separate entries — a preset, popups, a currency, three downloadable
 * word files, emoji names, the spell checker, dictation — and nothing fails when one of them is missing:
 * the keyboard just quietly does less. So the pieces are listed here, one assertion each.
 */
class AfrikaansLanguageTest {

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

    private fun JsonObject.string(key: String) = get(key)!!.jsonPrimitive.content

    private val localization by lazy { json("keyboard/org.florisboard.localization/extension.json") }

    private val preset: JsonObject by lazy {
        localization["subtypePresets"]!!.jsonArray.map { it.jsonObject }.single { it.string("languageTag") == "af-ZA" }
    }

    @Test
    fun `there is a preset, so adding Afrikaans is one tap`() {
        assertEquals("org.florisboard.layouts:qwerty", preset["preferred"]!!.jsonObject.string("characters"))
        assertEquals("org.florisboard.localization:af", preset.string("popupMapping"))
        assertEquals("org.florisboard.currencysets:south_african_rand", preset.string("currencySet"))
    }

    @Test
    fun `its popups are registered and put the letters it writes under the finger`() {
        val ids = localization["popupMappings"]!!.jsonArray.map { it.jsonObject.string("id") }
        assertTrue("af" in ids, "the af mapping is not registered, so the preset would point at nothing")
        val keys = json("keyboard/org.florisboard.localization/popupMappings/af.json")["all"]!!.jsonObject
        // Letter keys only: `~right` is the punctuation key, whose main is the comma in every language.
        val mains = keys.filterKeys { !it.startsWith("~") }
            .mapNotNull { (key, v) -> v.jsonObject["main"]?.jsonObject?.string("label")?.let { key to it } }
            .toMap()
        assertEquals(mapOf("e" to "ê", "i" to "ï", "n" to "'n", "o" to "ô", "u" to "û"), mains)
        val onE = keys["e"]!!.jsonObject["relevant"]!!.jsonArray.map { it.jsonObject.string("label") }
        assertTrue("ë" in onE, "ë (ideë, geëet) must be on e as well")
    }

    @Test
    fun `the rand is its currency`() {
        val sets = json("keyboard/org.florisboard.currencysets/extension.json")["currencySets"]!!.jsonArray
            .map { it.jsonObject }
        val rand = sets.single { it.string("id") == "south_african_rand" }
        assertEquals("R", rand["slots"]!!.jsonArray.first().jsonObject.string("label"))
    }

    @Test
    fun `word list and both context tables can be downloaded`() {
        assertNotNull(GlideDictionaryCatalog.forLang("af"), "no word list: no suggestions, autocorrect or glide")
        assertTrue(BigramCatalog.all.any { it.lang == "af" }, "no word-pair table: no next-word prediction")
        assertTrue(TrigramCatalog.all.any { it.lang == "af" }, "no word-triple table: prediction sees one word back")
    }

    @Test
    fun `emoji search and suggestions speak it`() {
        val annotations = File(assetsDir, "media/emoji/annotations/af.txt")
        assertTrue(annotations.isFile, "no af emoji names: searching \"hart\" would find nothing")
        assertTrue(annotations.readLines().any { it.startsWith("❤️;") && "hartjie" in it })
    }

    @Test
    fun `the spell checker and dictation know it too`() {
        val spellchecker = File(assetsDir, "../../res/xml/spellchecker.xml").readText()
        assertTrue("android:subtypeLocale=\"af\"" in spellchecker)
        assertTrue(DictateLanguages.all.any { it.code == "af" })
    }
}
