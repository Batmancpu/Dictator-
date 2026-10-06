/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.math

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The inline calculator (issue #329): what it answers, and — the larger half — what it stays quiet about.
 *
 * A wrong or unwanted answer costs a tap and a correction, so the bar for offering one is the same as
 * everywhere else in this keyboard: only on an unambiguous, deliberate expression.
 */
class CalculatorTest {

    private val de = Locale.GERMANY
    private val en = Locale.US

    private fun de(text: String) = Calculator.evaluateTrailing(text, de)?.result

    private fun en(text: String) = Calculator.evaluateTrailing(text, en)?.result

    /** What a tap on the answer writes behind the cursor. */
    private fun tap(text: String, locale: Locale = en) = Calculator.evaluateTrailing(text, locale)?.completion

    // ── The arithmetic ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `multiplies`() {
        assertEquals("600", en("150 * 4 ="))
    }

    @Test
    fun `works without any spaces`() {
        assertEquals("600", en("150*4="))
    }

    @Test
    fun `allows one trailing space`() {
        assertEquals("600", en("150 * 4 = "))
    }

    @Test
    fun `respects precedence`() {
        assertEquals("14", en("2 + 3 * 4 ="))
    }

    @Test
    fun `respects brackets`() {
        assertEquals("20", en("(2 + 3) * 4 ="))
    }

    @Test
    fun `handles a leading minus`() {
        assertEquals("-1", en("-5 + 4 ="))
    }

    @Test
    fun `handles the unicode operators from the symbols layout`() {
        assertEquals("600", en("150 × 4 ="))
        assertEquals("4", en("12 ÷ 3 ="))
        assertEquals("7", en("10 − 3 ="))
    }

    @Test
    fun `adds decimals exactly`() {
        // The whole reason this is BigDecimal: with Double this is 0.30000000000000004.
        assertEquals("0.3", en("0.1 + 0.2 ="))
    }

    @Test
    fun `divides`() {
        assertEquals("2.5", en("5 / 2 ="))
    }

    @Test
    fun `rounds a repeating quotient rather than running forever`() {
        assertEquals("0.3333333333", en("1 / 3 ="))
    }

    // ── The decimal separator ────────────────────────────────────────────────────────────────────

    @Test
    fun `reads and writes the german comma`() {
        assertEquals("3", de("1,5 * 2 ="))
        assertEquals("3,5", de("1,5 + 2 ="))
    }

    @Test
    fun `reads and writes the english point`() {
        assertEquals("3.5", en("1.5 + 2 ="))
    }

    @Test
    fun `reads a grouped number in each language`() {
        assertEquals("2469", de("1.234 + 1.235 ="))
        assertEquals("2469", en("1,234 + 1,235 ="))
    }

    @Test
    fun `refuses a number that is grouped wrongly`() {
        // "1.5" is not a German number, and reading it as one-and-a-half would give the same characters
        // two meanings in the same language.
        assertNull(de("1.5 + 2 ="))
        assertNull(en("1,5 + 2 ="))
    }

    @Test
    fun `a german result never comes back with a point`() {
        assertEquals("0,3", de("0,1 + 0,2 ="))
    }

    // ── When there is no suggestion ──────────────────────────────────────────────────────────────

    @Test
    fun `stays quiet once the answer is written`() {
        assertNull(en("150 * 4 = 600"))
        assertNull(en("2+3=5"))
    }

    @Test
    fun `stays quiet on a sum that is not finished yet`() {
        assertNull(en("2+"))
        assertNull(en("2 + 3 +"))
        assertNull(en("(2+3"))
    }

    @Test
    fun `stays quiet on a bare number`() {
        assertNull(en("42 ="))
        assertNull(en("-42 ="))
    }

    @Test
    fun `stays quiet on an assignment in code`() {
        assertNull(en("x ="))
        assertNull(en("let total ="))
    }

    @Test
    fun `stays quiet when digits are glued to a word`() {
        assertNull(en("abc5+5="))
    }

    @Test
    fun `stays quiet on division by zero`() {
        assertNull(en("5 / 0 ="))
        assertNull(en("5 / (3 - 3) ="))
    }

    @Test
    fun `stays quiet on an unfinished expression`() {
        assertNull(en("5 + ="))
        assertNull(en("(5 + 3 ="))
        assertNull(en("5 + 3) ="))
    }

    @Test
    fun `stays quiet on prose that happens to end in an equals sign`() {
        assertNull(en("Die Formel lautet E ="))
        assertNull(en("="))
    }

    @Test
    fun `does not mistake a date or a price in front of the sum`() {
        assertNull(de("am 3. 4+4="))
        assertNull(de("Ich zahle 20, 5+5="))
    }

    @Test
    fun `stays quiet on an expression over the length limit`() {
        val long = "1+".repeat(Calculator.MAX_EXPRESSION_LENGTH) + "1 ="
        assertNull(en(long))
    }

    @Test
    fun `stays quiet on more tokens than it will parse`() {
        val many = (1..Calculator.MAX_TOKENS).joinToString("+") { "1" } + "="
        assertNull(en(many))
    }

    @Test
    fun `stays quiet on brackets nested past the limit`() {
        val deep = "(".repeat(Calculator.MAX_DEPTH + 1) + "1+1" + ")".repeat(Calculator.MAX_DEPTH + 1) + "="
        assertNull(en(deep))
    }

    @Test
    fun `stays quiet on empty input`() {
        assertNull(en(""))
    }

    // ── Reading the expression out of surrounding text ───────────────────────────────────────────

    @Test
    fun `picks the sum out of a sentence`() {
        assertEquals("600", en("Das macht dann 150 * 4 ="))
    }

    @Test
    fun `starts after the previous result`() {
        assertEquals("10", en("150 * 4 = 600 und 5+5="))
    }

    // ── Without an equals sign (issue #440) ──────────────────────────────────────────────────────

    @Test
    fun `answers a sum before the equals sign is typed`() {
        assertEquals("5", en("2+3"))
        assertEquals("600", en("150 * 4"))
        assertEquals("10", en("(2+3)*2"))
        assertEquals("3,5", de("1,5+2"))
    }

    @Test
    fun `allows a trailing space before the equals sign`() {
        assertEquals("5", en("2 + 3 "))
    }

    @Test
    fun `carries on from the answer just written`() {
        assertEquals("6", en("2+3=5 + 1"))
    }

    @Test
    fun `takes a tight minus or slash for a date, a range or a phone number`() {
        assertNull(en("2026-10-01"))
        assertNull(en("10/1/2026"))
        assertNull(en("9-5"))
        assertNull(en("555-1234"))
        assertNull(en("+1-555-1234"))
        assertNull(en("24/7"))
    }

    @Test
    fun `takes a minus or slash with a space beside it for arithmetic`() {
        assertEquals("7", en("10 - 3"))
        assertEquals("4", en("8 / 2"))
        assertEquals("7", en("10−3"))
    }

    @Test
    fun `lets the equals sign settle a tight minus or slash`() {
        assertEquals("7", en("10-3="))
        assertEquals("4", en("8/2="))
    }

    @Test
    fun `stays quiet on a bare number without an equals sign`() {
        assertNull(en("42"))
        assertNull(en("-42"))
        assertNull(en("+49 170 1234567"))
    }

    @Test
    fun `stays quiet on digits glued to a word without an equals sign`() {
        assertNull(en("abc5+5"))
        assertNull(en("mc2"))
        assertNull(de("am 3. 4+4"))
    }

    // ── What a tap writes (issue #440) ───────────────────────────────────────────────────────────

    @Test
    fun `a tap completes the sum instead of replacing it`() {
        assertEquals("=5", tap("2+3"))
        assertEquals("=3,5", tap("1,5+2", de))
    }

    @Test
    fun `a tap follows the spacing the sum was written in`() {
        assertEquals(" = 5", tap("2 + 3"))
        assertEquals("= 5", tap("2 + 3 "))
    }

    @Test
    fun `a tap after the equals sign adds only the answer`() {
        assertEquals("5", tap("2+3="))
        assertEquals(" 5", tap("2 + 3 ="))
        assertEquals("5", tap("2 + 3 = "))
        // The space before the equals sign is the only one there is, and it still says how the line is
        // being written.
        assertEquals(" 5", tap("2+3 ="))
    }
}
