/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.popup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.patrickgold.florisboard.ime.keyboard.Key
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

val GlobalStateNumPopupsShowing = MutableStateFlow(0)

/**
 * How far a key or popup label may shrink to fit its box, or null to keep the themed size. A user's own
 * symbol key (issue #342) can say `->`, a word, or an e-mail address on a long press; at the themed size
 * that runs far past its box. One or two code points — a letter, a symbol, an emoji with its variation
 * selector — are left alone, which keeps every built-in key exactly as it was.
 */
fun labelShrinkRatio(label: String): Float? =
    if (label.codePointCount(0, label.length) > 2) 0.3f else null

/**
 * How many lines a label that shrinks (see [labelShrinkRatio]) may wrap over, on a key and in a popup,
 * so that an e-mail address breaks instead of shrinking to a sliver (Jannis's call). The corner hint
 * keeps one line: wrapped, it would run into the key's own label.
 */
const val LONG_LABEL_MAX_LINES = 3

/**
 * Down to this fraction of the themed size a long label stays on one line; only what does not fit even
 * then wraps. Without it auto-size takes the largest size in any number of lines and breaks a word as
 * short as "mfg".
 */
const val LONG_LABEL_ONE_LINE_RATIO = 0.55f

/**
 * A long label in a long-press popup is drawn at this fraction of the themed size, the same for every
 * element (issue #342). Its element is sized for one line of it, up to a fixed width; beyond that it wraps
 * over up to [LONG_LABEL_MAX_LINES] and ends in an ellipsis, shrinking at most to [POPUP_LONG_LABEL_MIN_RATIO]
 * of this when even three lines would not hold it.
 */
const val POPUP_LONG_LABEL_SIZE = 0.55f
const val POPUP_LONG_LABEL_MIN_RATIO = 0.75f

@Composable
fun PopupBaseBox(
    modifier: Modifier = Modifier,
    attributes: SnyggQueryAttributes,
    key: Key,
    shouldIndicateExtendedPopups: Boolean,
): Unit = with(LocalDensity.current) {
    DisposableEffect(key) {
        GlobalStateNumPopupsShowing.update { it + 1 }
        onDispose {
            GlobalStateNumPopupsShowing.update { it - 1 }
        }
    }

    SnyggBox(
        elementName = FlorisImeUi.KeyPopupBox.elementName,
        attributes = attributes,
        modifier = modifier,
    ) {
        key.label?.let { label ->
            SnyggBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(key.visibleBounds.height.toDp())
                    .align(Alignment.TopCenter),
            ) {
                val shrink = labelShrinkRatio(label)
                SnyggText(
                    modifier = Modifier.align(Alignment.Center),
                    maxLines = shrink?.let { LONG_LABEL_MAX_LINES },
                    autoSizeMinRatio = shrink,
                    autoSizeOneLineRatio = shrink?.let { LONG_LABEL_ONE_LINE_RATIO },
                    textAlign = shrink?.let { TextAlign.Center },
                    text = label,
                )
            }
        }
        if (shouldIndicateExtendedPopups) {
            SnyggIcon(
                elementName = FlorisImeUi.KeyPopupExtendedIndicator.elementName,
                attributes = attributes,
                modifier = Modifier.align(Alignment.CenterEnd),
                imageVector = Icons.Default.MoreHoriz,
            )
        }
    }
}

@Composable
fun PopupExtBox(
    modifier: Modifier = Modifier,
    attributes: SnyggQueryAttributes,
    elements: List<List<PopupUiController.Element>>,
    elemArrangement: Arrangement.Horizontal,
    elemWidth: Dp,
    elemHeight: Dp,
    activeElementIndex: Int,
): Unit = with(LocalDensity.current) {
    SnyggColumn(FlorisImeUi.KeyPopupBox.elementName, attributes, modifier = modifier) {
        for (row in elements.asReversed()) {
            SnyggRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeight(elemHeight),
                horizontalArrangement = elemArrangement,
            ) {
                for (element in row) {
                    val selector = if (activeElementIndex == element.orderedIndex) {
                        SnyggSelector.FOCUS
                    } else {
                        null
                    }
                    val localAttrs = attributes.plus(FlorisImeUi.Attr.Code to element.data.code)
                    SnyggBox(
                        elementName = FlorisImeUi.KeyPopupElement.elementName,
                        attributes = localAttrs,
                        selector = selector,
                        modifier = Modifier.size(elemWidth, elemHeight),
                    ) {
                        element.label?.let { label ->
                            val long = labelShrinkRatio(label) != null
                            SnyggText(
                                modifier = Modifier.align(Alignment.Center),
                                maxLines = if (long) LONG_LABEL_MAX_LINES else null,
                                overflow = if (long) TextOverflow.Ellipsis else null,
                                fontSizeMultiplier = if (long) POPUP_LONG_LABEL_SIZE else 1f,
                                autoSizeMinRatio = if (long) POPUP_LONG_LABEL_MIN_RATIO else null,
                                textAlign = if (long) TextAlign.Center else null,
                                text = label,
                            )
                        }
                        element.icon?.let { icon ->
                            SnyggIcon(
                                modifier = Modifier.align(Alignment.Center),
                                imageVector = icon,
                            )
                        }
                    }
                }
            }
        }
    }
}
