/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.PanelHeaderButton
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.model.collectAsState as collectPrefAsState
import org.florisboard.lib.compose.onAccent
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText
import kotlin.math.max
import kotlin.math.min

/** How far past "fit the text" the photo may be pushed. Beyond this the pixels have nothing left. */
private const val MaxZoom = 8f

/** The recognised text is fitted to this much of the canvas, leaving a margin to show it has edges. */
private const val InitialFill = 0.92f

/** A tap this far from a line still counts as that line — small print on a photo is a small target. */
private val TapSlop = 20.dp

/**
 * How tall the panel is — which is a question only a photograph can answer.
 *
 * Every other panel is locked to [FlorisImeSizing.panelUiHeight] so the window never jumps, and this one
 * stays there too for everything that is not a photo: the empty state, the spinner, an error. It grows
 * **only while a picture is actually on screen**, and only as far as that picture needs — a page held
 * upright wants the room, a receipt photographed sideways does not, and taking the height anyway would
 * be a keyboard that swallowed the screen for nothing.
 *
 * "As far as it needs" is the height at which the photo fills the panel's width, plus the two rows of
 * chrome. Width is taken from the screen, which is the keyboard's width in every layout but the
 * one-handed and split ones — being a little generous there costs nothing, because the cap below is
 * what actually decides the outcome for tall images.
 *
 * The cap keeps it honest in landscape and on small screens, where 60 % is already a lot of keyboard
 * (issues #361/#362).
 */
private const val ScanHeightMaxScreenFraction = 0.6f

@Composable
private fun scanPanelHeight(session: ScanController.Session?): Dp {
    val base = FlorisImeSizing.panelUiHeight()
    val ready = session as? ScanController.Session.Ready ?: return base
    val bitmap = ready.bitmap
    if (bitmap.width <= 0 || bitmap.height <= 0) return base
    val configuration = LocalConfiguration.current
    val chrome = FlorisImeSizing.smartbarHeight * 2
    val needed = configuration.screenWidthDp.dp * (bitmap.height.toFloat() / bitmap.width) + chrome
    val ceiling = configuration.screenHeightDp.dp * ScanHeightMaxScreenFraction
    return needed.coerceIn(base, maxOf(base, ceiling))
}

/**
 * Scan text (issue #390): the photo just taken, with every recognised line drawn as a tappable region
 * over it, and the tapped ones joined into the text that gets inserted.
 *
 * **Why the photo and not a list.** A list of recognised lines would be less work and would fit the
 * panel more comfortably — but a scan of a form, an invoice or a router label comes back as twenty
 * near-identical strings, and picking the right one out of them means reading all twenty. On the photo
 * the answer is where the user just saw it. The price is room, and it is paid three ways: the panel grows
 * for a photo that needs it (see [scanPanelHeight]); the view opens zoomed to the *text* rather than to
 * the whole picture; and a tap that lands near a line rather than on it still counts.
 *
 * **Why nothing is drawn from a stylesheet that does not exist.** No `scan-*` Snygg element is
 * registered. Every element here is one the bundled themes already style, for the reason spelled out in
 * `EditingPanel`: an element with no rule draws with no colour at all, and third-party themes would show
 * a blank panel.
 */
@Composable
fun ScanPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val prefs by FlorisPreferenceStore
    val accent by prefs.theme.accentColor.collectPrefAsState()
    val session by ScanController.session.collectAsState()
    val selection by ScanController.selection.collectAsState()

    SnyggColumn(
        elementName = FlorisImeUi.Media.elementName,
        modifier = modifier
            .fillMaxWidth()
            .height(scanPanelHeight(session)),
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
            // No camera or gallery button up here. They were the only way back to a new photo while a
            // scan was on screen — until "discard" took that job, which leaves them saying a second time
            // what the two buttons in the empty state already say. Retaking costs one extra tap now
            // (discard, then take), and that is the rarer path.
            SnyggText(
                elementName = FlorisImeUi.ClipboardHeaderText.elementName,
                modifier = Modifier.weight(1f),
                text = stringRes(R.string.quick_action__ime_ui_mode_scan),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when (val current = session) {
                null -> ScanEmptyState()
                is ScanController.Session.Working -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
                    SnyggText(
                        elementName = FlorisImeUi.MediaEmojiSubheader.elementName,
                        modifier = Modifier.padding(top = 10.dp),
                        text = stringRes(R.string.scan__reading),
                    )
                }
                is ScanController.Session.Failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    SnyggText(
                        elementName = FlorisImeUi.MediaEmojiSubheader.elementName,
                        text = stringRes(current.messageRes),
                    )
                    ScanTile(
                        label = stringRes(R.string.scan__retake),
                        icon = Icons.Outlined.PhotoCamera,
                        modifier = Modifier.padding(top = 10.dp),
                        onClick = { ScanController.startCapture(context) },
                    )
                }
                is ScanController.Session.Ready -> ScanCanvas(
                    ready = current,
                    selection = selection,
                    accent = accent,
                )
            }
        }

        ScanActionRow(
            session = session,
            selection = selection,
            accent = accent,
            onApprove = { text ->
                ScanController.insert(context, text)
                ScanController.clear()
                keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
            },
            onDiscard = { ScanController.clear() },
        )
    }
}

/** Before there is anything to show: what this panel is for, and the two ways to give it something. */
@Composable
private fun ScanEmptyState() {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        SnyggText(
            elementName = FlorisImeUi.MediaEmojiSubheader.elementName,
            text = stringRes(R.string.scan__empty_hint),
            maxLines = 2,
        )
        Row(modifier = Modifier.padding(top = 10.dp)) {
            ScanTile(
                label = stringRes(R.string.scan__take_photo),
                icon = Icons.Outlined.PhotoCamera,
                modifier = Modifier.padding(end = 8.dp),
                onClick = { ScanController.startCapture(context) },
            )
            ScanTile(
                label = stringRes(R.string.scan__pick_image),
                icon = Icons.Outlined.PhotoLibrary,
                onClick = { ScanController.startCapture(context, pickExisting = true) },
            )
        }
        // The recogniser reads Latin script and nothing else. Saying so quietly here is cheaper than
        // letting somebody photograph a Chinese menu and conclude the feature is broken.
        SnyggText(
            elementName = FlorisImeUi.MediaEmojiSubheader.elementName,
            modifier = Modifier.padding(top = 8.dp),
            fontSizeMultiplier = 0.8f,
            text = stringRes(R.string.scan__latin_only),
            maxLines = 1,
        )
    }
}

/** A labelled button in the panel's body, borrowing the Smartbar action tile's colours. */
@Composable
private fun ScanTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val inputFeedbackController = LocalInputFeedbackController.current
    SnyggBox(
        elementName = FlorisImeUi.SmartbarActionTile.elementName,
        modifier = modifier,
        clickAndSemanticsModifier = Modifier.clickable {
            inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
            onClick()
        },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            SnyggIcon(
                elementName = FlorisImeUi.SmartbarActionTileIcon.elementName,
                imageVector = icon,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(18.dp),
            )
            SnyggText(
                elementName = FlorisImeUi.SmartbarActionTileText.elementName,
                text = label,
                maxLines = 1,
            )
        }
    }
}

/**
 * The photo, the regions over it, and the two gestures that make a panel this short usable.
 *
 * The transform is kept as a zoom factor and a pan offset rather than a matrix, because the same two
 * numbers have to answer both questions: where to draw a line, and which line a tap landed on. A matrix
 * would need inverting for the second, and an inverted matrix that disagrees with the drawn one by half
 * a pixel is a panel where taps miss for reasons nobody can see.
 */
@Composable
private fun ScanCanvas(
    ready: ScanController.Session.Ready,
    selection: Set<Int>,
    accent: Color,
) {
    val inputFeedbackController = LocalInputFeedbackController.current
    val image = remember(ready.bitmap) { ready.bitmap.asImageBitmap() }
    val imageSize = remember(image) { Size(image.width.toFloat(), image.height.toFloat()) }

    var zoom by remember(ready) { mutableStateOf(0f) } // 0 = "not laid out yet, fit the text"
    var pan by remember(ready) { mutableStateOf(Offset.Zero) }
    var canvasSize by remember(ready) { mutableStateOf(Size.Zero) }

    val slopPx = with(LocalDensity.current) { TapSlop.toPx() }

    /** Where the image's top-left corner sits on the canvas, and how big it is drawn. */
    fun geometry(): Pair<Offset, Size> {
        val base = min(canvasSize.width / imageSize.width, canvasSize.height / imageSize.height)
        val effective = base * max(zoom, 1f)
        val drawn = Size(imageSize.width * effective, imageSize.height * effective)
        val topLeft = Offset(
            (canvasSize.width - drawn.width) / 2f + pan.x,
            (canvasSize.height - drawn.height) / 2f + pan.y,
        )
        return topLeft to drawn
    }

    /** Keeps the photo from being dragged out of the window entirely. */
    fun clampPan(candidate: Offset, drawn: Size): Offset {
        val maxX = max(0f, (drawn.width - canvasSize.width) / 2f)
        val maxY = max(0f, (drawn.height - canvasSize.height) / 2f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    /** Opens on the recognised text rather than on the photo — the first thing that makes this usable. */
    fun fitToText() {
        val bounds = ready.scan.bounds()
        val base = min(canvasSize.width / imageSize.width, canvasSize.height / imageSize.height)
        if (bounds == null || base <= 0f) {
            zoom = 1f
            pan = Offset.Zero
            return
        }
        val widthNorm = (bounds[2] - bounds[0]).coerceAtLeast(0.02f)
        val heightNorm = (bounds[3] - bounds[1]).coerceAtLeast(0.02f)
        val fitted = min(
            canvasSize.width * InitialFill / (imageSize.width * base * widthNorm),
            canvasSize.height * InitialFill / (imageSize.height * base * heightNorm),
        )
        zoom = fitted.coerceIn(1f, MaxZoom)
        val drawn = Size(imageSize.width * base * zoom, imageSize.height * base * zoom)
        val centre = Offset((bounds[0] + bounds[2]) / 2f, (bounds[1] + bounds[3]) / 2f)
        pan = clampPan(
            Offset(drawn.width * (0.5f - centre.x), drawn.height * (0.5f - centre.y)),
            drawn,
        )
    }

    /** Which line a tap at [position] means, forgiving a near miss. */
    fun lineAt(position: Offset): Int? {
        val (topLeft, drawn) = geometry()
        if (drawn.width <= 0f || drawn.height <= 0f) return null
        val x = (position.x - topLeft.x) / drawn.width
        val y = (position.y - topLeft.y) / drawn.height
        ready.scan.lines.forEachIndexed { index, line -> if (line.contains(x, y)) return index }
        // Normalised slop: the same physical distance means less of the image the further it is zoomed.
        val slopX = slopPx / drawn.width
        val slopY = slopPx / drawn.height
        val slop = max(slopX, slopY)
        return ready.scan.lines
            .mapIndexed { index, line -> index to line.distanceTo(x, y) }
            .filter { it.second <= slop }
            .minByOrNull { it.second }
            ?.first
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            // Compose does not clip a node's drawing to its bounds, and this one deliberately draws an
            // image bigger than itself — that is what zooming is. Without this the photo spills over the
            // header above and the chip row below, which then show through it half-erased.
            .clipToBounds()
            // The size comes from layout rather than from the draw scope: writing state while drawing
            // schedules another frame to draw the same thing, which is a loop waiting for an excuse.
            .onSizeChanged { measured ->
                val next = Size(measured.width.toFloat(), measured.height.toFloat())
                if (next != canvasSize && next.width > 0f && next.height > 0f) {
                    canvasSize = next
                    if (zoom == 0f) fitToText()
                }
            }
            .pointerInput(ready) {
                detectTapGestures(
                    onTap = { position ->
                        lineAt(position)?.let { index ->
                            inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                            ScanController.toggleLine(index)
                        }
                    },
                    // Holding takes the whole block, which is what an address is: three lines the
                    // recogniser already knows belong together.
                    onLongPress = { position ->
                        lineAt(position)?.let { index ->
                            inputFeedbackController.keyLongPress(TextKeyData.UNSPECIFIED)
                            ScanController.selectBlockOf(index)
                        }
                    },
                    // Deliberately no onDoubleTap. Defining one makes detectTapGestures hold every
                    // single tap back until the double-tap window has passed, and the single tap here
                    // is the whole feature — a fifth of a second of lag on picking a line is a worse
                    // panel than one without a zoom-to-fit shortcut. Pinch already does that job.
                )
            }
            .pointerInput(ready) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val base = min(canvasSize.width / imageSize.width, canvasSize.height / imageSize.height)
                    val next = (max(zoom, 1f) * zoomChange).coerceIn(1f, MaxZoom)
                    val (oldTopLeft, oldDrawn) = geometry()
                    val drawn = Size(imageSize.width * base * next, imageSize.height * base * next)
                    // Anchor the zoom on what is under the fingers: the point of pinching on a line is
                    // that the line stays where it is while it grows.
                    val anchor = if (oldDrawn.width > 0f && oldDrawn.height > 0f) {
                        Offset(
                            (centroid.x - oldTopLeft.x) / oldDrawn.width,
                            (centroid.y - oldTopLeft.y) / oldDrawn.height,
                        )
                    } else {
                        Offset(0.5f, 0.5f)
                    }
                    zoom = next
                    pan = clampPan(
                        Offset(
                            centroid.x - anchor.x * drawn.width - (canvasSize.width - drawn.width) / 2f,
                            centroid.y - anchor.y * drawn.height - (canvasSize.height - drawn.height) / 2f,
                        ) + panChange,
                        drawn,
                    )
                }
            },
    ) {
        if (canvasSize.width <= 0f || canvasSize.height <= 0f) return@Canvas
        val (topLeft, drawn) = geometry()
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
            dstSize = IntSize(drawn.width.toInt().coerceAtLeast(1), drawn.height.toInt().coerceAtLeast(1)),
        )
        ready.scan.lines.forEachIndexed { index, line ->
            val selected = index in selection
            val path = Path().apply {
                moveTo(topLeft.x + line.quad[0] * drawn.width, topLeft.y + line.quad[1] * drawn.height)
                lineTo(topLeft.x + line.quad[2] * drawn.width, topLeft.y + line.quad[3] * drawn.height)
                lineTo(topLeft.x + line.quad[4] * drawn.width, topLeft.y + line.quad[5] * drawn.height)
                lineTo(topLeft.x + line.quad[6] * drawn.width, topLeft.y + line.quad[7] * drawn.height)
                close()
            }
            // An unselected region is a target the user has to find on a photograph that may be
            // anything at all, so it carries an outline as well as a wash — a flat 18% tint vanished
            // on a dark screenshot. Selected is louder still, because it also has to be countable.
            drawPath(path, color = accent.copy(alpha = if (selected) 0.45f else 0.22f))
            drawPath(
                path,
                color = accent.copy(alpha = if (selected) 1f else 0.55f),
                style = Stroke(width = if (selected) 2.5f else 1.5f),
            )
        }
    }
}

/**
 * The controls under the photo: select everything, throw the scan away, take what is marked.
 *
 * It replaced a row that showed the selected text as a preview line plus an edit button, and both went
 * for the same reason: the text is already on screen, in the place the user is looking at and tapping,
 * so repeating it underneath said nothing and cost the width that the controls needed. Nothing is
 * offered here that the photo does not already offer better.
 */
@Composable
private fun ScanActionRow(
    session: ScanController.Session?,
    selection: Set<Int>,
    accent: Color,
    onApprove: (String) -> Unit,
    onDiscard: () -> Unit,
) {
    val ready = session as? ScanController.Session.Ready ?: return
    val hasSelection = selection.isNotEmpty()

    SnyggRow(
        elementName = FlorisImeUi.ClipboardHeader.elementName,
        modifier = Modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.smartbarHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ScanAction(
            label = stringRes(R.string.scan__select_all),
            icon = Icons.Default.SelectAll,
            onClick = { ScanController.selectAll() },
        )
        ScanAction(
            label = stringRes(R.string.scan__discard),
            icon = Icons.Default.Close,
            onClick = onDiscard,
        )
        // Nothing is marked, so there is nothing to take — said by greying it out rather than by
        // answering the tap with silence.
        ScanAction(
            label = stringRes(R.string.scan__approve),
            icon = Icons.Default.Check,
            accent = accent,
            enabled = hasSelection,
            onClick = { onApprove(ScanSelection.join(ready.scan, selection)) },
        )
    }
}

/**
 * One control in the row below the photo.
 *
 * It started as bare text and read as a caption rather than a button — and the disabled state of
 * "approve" was invisible, which is the worst version of that: a thing you can tap that answers with
 * nothing. So each one carries a surface, and the primary action carries the user's accent, which is
 * also how its disabled state announces itself — the colour drains out of it.
 */
@Composable
private fun ScanAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val inputFeedbackController = LocalInputFeedbackController.current
    val selector = if (enabled) null else SnyggSelector.DISABLED
    // The accent at full opacity, or not at all. A low-alpha accent over a dark keyboard composites to a
    // muddy near-black that reads as "some dark button" rather than as the colour the user chose — and,
    // worse here, looks near enough to the disabled state that the two cannot be told apart.
    val filled = accent != null && enabled
    val background = when {
        filled -> accent!!
        accent != null -> Color(0x14808080)
        else -> Color(0x1F808080)
    }
    // Content drawn on a surface this composable painted cannot use the themed foreground, which was
    // resolved against the panel's background instead.
    val content = if (filled) accent!!.onAccent() else null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(enabled = enabled) {
                inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        SnyggIcon(
            elementName = FlorisImeUi.SmartbarActionTileIcon.elementName,
            selector = selector,
            imageVector = icon,
            tint = content,
            modifier = Modifier
                .padding(end = 6.dp)
                .size(18.dp),
        )
        SnyggText(
            elementName = FlorisImeUi.SmartbarActionTileText.elementName,
            selector = selector,
            color = content,
            // Three labels share one row, and "Összes kijelölése" is not going to fit at full size.
            // Shrinking beats clipping: a button that says "Összes kije…" is a button nobody trusts.
            maxLines = 1,
            autoSizeMinRatio = 0.6f,
            text = label,
        )
    }
}
