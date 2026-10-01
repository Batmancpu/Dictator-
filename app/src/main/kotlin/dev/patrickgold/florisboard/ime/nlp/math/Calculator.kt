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

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Answers `150 * 4` in the suggestion strip (issues #329, #440).
 *
 * Two things shape everything here.
 *
 * The first is that this runs on **every keystroke**, in front of the word suggestions, so it has to
 * decide "not a sum" almost instantly and almost always. Hence the hard limits below and the fact that
 * the very first test is a single character comparison: a sum ends in a digit, a bracket or `=`, and
 * anything else costs no work.
 *
 * The second is the **decimal separator**. `1,5 * 2` is how half of Europe writes it and `1.5 * 2` is
 * how the other half does, and the same two characters swap roles as the thousands separator. Which is
 * which comes from the active keyboard language, never from a guess about the text — and where a number
 * would be ambiguous under that language, there is simply no suggestion. Being silent is free; being
 * wrong about somebody's invoice is not.
 *
 * Arithmetic is [BigDecimal] throughout. `0.1 + 0.2` has to be `0.3`: a calculator that answers
 * `0.30000000000000004` is not a calculator, it is a bug report.
 */
object Calculator {

    /** Longer than this is not a sum somebody typed, it is text that happens to contain digits. */
    const val MAX_EXPRESSION_LENGTH = 64

    /** Bounds the parser's work regardless of what the expression looks like. */
    const val MAX_TOKENS = 32

    /** Bounds recursion, so a paste of `((((((((…` cannot walk down the stack. */
    const val MAX_DEPTH = 8

    /** Longer results are not useful in a one-line strip, and hint at a runaway expression. */
    private const val MAX_RESULT_LENGTH = 32

    /** Digits of quotient kept before rounding; trailing zeros come off afterwards. */
    private const val DIVISION_SCALE = 10

    private const val OPERATORS = "+-*/×÷−"

    /**
     * The operators that mean arithmetic wherever they stand between two numbers. `-` and `/` are not
     * among them: written tight, they are a date (`2026-10-01`, `10/1/2026`), a range (`9-5`), a phone
     * number (`555-1234`) or a ratio (`24/7`) far more often than a calculation.
     */
    private const val UNAMBIGUOUS_OPERATORS = "+*×÷−"

    /**
     * A sum found in front of the cursor.
     *
     * @property result What it comes to, in the language's own notation — the label on the strip.
     * @property completion What a tap writes behind the cursor so that the line reads as a finished sum:
     *  `=5` where no equals sign was typed yet, only `5` where one was. Never a replacement — the
     *  expression the user typed stays exactly as it is (issue #440).
     */
    data class Answer(val result: String, val completion: String)

    /** The expression [trailingExpression] found, and how the user wrote it. */
    internal data class Trailing(
        val expression: String,
        val equalsTyped: Boolean,
        /** Whether the sum was written with spaces, which the completion then follows. */
        val spaced: Boolean,
    )

    /**
     * The answer to the arithmetic expression ending at the cursor, formatted for [locale], or null when
     * [textBeforeCursor] does not end in one.
     */
    fun evaluateTrailing(textBeforeCursor: String, locale: Locale): Answer? {
        val trailing = trailingExpression(textBeforeCursor) ?: return null
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val value = runCatching {
            Parser(trailing.expression, symbols.decimalSeparator, symbols.groupingSeparator).parse()
        }.getOrNull() ?: return null
        val result = format(value, symbols.decimalSeparator) ?: return null
        return Answer(result, completion(trailing, result, textBeforeCursor.endsWith(' ')))
    }

    /**
     * The expression standing at the cursor, with or without a trailing `=`, or null.
     *
     * Reads backwards from the cursor and stops at the first character that could not be part of a sum,
     * which is what keeps this out of the way of ordinary text: in `x=5` the character before the `=` is
     * a letter, so there is no expression at all. A letter or digit immediately in front of what was
     * collected disqualifies it for the same reason — `abc5+5=` is not arithmetic somebody typed.
     *
     * Without the `=` the sum has to say by itself that it is one (issue #440): `2+3` does, `2026-10-01`
     * does not — see [asksForAnswer].
     */
    internal fun trailingExpression(textBeforeCursor: String): Trailing? {
        // Trailing spaces are allowed, because "150 * 4 = " is how a person writes it, and "2 + 3 " is
        // what stands there the moment before the "=" is typed.
        val trimmed = textBeforeCursor.trimEnd(' ')
        val last = trimmed.lastOrNull() ?: return null
        val equalsTyped = last == '='
        if (!equalsTyped && !last.isDigit() && last != ')') return null
        val body = if (equalsTyped) trimmed.dropLast(1) else trimmed

        var start = body.length
        while (start > 0) {
            val c = body[start - 1]
            val isPart = c.isDigit() || c in OPERATORS || c == '(' || c == ')' ||
                c == ' ' || c == '.' || c == ','
            if (!isPart) break
            start--
        }
        // Past the spaces the run swallowed on its way back, so the boundary test below looks at the
        // character actually touching the expression rather than at the last letter of the word before it.
        while (start < body.length && body[start] == ' ') start++
        if (start > 0 && body[start - 1].isLetterOrDigit()) return null

        val expression = body.substring(start).trim()
        if (expression.isEmpty() || expression.length > MAX_EXPRESSION_LENGTH) return null
        // A bare number followed by "=" is not a calculation, it is a line of a form being filled in.
        // The leading sign of a negative number does not count as the operator that makes it one.
        if (expression.drop(1).none { it in OPERATORS }) return null
        if (!equalsTyped && !asksForAnswer(expression)) return null
        return Trailing(
            expression = expression,
            equalsTyped = equalsTyped,
            // In "2+3 =" the space before the equals sign is the only one there is, and it still says
            // how this person writes a sum.
            spaced = ' ' in expression || body.endsWith(' '),
        )
    }

    /**
     * Whether [expression] reads as a sum somebody wants answered even though no `=` asked for it.
     *
     * One operator from [UNAMBIGUOUS_OPERATORS] is enough. `-` and `/` count only with a space beside
     * them, because `10 - 3` is somebody calculating and `10-3` is as likely a score or a date. Typing the
     * `=` settles it either way, which is why this is never asked when one was typed.
     */
    internal fun asksForAnswer(expression: String): Boolean =
        (1 until expression.length).any { i ->
            val c = expression[i]
            c in UNAMBIGUOUS_OPERATORS ||
                (c == '-' || c == '/') && (expression[i - 1] == ' ' || expression.getOrNull(i + 1) == ' ')
        }

    /**
     * What a tap writes behind the cursor, in the spacing the user chose for the sum itself: `2+3`
     * becomes `2+3=5`, `2 + 3` becomes `2 + 3 = 5`, and `2 + 3 =` only gets its ` 5`.
     *
     * Completing rather than replacing is the point of issue #440: the expression is what the reader of
     * the message needs to see, and somebody who wanted only the number would not have typed the sum
     * into the field in the first place.
     */
    private fun completion(trailing: Trailing, result: String, endsWithSpace: Boolean): String {
        val gap = if (trailing.spaced) " " else ""
        return when {
            trailing.equalsTyped && endsWithSpace -> result
            trailing.equalsTyped -> "$gap$result"
            endsWithSpace -> "= $result"
            else -> "$gap=$gap$result"
        }
    }

    /** Plain decimal notation, trailing zeros removed, in the language's own decimal separator. */
    private fun format(value: BigDecimal, decimalSeparator: Char): String? {
        // toPlainString, because stripTrailingZeros turns 600 into 6E+2 and nobody wants that pasted.
        val plain = value.stripTrailingZeros().toPlainString()
        if (plain.length > MAX_RESULT_LENGTH) return null
        return if (decimalSeparator == '.') plain else plain.replace('.', decimalSeparator)
    }

    /**
     * Recursive descent over `+ - * /` with brackets and the usual precedence. Throws on anything it
     * does not understand; [evaluateTrailing] turns that into "no suggestion".
     */
    private class Parser(
        private val input: String,
        private val decimalSeparator: Char,
        private val groupingSeparator: Char,
    ) {
        private var pos = 0
        private var tokensRead = 0

        fun parse(): BigDecimal {
            val value = expression(depth = 0)
            skipSpaces()
            require(pos == input.length) { "trailing input" }
            return value
        }

        private fun expression(depth: Int): BigDecimal {
            var left = term(depth)
            while (true) {
                skipSpaces()
                val op = peek() ?: return left
                if (op != '+' && op != '-' && op != '−') return left
                advance()
                val right = term(depth)
                left = if (op == '+') left.add(right) else left.subtract(right)
            }
        }

        private fun term(depth: Int): BigDecimal {
            var left = factor(depth)
            while (true) {
                skipSpaces()
                val op = peek() ?: return left
                if (op != '*' && op != '/' && op != '×' && op != '÷') return left
                advance()
                val right = factor(depth)
                left = if (op == '*' || op == '×') {
                    left.multiply(right)
                } else {
                    require(right.signum() != 0) { "division by zero" }
                    left.divide(right, DIVISION_SCALE, RoundingMode.HALF_UP)
                }
            }
        }

        private fun factor(depth: Int): BigDecimal {
            skipSpaces()
            return when (peek()) {
                '-', '−' -> {
                    advance()
                    factor(depth).negate()
                }
                '+' -> {
                    advance()
                    factor(depth)
                }
                else -> primary(depth)
            }
        }

        private fun primary(depth: Int): BigDecimal {
            require(depth < MAX_DEPTH) { "too deeply nested" }
            skipSpaces()
            if (peek() == '(') {
                advance()
                val value = expression(depth + 1)
                skipSpaces()
                require(peek() == ')') { "unclosed bracket" }
                advance()
                return value
            }
            return number()
        }

        private fun number(): BigDecimal {
            countToken()
            val start = pos
            while (pos < input.length) {
                val c = input[pos]
                if (c.isDigit() || c == decimalSeparator || c == groupingSeparator) pos++ else break
            }
            require(pos > start) { "expected a number" }
            return parseNumber(input.substring(start, pos))
        }

        /**
         * Reads one number in the language's own notation.
         *
         * The grouping separator is only accepted where it genuinely groups: `1.234` is one thousand
         * two hundred thirty-four in German, but `1.5` is not a German number at all — and guessing that
         * it means one-and-a-half would silently reinterpret the same characters that mean `1.500` two
         * lines further up. Ambiguity ends the whole expression instead.
         */
        private fun parseNumber(raw: String): BigDecimal {
            val integerPart = raw.substringBefore(decimalSeparator)
            val rest = if (raw.contains(decimalSeparator)) raw.substringAfter(decimalSeparator) else ""
            require(!rest.contains(decimalSeparator)) { "two decimal separators" }
            require(!rest.contains(groupingSeparator)) { "grouping after the decimal point" }
            val digits = if (integerPart.contains(groupingSeparator)) {
                val groups = integerPart.split(groupingSeparator)
                require(groups.size > 1 && groups.first().length in 1..3) { "not a grouped number" }
                require(groups.drop(1).all { it.length == 3 }) { "not a grouped number" }
                groups.joinToString("")
            } else {
                integerPart
            }
            require(digits.isNotEmpty() || rest.isNotEmpty()) { "no digits" }
            return BigDecimal(if (rest.isEmpty()) digits else "${digits.ifEmpty { "0" }}.$rest")
        }

        private fun countToken() {
            tokensRead++
            require(tokensRead <= MAX_TOKENS) { "too many tokens" }
        }

        private fun peek(): Char? = input.getOrNull(pos)

        private fun advance() {
            countToken()
            pos++
        }

        private fun skipSpaces() {
            while (pos < input.length && input[pos] == ' ') pos++
        }
    }
}
