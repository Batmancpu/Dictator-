/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.ProviderListing
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggListItem
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * Switches the transcription provider from the keyboard (issue #431), without the trip into the settings.
 *
 * Deliberately the keyboard-language picker's twin below its header: the same bottom sheet, the same list
 * elements, the same radio marks, and a tap on a row is the choice — no OK to confirm it, because the picker is a shortcut and
 * a tap beside it is already the way to change one's mind. It offers what the settings picker offers
 * ([ProviderListing.transcriptionChoices]), so a provider that could only answer "no API key" is never on it,
 * and ends like that picker too, with the way to set up another.
 */
@Composable
fun SelectTranscriptionProviderPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val keyboardManager by context.keyboardManager()
    val inputFeedbackController = LocalInputFeedbackController.current

    val selectedId by prefs.dictate.transcriptionProviderId.collectAsState()
    val accounts by prefs.dictate.providerAccounts.collectAsState()
    val choices = remember(accounts, selectedId) {
        ProviderListing.transcriptionChoices(accounts, selectedId) { LocalModelManager.isInstalled(context, it) }
    }

    fun close() {
        keyboardManager.activeState.isTranscriptionProviderSelectionVisible = false
    }

    SnyggColumn(FlorisImeUi.SubtypePanel.elementName, modifier = modifier.safeDrawingPadding()) {
        // The header is the actions editor's, back arrow included, because both are opened from the Smartbar
        // actions: the two sheets reached from there look like one family. The list below stays the
        // language picker's.
        SnyggRow(
            elementName = FlorisImeUi.SmartbarActionsEditorHeader.elementName,
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Extra box wrapper is needed to enforce size constraint but still allow for Snygg margin to be used
            Box(modifier = Modifier.size(48.dp)) {
                SnyggIconButton(
                    elementName = FlorisImeUi.SmartbarActionsEditorHeaderButton.elementName,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                    onClick = { close() },
                ) {
                    SnyggIcon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft)
                }
            }
            SnyggText(
                modifier = Modifier.weight(1f),
                text = stringRes(R.string.quick_action__dictate_switch_provider),
            )
            Spacer(Modifier.size(48.dp))
        }

        SnyggBox(FlorisImeUi.SubtypePanelList.elementName) {
            LazyColumn {
                items(choices, key = { it.first }) { (id, label) ->
                    SnyggListItem(
                        elementName = FlorisImeUi.SubtypePanelListItem.elementName,
                        onClick = {
                            inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                            DictateController.setTranscriptionProvider(id)
                            close()
                        },
                        leadingImageVector = if (id == selectedId) {
                            Icons.Default.RadioButtonChecked
                        } else {
                            Icons.Default.RadioButtonUnchecked
                        },
                        text = label,
                    )
                }
                item(key = "add") {
                    SnyggListItem(
                        elementName = FlorisImeUi.SubtypePanelListItem.elementName,
                        onClick = {
                            inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                            close()
                            DictateController.openProviderSettings(context, addNew = true)
                        },
                        leadingImageVector = Icons.Default.Add,
                        text = stringRes(R.string.dictate__providers_add),
                    )
                }
            }
        }
    }
}
