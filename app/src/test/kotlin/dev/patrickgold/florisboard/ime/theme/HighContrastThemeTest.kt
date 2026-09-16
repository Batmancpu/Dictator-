/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.theme

import dev.patrickgold.florisboard.dictate.ui.HIGHLIGHT_WASH_ALPHA
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The high-contrast pair (#387), checked against a number instead of against an opinion.
 *
 * The two E-Ink themes carried "(High Contrast)" in their label for a year without anyone ever having
 * measured them: they are monochrome because an e-ink panel smears, which is a *display* problem, and
 * "black on white" was taken to be good rather than checked. `dictate_contrast`/`dictate_contrast_night`
 * exist to answer the *vision* question, so the numbers are the feature and this test is what makes
 * them a fact — every label and icon at **7:1** (WCAG AAA for body text) against the surface it is
 * really drawn on, every border at **3:1** (AA for a UI component's boundary).
 *
 * Three things here are less obvious than the ratio itself:
 *
 * - **The background of a rule is rarely on the rule.** `foreground` inherits implicitly down the
 *   composition tree and `background` does not (see `Snygg.elements`), so a rule that only sets a
 *   colour for text is painting it on whatever its *element* — or its parent — last set. The cascade
 *   below reproduces that walk, and the closed-palette test above it is what lets the walk end in
 *   `--background` instead of a hand-written parent map: in this pair every surface is `--paper`,
 *   `--accent` or `--ink`, so an element with no background of its own can only be sitting on paper.
 *
 * - **`--primary` is not ours.** [org.florisboard.lib.snygg.SnyggTheme.compileFrom] silently replaces a
 *   *static* `--primary` with the accent colour the user picked on the Theme screen, and picks
 *   `--on-primary` by a luminance threshold that is deliberately not the WCAG crossover. Any guarantee
 *   written against `var(--primary)` is therefore a guarantee about a colour a stranger chooses. The
 *   pair uses `--accent`/`--on-accent`, which nothing rewrites, and this test holds it to that.
 *
 * - **The one colour the theme cannot own** is the highlight wash the prompt strip paints over a tile
 *   ([HIGHLIGHT_WASH_ALPHA] of the user's accent). That one is checked against its worst case rather
 *   than against the default accent, because the user can pick black.
 */
class HighContrastThemeTest : FunSpec({
    val themeDir: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val suffix = "src/main/assets/ime/theme/org.florisboard.themes"
        var found: File? = null
        while (dir != null && found == null) {
            found = File(dir, "app/$suffix").takeIf { it.isDirectory }
                ?: File(dir, suffix).takeIf { it.isDirectory }
            dir = dir.parentFile
        }
        found ?: error("theme assets not found from ${System.getProperty("user.dir")}")
    }
    val sheets = listOf("dictate_contrast", "dictate_contrast_night").associateWith { id ->
        Sheet(Json.parseToJsonElement(File(themeDir, "stylesheets/$id.json").readText()).jsonObject)
    }

    context("the palette is closed, so an unstyled element can only sit on a surface we measured") {
        withData(sheets.keys) { id ->
            val sheet = sheets.getValue(id)
            val surfaces = sheet.rules.values
                .mapNotNull { it["background"] }
                .map { sheet.resolve(it) }
                .distinct()
            val allowed = listOf("--paper", "--accent", "--ink").map { sheet.define(it) }
            surfaces.filterNot { it in allowed }.shouldBeEmpty()
            // ...and the three aliases the sheet uses for paper really are paper.
            for (alias in listOf("--background", "--background-variant", "--surface", "--surface-variant",
                "--popup-surface", "--one-hand-background")) {
                sheet.define(alias) shouldBe sheet.define("--paper")
            }
        }
    }

    context("every label and icon clears 7:1 against the surface it is drawn on") {
        withData(sheets.keys) { id ->
            val sheet = sheets.getValue(id)
            val failures = sheet.rules.keys
                .filterNot { it.startsWith("$") || it.startsWith("@") }
                .filterNot { it.substringBefore('[').substringBefore(':') in EXEMPT }
                .mapNotNull { rule ->
                    val fg = sheet.effective(rule, "foreground") ?: return@mapNotNull null
                    val bg = sheet.effective(rule, "background") ?: sheet.define("--background")
                    val ratio = contrast(fg, bg)
                    "$rule: $fg on $bg = ${ratio.round()}:1".takeIf { ratio < 7.0 }
                }
            failures.shouldBeEmpty()
        }
    }

    context("every bordered component is identifiable against the keyboard around it (3:1)") {
        withData(sheets.keys) { id ->
            val sheet = sheets.getValue(id)
            val failures = sheet.rules.entries
                .mapNotNull { (rule, props) ->
                    val border = props["border-color"]?.let { sheet.resolve(it) } ?: return@mapNotNull null
                    val inside = sheet.effective(rule, "background") ?: sheet.define("--background")
                    val outside = sheet.define("--background")
                    // WCAG 1.4.11 asks that the component be *identifiable*, not that its outline beat
                    // its own fill. A white key on a white keyboard is found by its black border; the
                    // enter key is found by its accent fill, and its black border reads against the
                    // keyboard rather than against the blue underneath it. Either route is enough, and
                    // requiring both would have failed a key nobody can miss.
                    val ratio = max(contrast(border, outside), contrast(inside, outside))
                    "$rule: border $border, fill $inside on $outside = ${ratio.round()}:1".takeIf { ratio < 3.0 }
                }
            failures.shouldBeEmpty()
        }
    }

    context("the prompt strip's accent wash cannot drag a tile label under 7:1, whatever accent is picked") {
        withData(sheets.keys) { id ->
            val sheet = sheets.getValue(id)
            val tile = sheet.effective("smartbar-action-tile", "background")!!
            val label = sheet.effective("smartbar-action-tile", "foreground")!!
            // The user owns this colour, so the check is over the two extremes it can reach, not over
            // the accent that happens to be the default.
            for (accent in listOf("#000000", "#ffffff")) {
                val washed = blend(accent, tile, HIGHLIGHT_WASH_ALPHA)
                contrast(label, washed) shouldBeGreaterThanOrEqual 7.0
            }
        }
    }

    context("nothing that carries text is translucent, and no surface is an image") {
        withData(sheets.keys) { id ->
            val sheet = sheets.getValue(id)
            val opaque = sheet.rules.entries.flatMap { (rule, props) ->
                listOf("background", "foreground", "border-color").mapNotNull { prop ->
                    val value = props[prop] ?: return@mapNotNull null
                    // The incognito glyph is a watermark painted behind opaque keys; it is meant to be
                    // faint and it never has text on it.
                    if (rule.startsWith("incognito-mode-indicator")) return@mapNotNull null
                    "$rule { $prop: $value }".takeIf { sheet.resolve(value).length != 7 }
                }
            }
            opaque.shouldBeEmpty()
            val translucentTrickery = sheet.rules.entries.flatMap { (rule, props) ->
                props.keys.filter { it in setOf("background-image", "background-blur", "background-sheen", "border-sheen") }
                    .map { "$rule { $it }" }
            }
            translucentTrickery.shouldBeEmpty()
        }
    }

    context("the guarantee is not handed to the user's accent colour") {
        withData(sheets.keys) { id ->
            val sheet = sheets.getValue(id)
            // compileFrom rewrites a static --primary/--primary-variant/--on-primary to whatever the
            // user picked on the Theme screen. Declaring none is what keeps the measured numbers true.
            sheet.defines.keys.filter { it.startsWith("--primary") || it == "--on-primary" }.shouldBeEmpty()
            sheet.rules.values.flatMap { it.values }
                .filter { it.contains("var(--primary") || it.contains("var(--on-primary") }
                .shouldBeEmpty()
        }
    }

    test("day and night are the same theme, differing only in the colours they define") {
        val day = sheets.getValue("dictate_contrast")
        val night = sheets.getValue("dictate_contrast_night")
        night.rules shouldBe day.rules
        night.defines.keys shouldBe day.defines.keys
    }

    test("only the measured pair is called high contrast") {
        val themes = Json.parseToJsonElement(File(themeDir, "extension.json").readText())
            .jsonObject["themes"]!!.jsonArray
            .associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["label"]!!.jsonPrimitive.content }
        themes.filterValues { it.contains("High Contrast", ignoreCase = true) }.keys shouldBe
            setOf("dictate_contrast", "dictate_contrast_night")
    }
})

/** Elements whose foreground is not a label or an icon on a surface, and so has nothing to clear. */
private val EXEMPT = setOf(
    // A giant watermark drawn behind opaque, bordered keys — faint on purpose.
    "incognito-mode-indicator",
    // Literally the value `inherit`; whatever it resolves to was already measured at its source.
    "media-emoji-key-popup-extended-indicator",
)

/** A bundled stylesheet, flattened to `rule -> property -> raw value` plus its `@defines`. */
private class Sheet(json: JsonObject) {
    val defines: Map<String, String> =
        (json["@defines"] as JsonObject).mapValues { it.value.jsonPrimitive.content }
    val rules: Map<String, Map<String, String>> = json.entries
        .filterNot { it.key.startsWith("$") || it.key.startsWith("@") }
        .associate { (rule, props) -> rule to props.jsonObject.mapValues { it.value.jsonPrimitive.content } }

    fun define(name: String): String = defines.getValue(name)

    fun resolve(value: String): String =
        VAR.matchEntire(value)?.let { defines.getValue(it.groupValues[1]) } ?: value

    /**
     * The value a rule really ends up with, walking from the most specific match outwards the way
     * [org.florisboard.lib.snygg.SnyggTheme.query] applies matching rules in ascending specificity.
     * `key[code=10]:pressed` falls back to `key[code=10]`, then `key:pressed`, then `key`.
     *
     * A `-icon` / `-text` name is the one case where the walk has to leave the element: those are
     * children `QuickActionButton` and friends build by string concatenation, and they paint *inside*
     * the element they are named after. Without that step the mic icon looks like white on the
     * keyboard background rather than white on the accent circle it is actually sitting in.
     */
    fun effective(rule: String, prop: String): String? {
        val element = rule.substringBefore('[').substringBefore(':')
        val attrs = rule.substringAfter(element).substringBefore(':').takeIf { it.startsWith("[") } ?: ""
        val selector = rule.substringAfter(element).substringAfter(attrs).takeIf { it.startsWith(":") } ?: ""
        val candidates = listOf(
            "$element$attrs$selector",
            "$element$attrs",
            "$element$selector",
            element,
        )
        candidates.firstNotNullOfOrNull { rules[it]?.get(prop) }?.let { return resolve(it) }
        val parent = CHILD_SUFFIXES.firstOrNull { element.endsWith(it) }
            ?.let { element.removeSuffix(it) }
            ?: return null
        return effective("$parent$attrs$selector", prop)
    }

    private companion object {
        val VAR = """var\((--[a-z0-9-]+)\)""".toRegex()
        val CHILD_SUFFIXES = listOf("-icon", "-text")
    }
}

private fun channel(value: Int): Double {
    val c = value / 255.0
    return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

private fun luminance(hex: String): Double {
    val rgb = hex.removePrefix("#")
    val r = rgb.substring(0, 2).toInt(16)
    val g = rgb.substring(2, 4).toInt(16)
    val b = rgb.substring(4, 6).toInt(16)
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

/** The WCAG 2.1 contrast ratio between two opaque colours, `1.0`..`21.0`. */
private fun contrast(a: String, b: String): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** [top] painted over [bottom] at [alpha], in sRGB space — the way Compose draws a translucent fill. */
private fun blend(top: String, bottom: String, alpha: Float): String {
    val t = top.removePrefix("#")
    val b = bottom.removePrefix("#")
    return (0..2).joinToString("", prefix = "#") { i ->
        val tc = t.substring(i * 2, i * 2 + 2).toInt(16)
        val bc = b.substring(i * 2, i * 2 + 2).toInt(16)
        "%02x".format((tc * alpha + bc * (1 - alpha)).roundToInt())
    }
}

private fun Double.round() = (this * 100).roundToInt() / 100.0
