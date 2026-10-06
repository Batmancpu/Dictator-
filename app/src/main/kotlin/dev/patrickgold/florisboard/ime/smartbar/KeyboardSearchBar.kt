/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.smartbar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import kotlin.math.roundToInt
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery

private const val CaretBlinkMillis = 1000

/** Room kept clear at the field's ends when scrolling the cursor into view. */
private val CaretMargin = 24.dp

/**
 * The search bar shared by the emoji, GIF, sticker and clipboard searches (issues #274, #317, #333).
 *
 * Leaving the search belongs in [leading] and is drawn as a back arrow, never a second ✕ — the ✕ in
 * the field means "empty the query", and two of them side by side read as the same button twice.
 */
@Composable
fun KeyboardSearchBar(
    query: String,
    placeholder: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (RowScope.() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    icon: ImageVector = Icons.Default.Search,
) {
    val keyboardManager by LocalContext.current.keyboardManager()
    SnyggRow(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.smartbarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        KeyboardFieldInput(
            text = query,
            placeholder = placeholder,
            icon = icon,
            focused = true,
            onTap = { keyboardManager.placeFieldCursor(it) },
            onPaste = { keyboardManager.pasteIntoField() },
            onClear = onClear,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

/**
 * The text line of the keyboard's own fields — the four searches and the translate bar (issue #424) —
 * so all five behave alike.
 *
 * None of them is a real text field: an input method cannot give focus to a view of its own, so the
 * text lives in `KeyboardManager` (with its `fieldCursor` and `fieldSelection`, shared because only one
 * field is ever open) and the keys are folded into it there. This only draws it — but draws it as a text
 * field: one line that scrolls sideways to keep the cursor in view, a blinking cursor wherever a tap,
 * the arrow keys or a space-bar glide put it, and the stretch a Backspace swipe marks. Without the keys
 * ([focused] false, the translate bar after a tap into the app) it keeps its text and shows no cursor;
 * a tap on it hands [onTap] the offset under the finger.
 *
 * Held, it does what holding a text field does: the cursor goes under the finger, and a copied text is
 * offered to paste (issue #433) — [onPaste] writes it. A line break, which only the translate bar can
 * hold, shows as ↵ on the one line.
 *
 * [modifier] sizes the pill; the pill brings its own inset and background.
 */
@Composable
fun KeyboardFieldInput(
    text: String,
    placeholder: String,
    icon: ImageVector,
    focused: Boolean,
    onTap: (Int) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardManager by LocalContext.current.keyboardManager()
    val cursor by keyboardManager.fieldCursor.collectAsState()
    val selection by keyboardManager.fieldSelection.collectAsState()
    val style = rememberSnyggThemeQuery(FlorisImeUi.SmartbarCandidatesRow.elementName)
    val inputFeedbackController = LocalInputFeedbackController.current
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var pasteOffered by remember { mutableStateOf(false) }
    val currentText by rememberUpdatedState(text)
    val currentOnTap by rememberUpdatedState(onTap)

    /** A hold at [offset]: the cursor goes there, and the paste is offered if there is anything to paste. */
    fun hold(offset: Int) {
        currentOnTap(offset)
        if (!keyboardManager.canPasteIntoField()) return
        inputFeedbackController.keyLongPress(TextKeyData.UNSPECIFIED)
        pasteOffered = true
    }

    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(50))
            .background(if (focused) Color(0x33808080) else Color(0x1A808080))
            // A tap beside the text — on the icon, in the empty end of the field — puts the cursor last.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                        currentOnTap(currentText.length)
                    },
                    onLongPress = { hold(currentText.length) },
                )
            }
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnyggIcon(
            imageVector = icon,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(18.dp),
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) {
                // The cursor marks where the next character lands, so with nothing typed it belongs
                // *before* the placeholder, not after it.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (focused) Caret(color = style.foreground())
                    Text(
                        modifier = Modifier.padding(start = 6.dp),
                        text = placeholder,
                        color = style.foreground().copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .horizontalScroll(scroll)
                        .pointerInput(text) {
                            detectTapGestures(
                                onTap = { position ->
                                    inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                                    layout?.let { onTap(it.getOffsetForPosition(position)) }
                                },
                                onLongPress = { position ->
                                    layout?.let { hold(it.getOffsetForPosition(position)) }
                                },
                            )
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // One character for one, so every offset into the text is the same offset on screen.
                    val line = text.replace('\n', LineBreakGlyph)
                    // A stretch marked by the Backspace swipe is shown the way a text field shows one.
                    val marked = selection?.let { it.first.coerceIn(0, text.length) until it.last.coerceIn(0, text.length) }
                    val shown = if (marked == null || marked.isEmpty()) {
                        AnnotatedString(line)
                    } else {
                        buildAnnotatedString {
                            append(line)
                            addStyle(SpanStyle(background = style.foreground().copy(alpha = 0.3f)), marked.first, marked.last + 1)
                        }
                    }
                    Text(
                        text = shown,
                        color = style.foreground().copy(alpha = if (focused) 1f else 0.7f),
                        maxLines = 1,
                        softWrap = false,
                        onTextLayout = { layout = it },
                        // Room after the last character for the cursor and the scroll margin.
                        modifier = Modifier.padding(end = CaretMargin),
                    )
                    val caretX = layout?.caretX(cursor)
                    if (focused && caretX != null && selection == null) {
                        Caret(
                            color = style.foreground(),
                            modifier = Modifier.offset { IntOffset(caretX - 2.dp.roundToPx(), 0) },
                        )
                    }
                }
                // Keep the cursor in view as it moves or the text grows under it.
                LaunchedEffect(cursor, text, layout) {
                    val x = layout?.caretX(cursor) ?: return@LaunchedEffect
                    val margin = with(density) { CaretMargin.roundToPx() }
                    val viewport = scroll.viewportSize
                    val target = when {
                        x - margin < scroll.value -> x - margin
                        x + margin > scroll.value + viewport -> x + margin - viewport
                        else -> return@LaunchedEffect
                    }
                    scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
                }
            }
        }
        if (text.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                        onClear()
                    }
                    .padding(6.dp),
            ) {
                SnyggIcon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringRes(R.string.action__clear),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // Not focusable: a focusable menu raised by the keyboard hides the keyboard (#284).
        DropdownMenu(
            expanded = pasteOffered,
            onDismissRequest = { pasteOffered = false },
            offset = DpOffset(x = 24.dp, y = 0.dp),
            properties = PopupProperties(focusable = false),
        ) {
            DropdownMenuItem(
                text = { Text(stringRes(R.string.quick_action__clipboard_paste)) },
                leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                onClick = {
                    pasteOffered = false
                    onPaste()
                },
            )
        }
    }
}

/** How a line break in a field's one line is drawn — one character, like the break it stands for. */
private const val LineBreakGlyph = '↵'

/**
 * Where the cursor at [offset] is drawn. Clamped to the text *this layout* was made from, which trails
 * the field's by a frame: a letter typed moves the cursor before the new text has been laid out, and
 * asking the old layout for an offset past its end throws.
 */
private fun TextLayoutResult.caretX(offset: Int): Int =
    getCursorRect(offset.coerceIn(0, layoutInput.text.length)).left.roundToInt()

/** A blinking text cursor, marking where the next keystroke lands. */
@Composable
internal fun Caret(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "search-caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        // Hard on/off rather than a fade: a fading caret reads as a loading indicator.
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = CaretBlinkMillis
                1f at 0 using LinearEasing
                1f at CaretBlinkMillis / 2 using LinearEasing
                0f at CaretBlinkMillis / 2 + 1 using LinearEasing
                0f at CaretBlinkMillis
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "search-caret-alpha",
    )
    Box(
        modifier = modifier
            .padding(start = 2.dp)
            .alpha(alpha)
            .width(2.dp)
            .height(18.dp)
            .background(color),
    )
}
