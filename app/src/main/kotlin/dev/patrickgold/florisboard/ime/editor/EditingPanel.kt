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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
 * How much of the body the labelled action tiles take, against [PadWeight] for the cursor pad.
 *
 * Bounded from below rather than chosen: a tile is [TileIconSize], [TileIconGap] and a line of text —
 * about 47 dp with the tile's own padding, 55 dp with its margin — and anything under that clips the
 * caption on a short keyboard, where a tile captioned with half a word is worse than one with no
 * caption. Everything above that floor belongs to the pad, so this sits just clear of it.
 */
private const val ActionRowWeight = 1.25f

/** The cursor pad is the thing this panel is for, so it gets the room. */
private const val PadWeight = 3f

/**
 * The tile icons, sized here rather than by `smartbar-action-tile-icon`'s 24 sp.
 *
 * That element is written for the Smartbar's overflow grid, where a tile is a square about 88 dp
 * across; in a row a fifth of the screen wide the same icon plus the 8 dp gap the theme puts under it
 * made the row taller than the pad it sits above. The element name is kept, so the colour and the
 * greyed-out state are still the theme's — only the measurement is ours.
 */
private val TileIconSize = 20.dp

/** Between a tile's icon and its caption. Tight, because the two are one label. */
private val TileIconGap = 3.dp

/** The pad's card against the quiet columns beside it — wide enough that the cross is the focal point. */
private const val PadCardWeight = 3.2f

/** The dead centre of the cross, drawn faintly so the four arrows read as one control. */
private val HubDotSize = 7.dp

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
 * keyboard mode would have answered none of them without a layout file per subtype.
 *
 * ## How it is drawn, and why not as seventeen keyboard keys
 *
 * The first cut styled every button as `key`, and seventeen identical raised keys with an icon each is
 * a wall, not a panel — nothing said which of them mattered. It now has three weights, and they are the
 * app's own, borrowed rather than invented: **labelled tiles** (`smartbar-action-tile`, what the
 * Smartbar overflow is made of) for the five things you do *to* a selection; **one tile-coloured card**
 * holding the cross, so the arrows read as a single control; and **borderless keys**
 * (`media-emoji-key`) for everything else, which are quiet until pressed.
 *
 * Reusing those three elements rather than registering `editing-*` ones is what makes it themed
 * everywhere for free: an element with no rule in a stylesheet draws with no colour at all, so a new
 * element name would have meant editing every bundled theme and still leaving third-party themes blank.
 */
@Composable
fun EditingPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val prefs by FlorisPreferenceStore
    val accent by prefs.theme.accentColor.collectPrefAsState()
    val state by keyboardManager.activeState.collectAsState()

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
            SelectionCounterPill(modifier = Modifier.padding(end = 6.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            // Selection and clipboard, in the order they are used: mark something, then do something
            // with it. Labelled, because an icon for "select" and an icon for "select all" are the same
            // dashed square to anybody who has not been told which is which.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(ActionRowWeight),
            ) {
                ActionTile(TextKeyData.CLIPBOARD_SELECT, R.string.quick_action__clipboard_select,
                    Modifier.weight(1f), accent, active = state.isManualSelectionMode)
                ActionTile(TextKeyData.CLIPBOARD_SELECT_ALL, R.string.quick_action__clipboard_select_all,
                    Modifier.weight(1f), accent)
                ActionTile(TextKeyData.CLIPBOARD_CUT, R.string.quick_action__clipboard_cut,
                    Modifier.weight(1f), accent)
                ActionTile(TextKeyData.CLIPBOARD_COPY, R.string.quick_action__clipboard_copy,
                    Modifier.weight(1f), accent)
                ActionTile(TextKeyData.CLIPBOARD_PASTE, R.string.quick_action__clipboard_paste,
                    Modifier.weight(1f), accent)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(PadWeight),
            ) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    PadKey(TextKeyData.UNDO, R.string.quick_action__undo,
                        Modifier.weight(1f).fillMaxWidth())
                    PadKey(TextKeyData.REDO, R.string.quick_action__redo,
                        Modifier.weight(1f).fillMaxWidth())
                    PadKey(TextKeyData.IME_UI_MODE_CLIPBOARD, R.string.quick_action__ime_ui_mode_clipboard,
                        Modifier.weight(1f).fillMaxWidth())
                }
                // The cross on its own card. Its empty bottom corners are what make it read as a
                // direction pad rather than as three more rows of buttons, so they stay empty — and on
                // a card they read as the pad's own margin instead of as holes.
                SnyggBox(
                    elementName = FlorisImeUi.SmartbarActionTile.elementName,
                    modifier = Modifier.weight(PadCardWeight).fillMaxHeight(),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            PadKey(TextKeyData.MOVE_START_OF_PAGE,
                                R.string.quick_action__move_start_of_page,
                                Modifier.weight(1f).fillMaxHeight())
                            PadKey(TextKeyData.ARROW_UP, R.string.quick_action__arrow_up,
                                Modifier.weight(1f).fillMaxHeight())
                            PadKey(TextKeyData.MOVE_END_OF_PAGE, R.string.quick_action__move_end_of_page,
                                Modifier.weight(1f).fillMaxHeight())
                        }
                        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            PadKey(TextKeyData.ARROW_LEFT, R.string.quick_action__arrow_left,
                                Modifier.weight(1f).fillMaxHeight())
                            HubDot(modifier = Modifier.weight(1f).fillMaxHeight())
                            PadKey(TextKeyData.ARROW_RIGHT, R.string.quick_action__arrow_right,
                                Modifier.weight(1f).fillMaxHeight())
                        }
                        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            Spacer(modifier = Modifier.weight(1f).fillMaxHeight())
                            PadKey(TextKeyData.ARROW_DOWN, R.string.quick_action__arrow_down,
                                Modifier.weight(1f).fillMaxHeight())
                            Spacer(modifier = Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    PadKey(TextKeyData.DELETE, R.string.dictate__legacy_backspace,
                        Modifier.weight(1f).fillMaxWidth())
                    PadKey(TextKeyData.FORWARD_DELETE, R.string.quick_action__forward_delete,
                        Modifier.weight(1f).fillMaxWidth())
                    // A second way back to typing, where a thumb already is. The header's arrow is the
                    // other one, and it is a long reach on a tall phone. "ABC" rather than an icon,
                    // because that is what the emoji panel's way out says.
                    PadKey(TextKeyData.IME_UI_MODE_TEXT, R.string.editing__back_to_keyboard,
                        Modifier.weight(1f).fillMaxWidth()) {
                        SnyggText(text = "ABC", fontSizeMultiplier = 0.62f)
                    }
                }
            }
        }
    }
}

/**
 * The dead centre of the cross. Not a button and never was — it is there so the four arrows around it
 * read as one direction pad instead of as four buttons that happen to be near each other.
 */
@Composable
private fun HubDot(modifier: Modifier = Modifier) {
    val color = rememberSnyggThemeQuery(FlorisImeUi.MediaEmojiKey.elementName)
        .foreground(default = Color.Gray)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(HubDotSize)
                .background(color.copy(alpha = 0.22f), CircleShape),
        )
    }
}

/**
 * One of the five things you do *to* a selection: an icon over its name, in the tile the Smartbar
 * overflow is made of.
 *
 * [active] marks a toggle that is currently on — only `Select` ever is. It is painted with the user's
 * accent rather than with a second icon, because "the arrows now drag a selection" is a state of the
 * keyboard, not a different action.
 */
@Composable
private fun ActionTile(
    keyData: TextKeyData,
    labelRes: Int,
    modifier: Modifier = Modifier,
    accent: Color,
    active: Boolean = false,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val evaluator by keyboardManager.activeEvaluator.collectAsState()
    val label = stringRes(labelRes)
    val attributes = remember(keyData) { mapOf(FlorisImeUi.Attr.Code to keyData.code) }
    val imageVector: ImageVector? = evaluator.computeImageVector(keyData)
    // Cut and copy with nothing selected, paste with an empty clipboard: the evaluator has always known
    // these, the Smartbar has always greyed them out, and a panel that pretends they are available is a
    // panel that answers a tap with nothing.
    val enabled = evaluator.evaluateEnabled(keyData)
    val selector = if (enabled) null else SnyggSelector.DISABLED

    EditingKey(
        keyData = keyData,
        label = label,
        elementName = FlorisImeUi.SmartbarActionTile.elementName,
        modifier = modifier.fillMaxHeight(),
        accent = accent,
        active = active,
        enabled = enabled,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (imageVector != null) {
                SnyggIcon(
                    elementName = FlorisImeUi.SmartbarActionTileIcon.elementName,
                    attributes = attributes,
                    selector = selector,
                    modifier = Modifier.size(TileIconSize),
                    imageVector = imageVector,
                )
                Spacer(modifier = Modifier.height(TileIconGap))
            }
            SnyggText(
                elementName = FlorisImeUi.SmartbarActionTileText.elementName,
                attributes = attributes,
                selector = selector,
                // One line that shrinks rather than two that ellipsize: these names are one word in
                // most languages ("Ausschneiden", "Sélectionner"), and half a word centred under an
                // icon says nothing at all.
                maxLines = 1,
                autoSizeMinRatio = 0.5f,
                text = label,
            )
        }
    }
}

/**
 * A borderless key — quiet until it is pressed. Everything that is not one of the five named actions:
 * the arrows on their card, and the columns of seconds beside it.
 */
@Composable
private fun PadKey(
    keyData: TextKeyData,
    labelRes: Int,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val evaluator by keyboardManager.activeEvaluator.collectAsState()
    val label = stringRes(labelRes)
    val attributes = remember(keyData) { mapOf(FlorisImeUi.Attr.Code to keyData.code) }
    val imageVector: ImageVector? = evaluator.computeImageVector(keyData)

    EditingKey(
        keyData = keyData,
        label = label,
        elementName = FlorisImeUi.MediaEmojiKey.elementName,
        modifier = modifier.padding(2.dp),
    ) {
        when {
            content != null -> content()
            imageVector != null -> SnyggIcon(attributes = attributes, imageVector = imageVector)
            else -> SnyggText(text = label)
        }
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
private fun EditingKey(
    keyData: TextKeyData,
    label: String,
    elementName: String,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified,
    active: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val inputFeedbackController = LocalInputFeedbackController.current
    val attributes = remember(keyData) { mapOf(FlorisImeUi.Attr.Code to keyData.code) }
    // The accent ring follows the element's own shape, so it fits round tiles and square ones alike.
    val shape = rememberSnyggThemeQuery(elementName, attributes).shape()

    var isPressed by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(keyData) {
        // Leaving the panel mid-press must not leave the key down: the dispatcher keys its pressed map
        // by code, and a code left down there swallows every later press of it.
        onDispose { keyboardManager.inputEventDispatcher.sendCancel(keyData) }
    }

    SnyggBox(
        elementName = elementName,
        attributes = attributes,
        selector = when {
            !enabled -> SnyggSelector.DISABLED
            isPressed -> SnyggSelector.PRESSED
            else -> SnyggSelector.NONE
        },
        modifier = modifier.onSizeChanged { size = it },
        clickAndSemanticsModifier = Modifier
            .semantics {
                role = Role.Button
                contentDescription = label
                if (!enabled) disabled()
            }
            .then(
                if (active) {
                    Modifier
                        .background(accent.copy(alpha = 0.26f), shape)
                        .border(2.dp, accent, shape)
                } else {
                    Modifier
                },
            )
            .pointerInteropFilter { event ->
                val dispatcher = keyboardManager.inputEventDispatcher
                // A greyed-out key still swallows the touch, so a miss cannot fall through to whatever
                // is underneath — it simply does nothing, which is what greyed out means.
                if (!enabled) return@pointerInteropFilter true
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
        content = { content() },
    )
}
