/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.keyboard

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.settings.dictate.readText
import dev.patrickgold.florisboard.app.settings.dictate.writeText
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.dictate.symbols.CustomSymbols
import dev.patrickgold.florisboard.dictate.symbols.CustomSymbolsJson
import dev.patrickgold.florisboard.dictate.symbols.CustomSymbolsStore
import dev.patrickgold.florisboard.dictate.symbols.SymbolDefaults
import dev.patrickgold.florisboard.dictate.symbols.SymbolKey
import dev.patrickgold.florisboard.dictate.symbols.SymbolPalette
import dev.patrickgold.florisboard.dictate.symbols.SymbolText
import dev.patrickgold.florisboard.dictate.symbols.SymbolValue
import dev.patrickgold.florisboard.ime.popup.LONG_LABEL_MAX_LINES
import dev.patrickgold.florisboard.ime.popup.labelShrinkRatio
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.compose.FlorisIconButton
import org.florisboard.lib.compose.florisDialogScroll
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.OneLineFirstAutoSize

/**
 * The editor for the user's own symbol pages (issue #342): the page as it will look, a tap on a key to
 * change it. The keyboard's own geometry is fixed here and nothing the user does can disturb it — the
 * digit row, the page switch, backspace and the bottom row are drawn for orientation only.
 */
@Composable
fun CustomSymbolsScreen() = FlorisScreen {
    title = stringRes(R.string.settings__custom_symbols__title)
    // Lets the result be typed on right here, both pages included.
    previewFieldVisible = true
    iconSpaceReserved = false

    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subtypeManager by context.subtypeManager()
    val symbols by CustomSymbolsStore.symbols.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { CustomSymbolsStore.ensureLoaded(context) }
    }

    var page by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var pendingImport by remember { mutableStateOf<CustomSymbols?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    // The currency keys show what they type in the language in use now, as the keyboard does.
    val currencySet = remember { subtypeManager.getCurrencySet(subtypeManager.activeSubtype) }
    val labelOf: (SymbolValue) -> String = { value ->
        when (value) {
            is SymbolValue.Text -> value.text
            is SymbolValue.Currency -> currencySet.getSlot(KeyCode.CURRENCY_SLOT_1 - (value.slot - 1))
                ?.asString(isForDisplay = true) ?: "¤"
        }
    }

    fun write(updated: CustomSymbols) {
        scope.launch { CustomSymbolsStore.set(context, updated) }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) { readText(context, uri)?.let(CustomSymbolsJson::decode) }
            if (read == null) {
                context.showLongToast(R.string.custom_symbols__import_invalid)
            } else {
                pendingImport = read
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = CustomSymbolsJson.encode(symbols, full = true)
            val ok = withContext(Dispatchers.IO) { writeText(context, uri, text) }
            context.showLongToast(if (ok) R.string.dictate__file_export_done else R.string.dictate__file_export_failed)
        }
    }

    actions {
        var menuExpanded by remember { mutableStateOf(false) }
        FlorisIconButton(onClick = { menuExpanded = true }, icon = Icons.Default.MoreVert)
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                text = { Text(stringRes(R.string.action__import)) },
                onClick = {
                    menuExpanded = false
                    importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                text = { Text(stringRes(R.string.action__export)) },
                onClick = {
                    menuExpanded = false
                    exportLauncher.launch(context.getString(R.string.custom_symbols__export_filename))
                },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                text = { Text(stringRes(R.string.custom_symbols__reset_all)) },
                enabled = symbols.changed.isNotEmpty(),
                onClick = {
                    menuExpanded = false
                    confirmReset = true
                },
            )
        }
    }

    content {
        SwitchPreference(
            prefs.keyboard.customSymbolsEnabled,
            modifier = Modifier.settingsSearchAnchor("pref__keyboard__custom_symbols__label"),
            title = stringRes(R.string.pref__keyboard__custom_symbols__label),
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Named like the keys that open each page, which is how the user knows them.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val pageKeys = listOf(R.string.key__view_symbols, R.string.key__view_symbols2)
                pageKeys.forEachIndexed { index, labelRes ->
                    SegmentedButton(
                        selected = page == index,
                        onClick = { page = index },
                        shape = SegmentedButtonDefaults.itemShape(index, pageKeys.size),
                    ) {
                        Text(stringRes(labelRes))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SymbolPagePreview(
                page = page,
                symbols = symbols,
                labelOf = labelOf,
                onKeyClick = { editing = it },
            )
        }

        editing?.let { index ->
            SymbolKeyDialog(
                key = symbols.key(page, index),
                default = SymbolDefaults.key(page, index),
                isChanged = symbols.isChanged(page, index),
                labelOf = labelOf,
                onDismiss = { editing = null },
                onSave = { key ->
                    editing = null
                    write(symbols.with(page, index, key))
                },
            )
        }

        pendingImport?.let { imported ->
            JetPrefAlertDialog(
                title = stringRes(R.string.action__import),
                confirmLabel = stringRes(R.string.action__import),
                dismissLabel = stringRes(R.string.action__cancel),
                onConfirm = {
                    pendingImport = null
                    write(imported)
                    // Someone importing a layout means to type with it.
                    scope.launch { prefs.keyboard.customSymbolsEnabled.set(true) }
                    scope.launch { context.showLongToast(R.string.custom_symbols__import_done) }
                },
                onDismiss = { pendingImport = null },
            ) {
                Text(stringRes(R.string.custom_symbols__import_confirm))
            }
        }

        if (confirmReset) {
            JetPrefAlertDialog(
                title = stringRes(R.string.custom_symbols__reset_all),
                confirmLabel = stringRes(R.string.action__reset),
                dismissLabel = stringRes(R.string.action__cancel),
                onConfirm = {
                    confirmReset = false
                    write(CustomSymbols())
                },
                onDismiss = { confirmReset = false },
            ) {
                Text(stringRes(R.string.custom_symbols__reset_all_confirm))
            }
        }
    }
}

/**
 * One page drawn the way the keyboard lays it out: the same rows, the 9-key row centred like the
 * letters' middle row, the last row between the page switch and backspace.
 */
@Composable
private fun SymbolPagePreview(
    page: Int,
    symbols: CustomSymbols,
    labelOf: (SymbolValue) -> String,
    onKeyClick: (Int) -> Unit,
) {
    val rowModifier = Modifier.fillMaxWidth().height(52.dp)
    Column(Modifier.fillMaxWidth()) {
        if (page == 0) {
            Row(rowModifier) {
                "1234567890".forEach { FixedKey(1f, it.toString()) }
            }
        }
        var index = 0
        val rows = CustomSymbols.PAGE_ROWS[page]
        rows.forEachIndexed { r, size ->
            val first = index
            Row(rowModifier) {
                val isLast = r == rows.lastIndex
                if (isLast) {
                    FixedKey(1.5f, stringRes(if (page == 0) R.string.key__view_symbols2 else R.string.key__view_symbols))
                } else if (size < 10) {
                    Spacer(Modifier.weight((10f - size) / 2f))
                }
                for (i in first until first + size) {
                    val key = symbols.key(page, i)
                    SymbolPreviewKey(
                        label = labelOf(key.value),
                        hint = key.longPress.firstOrNull()?.let(labelOf),
                        changed = symbols.isChanged(page, i),
                        onClick = { onKeyClick(i) },
                    )
                }
                if (isLast) {
                    FixedKey(1.5f, icon = Icons.AutoMirrored.Outlined.Backspace)
                } else if (size < 10) {
                    Spacer(Modifier.weight((10f - size) / 2f))
                }
            }
            index += size
        }
        Row(rowModifier) {
            FixedKey(1.5f, stringRes(R.string.key__view_characters))
            FixedKey(1f, if (page == 0) "," else "<")
            FixedKey(1f, stringRes(R.string.key__view_numeric).replace("\n", " "))
            FixedKey(4f, "")
            FixedKey(1f, if (page == 0) "." else ">")
            FixedKey(1.5f, icon = Icons.AutoMirrored.Outlined.KeyboardReturn)
        }
    }
}

@Composable
private fun RowScope.SymbolPreviewKey(label: String, hint: String?, changed: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            // A changed key is the user's own; the rest are still the defaults.
            .background(if (changed) colors.primaryContainer else colors.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Wraps and shrinks like the key on the keyboard does (see labelShrinkRatio).
        val long = labelShrinkRatio(label) != null
        BasicText(
            text = label,
            modifier = Modifier.padding(horizontal = 2.dp),
            style = TextStyle(
                color = if (changed) colors.onPrimaryContainer else colors.onSurface,
                textAlign = TextAlign.Center,
            ),
            maxLines = if (long) LONG_LABEL_MAX_LINES else 1,
            autoSize = if (long) {
                OneLineFirstAutoSize(maxFontSize = 20.sp, oneLineMinFontSize = 11.sp, minFontSize = 5.sp)
            } else {
                TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 20.sp)
            },
        )
        if (hint != null) {
            BasicText(
                text = hint,
                modifier = Modifier.align(Alignment.TopEnd).padding(start = 6.dp, end = 3.dp, top = 1.dp),
                style = TextStyle(color = colors.onSurfaceVariant),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 5.sp, maxFontSize = 9.sp),
            )
        }
    }
}

@Composable
private fun RowScope.FixedKey(weight: Float, label: String = "", icon: ImageVector? = null) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        val muted = colors.onSurface.copy(alpha = 0.38f)
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
        } else {
            BasicText(
                text = label,
                style = TextStyle(color = muted),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 16.sp),
            )
        }
    }
}

/**
 * Editing one key: what a tap types, what holding it offers, and symbols to pick both from. An empty
 * symbol is the default again (the key never goes blank); [onSave] with null resets the whole key.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolKeyDialog(
    key: SymbolKey,
    default: SymbolKey,
    isChanged: Boolean,
    labelOf: (SymbolValue) -> String,
    onDismiss: () -> Unit,
    onSave: (SymbolKey?) -> Unit,
) {
    // A currency key shows what it types now and stays a currency key for as long as that is left alone.
    val currencyLabel = (key.value as? SymbolValue.Currency)?.let(labelOf)
    var primary by remember { mutableStateOf(currencyLabel ?: (key.value as SymbolValue.Text).text) }
    val keepsCurrency = currencyLabel != null && primary == currencyLabel
    val longPress = remember { key.longPress.toMutableStateList() }
    var adding by remember { mutableStateOf("") }
    var toLongPress by remember { mutableStateOf(false) }

    fun addToLongPress(input: String) {
        for (token in SymbolText.tokens(input)) {
            val value = SymbolValue.Text(token)
            if (value !in longPress && longPress.size < CustomSymbols.MAX_LONG_PRESS) longPress.add(value)
        }
    }

    JetPrefAlertDialog(
        modifier = Modifier.fillMaxWidth(0.96f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        scrollModifier = florisDialogScroll(),
        title = stringRes(R.string.custom_symbols__edit_key),
        confirmLabel = stringRes(R.string.action__ok),
        dismissLabel = stringRes(R.string.action__cancel),
        neutralLabel = if (isChanged) stringRes(R.string.action__default) else null,
        onConfirm = {
            // Whatever is still in the add field was meant to be added.
            addToLongPress(adding)
            val value = when {
                keepsCurrency -> key.value
                else -> SymbolText.clean(primary)?.let { SymbolValue.Text(it) } ?: default.value
            }
            onSave(SymbolKey(value, SymbolText.longPress(longPress)))
        },
        onDismiss = onDismiss,
        onNeutral = { onSave(null) },
    ) {
        Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = primary,
                // A key holds no spaces; that is what lets a space separate the long presses below.
                onValueChange = { primary = it.filterNot(Char::isWhitespace) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall,
                label = { Text(stringRes(R.string.custom_symbols__key)) },
                // Emptied, the key is its default again; this is what that will be.
                placeholder = { Text(labelOf(default.value)) },
                // The one thing the field cannot show: that this sign changes with the language.
                supportingText = if (keepsCurrency) {
                    { Text(stringRes(R.string.custom_symbols__currency)) }
                } else {
                    null
                },
            )

            Text(
                text = stringRes(R.string.custom_symbols__long_press),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            LongPressChips(longPress, labelOf)
            OutlinedTextField(
                value = adding,
                onValueChange = { adding = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                placeholder = { Text(stringRes(R.string.custom_symbols__add_placeholder)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addToLongPress(adding); adding = "" }),
                trailingIcon = {
                    IconButton(
                        onClick = { addToLongPress(adding); adding = "" },
                        enabled = adding.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringRes(R.string.action__add))
                    }
                },
            )

            Text(
                text = stringRes(R.string.custom_symbols__insert_as),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !toLongPress,
                    onClick = { toLongPress = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringRes(R.string.custom_symbols__target_key)) }
                SegmentedButton(
                    selected = toLongPress,
                    onClick = { toLongPress = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringRes(R.string.custom_symbols__target_long_press)) }
            }
            SymbolPalettePicker(
                onPick = { symbol -> if (toLongPress) addToLongPress(symbol) else primary = symbol },
            )
        }
    }
}

/**
 * The long presses as boxes, in the order the popup offers them. Holding one and dragging it moves it;
 * the first is the one the popup preselects.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LongPressChips(values: SnapshotStateList<SymbolValue>, labelOf: (SymbolValue) -> String) {
    if (values.isEmpty()) return
    // In root coordinates: a chip that moved under the finger is still found by where the finger is.
    val bounds = remember { mutableStateMapOf<SymbolValue, Rect>() }
    var dragged by remember { mutableStateOf<SymbolValue?>(null) }
    val colors = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        values.forEach { value ->
            key(value) {
                Row(
                    modifier = Modifier
                        .onGloballyPositioned { bounds[value] = it.boundsInRoot() }
                        .pointerInput(value) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragged = value },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val origin = bounds[value] ?: return@detectDragGesturesAfterLongPress
                                    val finger = origin.topLeft + change.position
                                    val target = values.indexOfFirst { it != value && bounds[it]?.contains(finger) == true }
                                    val from = values.indexOf(value)
                                    if (target >= 0 && from >= 0) values.add(target, values.removeAt(from))
                                },
                                onDragEnd = { dragged = null },
                                onDragCancel = { dragged = null },
                            )
                        }
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (dragged == value) colors.primaryContainer else colors.surfaceContainerHighest)
                        .height(40.dp)
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(labelOf(value), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { values.remove(value) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringRes(R.string.action__delete),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolPalettePicker(onPick: (String) -> Unit) {
    var category by rememberSaveable { mutableStateOf(SymbolPalette.MATH) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SymbolPalette.entries.forEach { entry ->
            FilterChip(
                selected = entry == category,
                onClick = { category = entry },
                label = { Text(stringRes(entry.titleRes)) },
            )
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        category.symbols.forEach { symbol ->
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { onPick(symbol) },
                contentAlignment = Alignment.Center,
            ) {
                Text(symbol, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private val SymbolPalette.titleRes: Int
    get() = when (this) {
        SymbolPalette.MATH -> R.string.custom_symbols__palette_math
        SymbolPalette.ARROWS -> R.string.custom_symbols__palette_arrows
        SymbolPalette.CURRENCIES -> R.string.custom_symbols__palette_currencies
        SymbolPalette.PUNCTUATION -> R.string.custom_symbols__palette_punctuation
        SymbolPalette.SHAPES -> R.string.custom_symbols__palette_shapes
        SymbolPalette.GREEK -> R.string.custom_symbols__palette_greek
        SymbolPalette.SCRIPTS -> R.string.custom_symbols__palette_scripts
    }

/** The entry on the keyboard settings page, saying whether the user's pages are in use. */
@Composable
fun customSymbolsSummary(): String {
    val prefs by FlorisPreferenceStore
    val enabled by prefs.keyboard.customSymbolsEnabled.collectAsState()
    return stringRes(if (enabled) R.string.state__enabled else R.string.state__disabled)
}
