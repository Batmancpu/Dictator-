/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.localization

import androidx.compose.runtime.saveable.SaverScope
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.core.SubtypeNlpProviderMap
import dev.patrickgold.florisboard.ime.keyboard.extCoreCurrencySet
import dev.patrickgold.florisboard.ime.keyboard.extCoreLayout
import dev.patrickgold.florisboard.ime.nlp.han.HanShapeBasedLanguageProvider
import dev.patrickgold.florisboard.lib.FlorisLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Picking a language in the subtype editor fills in everything else (e-mail, 2026-10-07: "How do I add
 * Afrikaans … without having to select 15 mysterious drop-down boxes?").
 */
class SubtypeEditorStateTest {

    private fun newEditor(language: String) = SubtypeEditorState(null).apply {
        primaryLocale.value = FlorisLocale.fromTag(language)
    }

    @Test
    fun `a language with no preset can be saved without touching another field`() {
        val editor = newEditor("nl")
        assertTrue(editor.toSubtype().isFailure, "before the fill, the editor refuses to save")
        editor.fillUnsetFrom(Subtype.DEFAULT)
        val subtype = editor.toSubtype().getOrThrow()
        assertEquals(extCoreLayout("qwerty"), subtype.layoutMap.characters)
        assertEquals(extCoreLayout("western"), subtype.layoutMap.symbols)
        assertEquals(extCoreCurrencySet("dollar"), subtype.currencySet)
    }

    @Test
    fun `what the user already chose stays`() {
        val editor = newEditor("af-ZA")
        editor.layoutMap.value = editor.layoutMap.value.copy(characters = extCoreLayout("azerty"))
        editor.currencySet.value = extCoreCurrencySet("euro")
        editor.fillUnsetFrom(Subtype.DEFAULT)
        val subtype = editor.toSubtype().getOrThrow()
        assertEquals(extCoreLayout("azerty"), subtype.layoutMap.characters)
        assertEquals(extCoreCurrencySet("euro"), subtype.currencySet)
        assertEquals(extCoreLayout("western"), subtype.layoutMap.symbols, "the rest is still filled")
    }

    @Test
    fun `changing the language replaces what the editor filled, not what the user chose`() {
        val afrikaans = Subtype.DEFAULT.copy(currencySet = extCoreCurrencySet("south_african_rand"))
        val editor = newEditor("nl")
        editor.fillUnsetFrom(Subtype.DEFAULT)
        editor.layoutMap.value = editor.layoutMap.value.copy(symbols = extCoreLayout("eastern"))
        editor.primaryLocale.value = FlorisLocale.fromTag("af-ZA")
        editor.fillUnsetFrom(afrikaans)
        val subtype = editor.toSubtype().getOrThrow()
        assertEquals(extCoreCurrencySet("south_african_rand"), subtype.currencySet, "Dutch's dollar must give way")
        assertEquals(extCoreLayout("eastern"), subtype.layoutMap.symbols, "the user's own pick stays")
    }

    @Test
    fun `what the editor filled survives the trip to the language picker`() {
        // Opening the picker disposes the editor and restores it from its Saver. Found on the emulator:
        // without lastFill in the saved state, Dutch-then-Afrikaans kept Dutch's dollar.
        val editor = newEditor("nl")
        editor.fillUnsetFrom(Subtype.DEFAULT)
        val saved = with(SubtypeEditorState.Saver) { SaverScope { true }.save(editor) }!!
        val restored = SubtypeEditorState.Saver.restore(saved)!!
        restored.primaryLocale.value = FlorisLocale.fromTag("af-ZA")
        restored.fillUnsetFrom(Subtype.DEFAULT.copy(currencySet = extCoreCurrencySet("south_african_rand")))
        assertEquals(extCoreCurrencySet("south_african_rand"), restored.currencySet.value)
    }

    @Test
    fun `a preset's own provider arrives, a provider the user picked does not move`() {
        val han = SubtypeNlpProviderMap(
            spelling = HanShapeBasedLanguageProvider.ProviderId,
            suggestion = HanShapeBasedLanguageProvider.ProviderId,
        )
        val preset = Subtype.DEFAULT.copy(nlpProviders = han)

        val untouched = newEditor("zh-CN")
        untouched.fillUnsetFrom(preset)
        assertEquals(han, untouched.nlpProviders.value)

        val chosen = newEditor("zh-CN")
        val own = SubtypeNlpProviderMap(spelling = HanShapeBasedLanguageProvider.ProviderId)
        chosen.nlpProviders.value = own
        chosen.fillUnsetFrom(preset)
        assertEquals(own, chosen.nlpProviders.value)
    }
}
