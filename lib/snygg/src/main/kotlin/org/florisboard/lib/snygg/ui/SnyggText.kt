/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.florisboard.lib.snygg.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.SnyggStylesheet

/**
 * Simple text composable, which displays the given [text].
 *
 * This composable infers its style from the current [SnyggTheme][org.florisboard.lib.snygg.SnyggTheme], which is
 * required to be provided by [ProvideSnyggTheme].
 *
 * @param elementName The name of this element. If `null` the style will be inherited from the parent element.
 * @param attributes The attributes of the element used to refine the query.
 * @param selector A specific SnyggSelector to query the style for.
 * @param modifier The modifier to be applied to the Text.
 * @param text The text of the element.
 *
 * @since 0.5.0-alpha01
 *
 * @see [Text]
 */
@Composable
fun SnyggText(
    elementName: String? = null,
    attributes: SnyggQueryAttributes = emptyMap(),
    selector: SnyggSelector? = null,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    // Optional overrides for cases where the caller needs a fixed line budget (e.g. a two-line preview)
    // instead of the themed values; null falls back to the stylesheet.
    maxLines: Int? = null,
    overflow: TextOverflow? = null,
    // Scales the themed size instead of replacing it, so a caller can ask for "smaller than the text next
    // to it" without stepping outside the theme or the user's font-scale setting (issue #355).
    fontSizeMultiplier: Float = 1f,
    // Lets the text shrink to fit the width it is given instead of ellipsizing at the themed size, down
    // to this fraction of it; null keeps the size fixed (issue #346). Expressed as a fraction rather than
    // an absolute floor so it stays relative to whatever the theme and the font-scale setting resolved to.
    autoSizeMinRatio: Float? = null,
    // Optional override for text that sits on a surface the caller painted itself — a button filled with
    // the user's accent, say, where the themed foreground was chosen against the panel's background and
    // not against that. Use `Color.onAccent()` to pick it; null keeps the themed colour, which is right
    // everywhere else.
    color: Color? = null,
    // Optional override for text wrapped over several lines inside a centred box — a long label on a
    // user's own symbol key (issue #342) — where the themed alignment would leave the lines ragged.
    textAlign: TextAlign? = null,
    // With [autoSizeMinRatio] and a [maxLines] above one: the text stays on one line while it fits at this
    // fraction of the themed size or more, and only wraps when it would have to get smaller than that
    // (issue #342). Plain auto-size takes the largest size that fits in any number of lines, which breaks
    // "mfg" into "mf" over "g".
    autoSizeOneLineRatio: Float? = null,
    text: String,
) {
    ProvideSnyggStyle(elementName, attributes, selector) { style ->
        val themedSize = style.fontSize().scaledBy(fontSizeMultiplier)
        val autoSize = autoSizeFor(themedSize, autoSizeMinRatio, autoSizeOneLineRatio)
        Text(
            modifier = modifier
                .snyggMargin(style)
                .snyggShadow(style)
                .snyggBorder(style)
                .snyggBackground(style, allowClip = false)
                .snyggPadding(style),
            text = text,
            color = color ?: style.foreground(),
            // A malformed (e.g. third-party) theme can resolve a size to a non-finite value; since the font
            // scale multiplies every sp size, a NaN/∞ would reach Compose's Text and crash it on measure
            // ("lineHeight can't be negative (NaN)"). Coerce those to Unspecified so a bad theme can't crash
            // the keyboard (issue: SnyggText NaN lineHeight).
            //
            // An auto-size range supersedes a fixed size — the themed value is its upper bound instead.
            autoSize = autoSize,
            fontSize = if (autoSize != null) TextUnit.Unspecified else themedSize,
            // Optional override, same shape as the weight below: italics mark a word that came from the
            // user's own vocabulary rather than the bundled dictionary (issue #318).
            fontStyle = fontStyle ?: style.fontStyle(),
            // Optional override (e.g. bold the autocorrect/auto-commit candidate, issue #150) — falls back
            // to the themed weight when null.
            fontWeight = fontWeight ?: style.fontWeight(),
            fontFamily = style.fontFamily(LocalSnyggPreloadedCustomFontFamilies.current),
            letterSpacing = style.letterSpacing().finiteOrUnspecified(),
            lineHeight = style.lineHeight().finiteOrUnspecified(),
            textAlign = textAlign ?: style.textAlign(),
            textDecoration = style.textDecorationLine(),
            maxLines = maxLines ?: style.textMaxLines(),
            overflow = overflow ?: style.textOverflow(),
        )
    }
}

/** Returns [TextUnit.Unspecified] when this size is specified but non-finite (NaN/∞), else the value. */
private fun TextUnit.finiteOrUnspecified(): TextUnit =
    if (isSpecified && !value.isFinite()) TextUnit.Unspecified else this

/**
 * Multiplies a themed size, leaving anything the arithmetic would reject untouched — `TextUnit.times`
 * requires a specified, finite value, and a malformed theme can supply neither.
 */
private fun TextUnit.scaledBy(factor: Float): TextUnit {
    val size = finiteOrUnspecified()
    return if (factor == 1f || !size.isSpecified) size else (size * factor).finiteOrUnspecified()
}

/**
 * The auto-size range for a themed [size] and a caller's [minRatio], or `null` when the text should keep
 * the themed size — because no ratio was asked for, because the ratio would not shrink anything, or
 * because the theme resolved a size the arithmetic cannot use (the same malformed-theme case
 * [finiteOrUnspecified] exists for; a NaN bound would crash Compose on measure).
 */
internal fun autoSizeFor(size: TextUnit, minRatio: Float?, oneLineRatio: Float? = null): TextAutoSize? {
    if (minRatio == null || minRatio <= 0f || minRatio >= 1f) return null
    val max = size.finiteOrUnspecified()
    if (!max.isSpecified) return null
    val min = (max * minRatio).finiteOrUnspecified()
    if (!min.isSpecified) return null
    if (oneLineRatio != null && oneLineRatio > minRatio && oneLineRatio < 1f && max.isSp) {
        return OneLineFirstAutoSize(maxFontSize = max, oneLineMinFontSize = max * oneLineRatio, minFontSize = min)
    }
    // Half a point: fine enough that the shrink is never visible as a step, coarse enough to bound the
    // search Compose runs in the layout pass — this text is re-laid out on every keystroke.
    return TextAutoSize.StepBased(minFontSize = min, maxFontSize = max, stepSize = 0.5.sp)
}

/**
 * Auto-size that shrinks a text on one line first and lets it wrap only below [oneLineMinFontSize]
 * (issue #342): a short label stays whole, a long one — an e-mail address on a key — breaks over the
 * lines its layout allows rather than shrinking to a sliver. Both searches are binary over half-point
 * steps, since a size that fits keeps fitting as it gets smaller. Sizes in sp only.
 */
class OneLineFirstAutoSize(
    private val maxFontSize: TextUnit,
    private val oneLineMinFontSize: TextUnit,
    private val minFontSize: TextUnit,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        largestFitting(oneLineMinFontSize.value, maxFontSize.value) { size ->
            val layout = performLayout(constraints, text, size.sp)
            layout.lineCount == 1 && !layout.hasVisualOverflow
        }?.let { return it.sp }
        return (largestFitting(minFontSize.value, oneLineMinFontSize.value) { size ->
            !performLayout(constraints, text, size.sp).hasVisualOverflow
        } ?: minFontSize.value).sp
    }

    /** The largest half-point size in [low]..[high] that [fits], or null if not even [low] does. */
    private inline fun largestFitting(low: Float, high: Float, fits: (Float) -> Boolean): Float? {
        var lo = 0
        var hi = ((high - low) / STEP).toInt()
        if (!fits(low)) return null
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fits(low + mid * STEP)) lo = mid else hi = mid - 1
        }
        return low + lo * STEP
    }

    override fun equals(other: Any?): Boolean = other is OneLineFirstAutoSize &&
        other.maxFontSize == maxFontSize && other.oneLineMinFontSize == oneLineMinFontSize &&
        other.minFontSize == minFontSize

    override fun hashCode(): Int = (maxFontSize.hashCode() * 31 + oneLineMinFontSize.hashCode()) * 31 + minFontSize.hashCode()

    private companion object {
        const val STEP = 0.5f
    }
}

@Preview
@Composable
private fun SimpleSnyggText() {
    val stylesheet = SnyggStylesheet.v2 {
        "preview-column" {
            fontSize = fontSize(20.sp)
            foreground = rgbaColor(0, 0, 255)
        }
        "preview-text" {
            background = rgbaColor(255, 255, 255)
            foreground = inherit()
            borderColor = rgbaColor(0, 0, 255)
            borderWidth = size(1.dp)
            shadowElevation = size(6.dp)
            shadowColor = rgbaColor(0, 255, 0)
            margin = padding(16.dp)
            padding = padding(6.dp)
        }
        "preview-text"("attr" to listOf(1)) {
            foreground = rgbaColor(255, 0, 0)
            borderWidth = size(0.dp)
            fontSize = fontSize(10.sp)
            fontStyle = fontStyle(FontStyle.Italic)
            fontWeight = fontWeight(FontWeight.Bold)
            letterSpacing = fontSize(4.sp)
            textDecorationLine = textDecorationLine(TextDecoration.LineThrough)
        }
        "preview-text"("long" to listOf(1)) {
            fontFamily = genericFontFamily(FontFamily.Serif)
            fontSize = fontSize(10.sp)
            textMaxLines = textMaxLines(1)
            textOverflow = textOverflow(TextOverflow.Ellipsis)
        }
    }
    val theme = rememberSnyggTheme(stylesheet)

    ProvideSnyggTheme(theme) {
        SnyggColumn("preview-column", modifier = Modifier.widthIn(max = 150.dp)) {
            SnyggText("preview-text", text = "black text")
            SnyggText("preview-text", mapOf("attr" to 1), text = "red text")
            SnyggText("preview-text", mapOf("long" to 1),
                text = "this is a very long paragraph that will definitely not fit")
            // The same text twice: ellipsized at the themed size, then shrunk to fit instead.
            SnyggText("preview-text", mapOf("long" to 1), text = "Misunderstanding")
            SnyggText("preview-text", mapOf("long" to 1), autoSizeMinRatio = 0.75f,
                text = "Misunderstanding")
        }
    }
}
