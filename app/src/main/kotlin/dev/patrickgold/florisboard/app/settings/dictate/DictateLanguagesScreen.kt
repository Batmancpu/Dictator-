/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.dictate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.DictateLanguages
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import dev.patrickgold.jetpref.material.ui.JetPrefListItem
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.florisDialogScroll
import org.florisboard.lib.compose.stringRes

/**
 * Which languages the user dictates in, which of them is active, and whether switching the keyboard's
 * language picks among them. The selection is stored comma-separated in [prefs.dictate.inputLanguages];
 * catalog order is preserved and at least one language always stays selected.
 */
@Composable
fun DictateLanguagesScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__languages_title)
    previewFieldVisible = true

    val prefs by FlorisPreferenceStore

    content {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val selectionRaw by prefs.dictate.inputLanguages.collectAsState()
        val selection = remember(selectionRaw) { DictateLanguages.parseSelection(selectionRaw) }
        val selectedCodes = remember(selection) { selection.map { it.code }.toSet() }
        // The currently active language (what the recording bar's globe cycles and what is sent to the
        // model). Selectable here too, so floating-button-only users can change it without the keyboard
        // (issue #174 follow-up).
        val activeCode by prefs.dictate.activeInputLanguage.collectAsState()
        val enabledLanguages = remember(selectedCodes) { DictateLanguages.all.filter { it.code in selectedCodes } }
        var showLanguagesPicker by remember { mutableStateOf(false) }
        var showActivePicker by remember { mutableStateOf(false) }

        fun saveSelection(codes: Set<String>) {
            // Preserve catalog order and never allow an empty selection.
            val ordered = DictateLanguages.all.filter { it.code in codes }
                .ifEmpty { listOf(DictateLanguages.of(DictateLanguages.DETECT)) }
            scope.launch {
                prefs.dictate.inputLanguages.set(DictateLanguages.serializeSelection(ordered))
                // Keep the active language consistent: if it's no longer selected (e.g. the user just
                // disabled auto-detect while it was active), snap to the first entry — otherwise a stale
                // "detect" would silently keep dictation on auto-detect and show a phantom globe.
                val active = prefs.dictate.activeInputLanguage.get()
                if (ordered.none { it.code == active }) {
                    prefs.dictate.activeInputLanguage.set(ordered.first().code)
                }
            }
        }

        val followsKeyboard by prefs.dictate.languageFollowsKeyboard.collectAsState()
        val subtypeManager by context.subtypeManager()
        fun setFollowsKeyboard(on: Boolean) {
            scope.launch {
                prefs.dictate.languageFollowsKeyboard.set(on)
                if (on) DictateController.followKeyboardLanguage(subtypeManager.activeSubtype.primaryLocale.base)
            }
        }

        val detectLabel = stringRes(R.string.dictate__language_detect)
        fun languageLabel(code: String): String =
            if (code == DictateLanguages.DETECT) detectLabel else DictateLanguages.of(code).displayName()

        Text(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            text = stringRes(R.string.dictate__languages_summary),
            color = LocalContentColor.current.copy(alpha = 0.7f),
        )
        // Issue #431 follow-up. The selection used to be the screen itself, a checkbox on each of a hundred
        // rows, so a language ticked by accident on the way past went unseen, while it joined the recording
        // bar's cycle, the set auto-detect hands the provider (#99) and the languages the keyboard switch
        // below may pick. Now the names are one line, and the list sits behind it in a dialog whose Cancel
        // takes a stray tick back.
        JetPrefListItem(
            modifier = Modifier.clickable { showLanguagesPicker = true },
            icon = { Icon(Icons.Default.Translate, contentDescription = null) },
            text = stringRes(R.string.dictate__languages_selected_title),
            secondaryText = selection.joinToString(", ") { languageLabel(it.code) },
        )
        // Directly pick the active language (mirrors the recording-bar globe).
        JetPrefListItem(
            modifier = Modifier.clickable { showActivePicker = true },
            icon = { Icon(Icons.Default.Language, contentDescription = null) },
            text = stringRes(R.string.dictate__languages_active_title),
            secondaryText = languageLabel(activeCode),
        )
        // Issue #431. Turning it on follows the keyboard's current language straight away, so the effect
        // is visible where the switch is; after that only a switch of the keyboard's language moves it.
        JetPrefListItem(
            modifier = Modifier
                .settingsSearchAnchor("dictate__languages_follow_keyboard")
                .clickable { setFollowsKeyboard(!followsKeyboard) },
            icon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
            text = stringRes(R.string.dictate__languages_follow_keyboard),
            secondaryText = stringRes(R.string.dictate__languages_follow_keyboard_summary),
            trailing = {
                Switch(checked = followsKeyboard, onCheckedChange = { setFollowsKeyboard(it) })
            },
        )

        if (showLanguagesPicker) {
            // Fixed for as long as the dialog is open: auto-detect and the user's own languages on top, so
            // what is on can be seen and taken off without scrolling, then everything else. A row never
            // moves under the finger that ticked it; the next opening puts it in its new group.
            val (mine, rest) = remember {
                DictateLanguages.sortedForDisplay(DictateLanguages.all)
                    .map { it.code to languageLabel(it.code) }
                    .partition { (code, _) -> code == DictateLanguages.DETECT || code in selectedCodes }
            }
            var draft by remember { mutableStateOf(selectedCodes) }
            JetPrefAlertDialog(
                scrollModifier = florisDialogScroll(),
                title = stringRes(R.string.dictate__languages_selected_title),
                confirmLabel = stringRes(R.string.action__ok),
                onConfirm = {
                    saveSelection(draft)
                    showLanguagesPicker = false
                },
                dismissLabel = stringRes(R.string.action__cancel),
                onDismiss = { showLanguagesPicker = false },
            ) {
                Column {
                    (mine + rest).forEachIndexed { index, (code, label) ->
                        if (index == mine.size) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        LanguageCheckRow(label = label, checked = code in draft) { checked ->
                            draft = if (checked) draft + code else draft - code
                        }
                    }
                }
            }
        }

        if (showActivePicker) {
            fun pick(code: String) {
                scope.launch { prefs.dictate.activeInputLanguage.set(code) }
                showActivePicker = false
            }
            // Rows spaced like the active-provider pickers: the radio button's own touch target is the
            // row's height, with no padding on top of it.
            JetPrefAlertDialog(
                scrollModifier = florisDialogScroll(),
                title = stringRes(R.string.dictate__languages_active_title),
                dismissLabel = stringRes(android.R.string.cancel),
                onDismiss = { showActivePicker = false },
            ) {
                Column {
                    enabledLanguages.forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pick(lang.code) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = lang.code == activeCode, onClick = { pick(lang.code) })
                            Text(text = languageLabel(lang.code), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * One language in the selection dialog, spaced like the rows of the active-provider pickers. The whole row
 * toggles it: a bare checkbox is a small target in a list this long.
 */
@Composable
private fun LanguageCheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}
