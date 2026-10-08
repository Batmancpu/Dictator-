/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.symbols

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The file format of the user's symbol pages (issue #342), for the app's own copy and for sharing.
 *
 * ```
 * { "format": "dictate-symbols", "version": 1,
 *   "pages": [ [ { "key": "✓", "longPress": ["✔", "✅"] }, null, … ], [ … ] ] }
 * ```
 * A page lists its keys in reading order. `null` — or nothing, past the end — is the default key, which
 * is how the app keeps only what was changed, while an export writes every key so the receiver gets
 * exactly what the sender sees. A key may also be a bare string, and `{ "currency": 1 }` stands for the
 * language's currency wherever a symbol can. Anything unreadable falls back to the default rather than
 * failing the file, so a hand-edited file loses at most the key it got wrong.
 */
object CustomSymbolsJson {
    const val FORMAT = "dictate-symbols"
    const val VERSION = 1

    private val compact = Json

    /**
     * [symbols] as text: only its changed keys, or every key with [full] (an export). An export puts one
     * key on a line, in reading order, so that a shared file can be read and edited by hand — a generic
     * pretty-printer spreads one key with a few long presses over a dozen lines.
     */
    fun encode(symbols: CustomSymbols, full: Boolean): String {
        if (!full) {
            val root = buildJsonObject {
                put("format", FORMAT)
                put("version", VERSION)
                put("pages", buildJsonArray {
                    for (page in 0 until CustomSymbols.PAGE_COUNT) {
                        add(buildJsonArray {
                            for (index in 0 until CustomSymbols.keyCount(page)) {
                                add(if (symbols.isChanged(page, index)) keyJson(symbols.key(page, index)) else JsonNull)
                            }
                        })
                    }
                })
            }
            return compact.encodeToString(JsonElement.serializer(), root)
        }
        return buildString {
            append("{\n  \"format\": \"").append(FORMAT).append("\",\n  \"version\": ").append(VERSION)
            append(",\n  \"pages\": [\n")
            for (page in 0 until CustomSymbols.PAGE_COUNT) {
                append("    [\n")
                val count = CustomSymbols.keyCount(page)
                for (index in 0 until count) {
                    append("      ").append(compact.encodeToString(JsonElement.serializer(), keyJson(symbols.key(page, index))))
                    append(if (index < count - 1) ",\n" else "\n")
                }
                append(if (page < CustomSymbols.PAGE_COUNT - 1) "    ],\n" else "    ]\n")
            }
            append("  ]\n}\n")
        }
    }

    /** The symbol pages in [text], or null if it is not a symbols file at all. */
    fun decode(text: String): CustomSymbols? {
        val root = runCatching { compact.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) return null
        val pages = root["pages"] as? JsonArray ?: return null
        var symbols = CustomSymbols()
        for (page in 0 until minOf(pages.size, CustomSymbols.PAGE_COUNT)) {
            val keys = pages[page] as? JsonArray ?: continue
            for (index in 0 until minOf(keys.size, CustomSymbols.keyCount(page))) {
                val key = parseKey(keys[index]) ?: continue
                symbols = symbols.with(page, index, key)
            }
        }
        return symbols
    }

    private fun keyJson(key: SymbolKey): JsonElement = buildJsonObject {
        put("key", valueJson(key.value))
        if (key.longPress.isNotEmpty()) {
            put("longPress", JsonArray(key.longPress.map(::valueJson)))
        }
    }

    private fun valueJson(value: SymbolValue): JsonElement = when (value) {
        is SymbolValue.Text -> JsonPrimitive(value.text)
        is SymbolValue.Currency -> buildJsonObject { put("currency", value.slot) }
    }

    private fun parseKey(element: JsonElement): SymbolKey? = when (element) {
        is JsonObject -> parseValue(element["key"] ?: JsonNull)?.let { value ->
            val longPress = (element["longPress"] as? JsonArray).orEmpty().mapNotNull(::parseValue)
            SymbolKey(value, SymbolText.longPress(longPress))
        }
        else -> parseValue(element)?.let { SymbolKey(it) }
    }

    private fun parseValue(element: JsonElement): SymbolValue? = when (element) {
        is JsonObject -> (element["currency"] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it in 1..CURRENCY_SLOTS }
            ?.let { SymbolValue.Currency(it) }
        is JsonPrimitive -> element.takeIf { it.isString }?.content?.let(SymbolText::clean)?.let { SymbolValue.Text(it) }
        else -> null
    }

    /** A currency set has six slots. */
    private const val CURRENCY_SLOTS = 6
}
