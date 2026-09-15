/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.editor

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.input.InputEventDispatcher
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.PanelHeaderButton
import dev.patrickgold.florisboard.ime.keyboard.computeImageVector
import dev.patrickgold.florisboard.ime.smartbar.SelectionCounterPill
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.ime.window.LocalWindowController
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.model.collectAsState as collectPrefAsState
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery

/**
 * The text editing panel (issue #386): the cursor pad, the selection toggle and the clipboard actions,
 * on one surface instead of scattered between Smartbar slots and swipe gestures nobody remembers.
 *
 * **It implements nothing.** Every key here sends a key code that `KeyboardManager` has handled for
 * years — the arrows repeat because [KeyCode.ARROW_LEFT] and friends are in the dispatcher's repeatable
 * list, `Select` is [KeyCode.CLIPBOARD_SELECT] (which makes the arrows drag a selection instead of
 * moving the cursor), and cut/copy/paste are the very actions the Smartbar offers. The panel is a
 * surface, not a second implementation; that is what keeps it from drifting out of step.
 *
 * **Why a panel and not a keyboard mode**, which is what FlorisBoard had started and left behind as
 * `KeyboardMode.EDITING`, `@Deprecated("TODO: remove")`: the panels already answer the three questions a
 * new surface has to answer — how tall it is, how it is themed, and how one gets back out — while the
 * keyboard mode would have answered none of them without a layout file per subtype. That half-built
 * mode is deleted in the same change, so there is only one thing called EDITING.
 */
@Composable
fun EditingPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val prefs by FlorisPreferenceStore
    val accent by prefs.theme.accentColor.collectPrefAsState()
    val state by keyboardManager.activeState.collectAsState()
    val windowController = LocalWindowController.current
    val windowSpec by windowController.activeWindowSpec.collectAsState()

    // The gaps between the pad's keys are the keyboard's own, resize handles and the key-spacing
    // sliders (#365) included — a pad that spaced its keys differently would read as a different app.
    val marginH = windowSpec.keyMarginH
    val marginV = windowSpec.keyMarginV

    SnyggColumn(
        elementName = FlorisImeUi.Media.elementName,
        modifier = modifier
            .fillMaxWidth()
            // Locked to the normal keyboard height, like every other panel, so opening it never makes
            // the IME jump.
            .height(FlorisImeSizing.panelUiHeight()),
    ) {
        SnyggRow(
            elementName = FlorisImeUi.ClipboardHeader.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelHeaderButton(
                onClick = { keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT },
                modifier = Modifier
                    .sizeIn(maxHeight = FlorisImeSizing.smartbarHeight)
                    .aspectRatio(1f),
            ) {
                SnyggIcon(imageVector = Icons.AutoMirrored.Filled.ArrowBack)
            }
            SnyggText(
                elementName = FlorisImeUi.ClipboardHeaderText.elementName,
                modifier = Modifier.weight(1f),
                text = stringRes(R.string.quick_action__ime_ui_mode_editing),
            )
            // What is selected right now, in the panel whose whole purpose is changing it (#335 built
            // this for the Smartbar; here it needs no setting, because opening this panel *is* the
            // request to be told). Draws nothing at all while nothing is selected.
            SelectionCounterPill(modifier = Modifier.padding(end = 8.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // Selection and clipboard, in the order they are used: mark something, then do something
            // with it.
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                EditingKey(TextKeyData.CLIPBOARD_SELECT, R.string.quick_action__clipboard_select,
                    marginH, marginV, accent, active = state.isManualSelectionMode)
                EditingKey(TextKeyData.CLIPBOARD_SELECT_ALL, R.string.quick_action__clipboard_select_all,
                    marginH, marginV, accent)
                EditingKey(TextKeyData.CLIPBOARD_CUT, R.string.quick_action__clipboard_cut,
                    marginH, marginV, accent)
                EditingKey(TextKeyData.CLIPBOARD_COPY, R.string.quick_action__clipboard_copy,
                    marginH, marginV, accent)
                EditingKey(TextKeyData.CLIPBOARD_PASTE, R.string.quick_action__clipboard_paste,
                    marginH, marginV, accent)
            }
            Row(modifier = Modifier.fillMaxWidth().weight(3f)) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    EditingKey(TextKeyData.UNDO, R.string.quick_action__undo, marginH, marginV, accent)
                    EditingKey(TextKeyData.REDO, R.string.quick_action__redo, marginH, marginV, accent)
                    EditingKey(TextKeyData.IME_UI_MODE_CLIPBOARD,
                        R.string.quick_action__ime_ui_mode_clipboard, marginH, marginV, accent)
                }
                // The cross. Its empty corners are what make it read as a direction pad rather than as
                // three more rows of buttons, so they stay empty.
                Column(modifier = Modifier.weight(3f).fillMaxHeight()) {
                    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        EditingKey(TextKeyData.MOVE_START_OF_PAGE,
                            R.string.quick_action__move_start_of_page, marginH, marginV, accent)
                        EditingKey(TextKeyData.ARROW_UP, R.string.quick_action__arrow_up,
                            marginH, marginV, accent)
                        EditingKey(TextKeyData.MOVE_END_OF_PAGE,
                            R.string.quick_action__move_end_of_page, marginH, marginV, accent)
                    }
                    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        EditingKey(TextKeyData.ARROW_LEFT, R.string.quick_action__arrow_left,
                            marginH, marginV, accent)
                        Spacer(modifier = Modifier.weight(1f).fillMaxHeight())
                        EditingKey(TextKeyData.ARROW_RIGHT, R.string.quick_action__arrow_right,
                            marginH, marginV, accent)
                    }
                    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Spacer(modifier = Modifier.weight(1f).fillMaxHeight())
                        EditingKey(TextKeyData.ARROW_DOWN, R.string.quick_action__arrow_down,
                            marginH, marginV, accent)
                        Spacer(modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    EditingKey(TextKeyData.DELETE, R.string.dictate__legacy_backspace,
                        marginH, marginV, accent)
                    EditingKey(TextKeyData.FORWARD_DELETE, R.string.quick_action__forward_delete,
                        marginH, marginV, accent)
                    // A second way back to typing, where a thumb already is. The header's arrow is the
                    // other one, and it is a long reach on a tall phone.
                    EditingKey(TextKeyData.IME_UI_MODE_TEXT, R.string.editing__back_to_keyboard,
                        marginH, marginV, accent) {
                        Text(text = "ABC", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * One key of the pad, drawn and themed exactly like a keyboard key (`key[code=…]`, so a theme that
 * styles a key styles these too) and dispatched exactly like one — which is where hold-to-repeat comes
 * from, for free, on the arrows and on backspace.
 *
 * [active] marks a toggle that is currently on. It is painted with the user's accent rather than with a
 * second icon, because the only toggle here is `Select`, and "arrows now drag a selection" is a state of
 * the keyboard, not a different action.
 */
@Composable
private fun RowScope.EditingKey(
    keyData: TextKeyData,
    labelRes: Int,
    marginH: Dp,
    marginV: Dp,
    accent: Color,
    active: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(horizontal = marginH, vertical = marginV),
    ) {
        EditingKeyContent(keyData, labelRes, accent, active, content)
    }
}

@Composable
private fun ColumnScope.EditingKey(
    keyData: TextKeyData,
    labelRes: Int,
    marginH: Dp,
    marginV: Dp,
    accent: Color,
    active: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = marginH, vertical = marginV),
    ) {
        EditingKeyContent(keyData, labelRes, accent, active, content)
    }
}

/**
 * **Raw `MotionEvent`s, not a Compose gesture detector**, and that is the whole reason hold-to-repeat
 * works here. Compose ends its own pointer stream part way into a press inside this IME — it hands the
 * gesture a synthetic release with the finger unmoved, around 110 ms in — and a key built on
 * `awaitEachGesture` would therefore send `up` to [InputEventDispatcher] before the repeat ever starts,
 * which is exactly the "worse than the gesture it replaces" the issue warns about. The typing keyboard
 * has always taken its touches through [pointerInteropFilter] for this reason; so does this pad.
 *
 * Everything after the dispatch is the keyboard's: the repeat loop, its acceleration, whether backspace
 * repeats by character or by word, and the feedback tick.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EditingKeyContent(
    keyData: TextKeyData,
    labelRes: Int,
    accent: Color,
    active: Boolean,
    content: (@Composable () -> Unit)?,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val inputFeedbackController = LocalInputFeedbackController.current
    val evaluator by keyboardManager.activeEvaluator.collectAsState()
    val label = stringRes(labelRes)
    val attributes = remember(keyData) { mapOf(FlorisImeUi.Attr.Code to keyData.code) }
    // The accent ring follows the key's own shape, so it fits round keys and square ones alike.
    val keyShape = rememberSnyggThemeQuery(FlorisImeUi.Key.elementName, attributes).shape()
    val imageVector: ImageVector? = evaluator.computeImageVector(keyData)

    var isPressed by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(keyData) {
        // Leaving the panel mid-press must not leave the key down: the dispatcher keys its pressed map
        // by code, and a code left down there swallows every later press of it.
        onDispose { keyboardManager.inputEventDispatcher.sendCancel(keyData) }
    }

    SnyggBox(
        elementName = FlorisImeUi.Key.elementName,
        attributes = attributes,
        selector = if (isPressed) SnyggSelector.PRESSED else SnyggSelector.NONE,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it },
        clickAndSemanticsModifier = Modifier
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .then(
                if (active) {
                    Modifier
                        .background(accent.copy(alpha = 0.22f), keyShape)
                        .border(2.dp, accent, keyShape)
                } else {
                    Modifier
                },
            )
            .pointerInteropFilter { event ->
                val dispatcher = keyboardManager.inputEventDispatcher
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        isPressed = true
                        dispatcher.sendDown(keyData)
                        inputFeedbackController.keyPress(keyData)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Sliding off a key abandons it, the way it does on the keyboard — otherwise a
                        // finger parked outside the arrow would keep the cursor running.
                        val inside = event.x >= 0f && event.y >= 0f &&
                            event.x <= size.width && event.y <= size.height
                        if (isPressed && !inside) {
                            isPressed = false
                            dispatcher.sendCancel(keyData)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        isPressed = false
                        dispatcher.sendUp(keyData)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        isPressed = false
                        dispatcher.sendCancel(keyData)
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            content != null -> content()
            imageVector != null -> SnyggIcon(attributes = attributes, imageVector = imageVector)
            else -> SnyggText(text = label)
        }
    }
}
