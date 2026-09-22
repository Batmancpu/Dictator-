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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.launch
import dev.patrickgold.florisboard.dictate.provider.LocalModelCatalog
import dev.patrickgold.florisboard.dictate.provider.LocalModelDownloads
import dev.patrickgold.florisboard.dictate.provider.LocalModelEntry
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.LocalModelSpec
import kotlin.math.roundToInt
import org.florisboard.lib.compose.stringRes

/**
 * Provider-editor body for the on-device (offline) transcription provider (issue #104). Instead of an
 * API key + remote model picker, it lists the downloadable Whisper models with install / delete /
 * cancel actions and a live download progress bar, and lets the user pick which installed model is
 * active. The active model id is reported via [onActiveModelChange] and persisted by the caller when the
 * dialog is confirmed; installs/deletes take effect immediately on disk.
 *
 * [onModelChosen] is the narrower signal: the user *decided* to use an on-device model, by tapping an
 * installed one or by downloading one while looking at this list (issue #343). It is deliberately not
 * fired by every [onActiveModelChange] — that one also repairs a pick whose model was deleted, and
 * repairing a dangling id is not a decision about anything.
 *
 * What the decision is worth is the caller's to judge: during setup it settles which engine dictates,
 * while later it is just a model being chosen and must not move a working configuration.
 */
@Composable
fun LocalModelSection(
    activeModelId: String,
    activeStreamingModelId: String,
    onActiveModelChange: (String) -> Unit,
    onActiveStreamingModelChange: (String) -> Unit,
    onModelChosen: () -> Unit = {},
) {
    val context = LocalContext.current

    // Downloads run app-scoped (issue #207) so they survive this dialog closing / the app being left; the
    // installed set is recomputed on a local delete tick and whenever a background download finishes.
    var refreshTick by remember { mutableStateOf(0) }
    val installedTick by LocalModelDownloads.installedTick.collectAsState()
    val installed = remember(refreshTick, installedTick) { LocalModelManager.installedIds(context).toSet() }
    val downloads by LocalModelDownloads.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<LocalModelSpec?>(null) }

    val backgroundHint = stringRes(R.string.dictate__local_model_download_background)

    val prefs by FlorisPreferenceStore

    /** The family whose variants are open over this dialog, or null while the first level is showing. */
    var openFamily by remember { mutableStateOf<LocalModelEntry.Family?>(null) }

    val rowState = LocalModelState(
        installed = installed,
        downloads = downloads,
        activeModelId = activeModelId,
        activeStreamingModelId = activeStreamingModelId,
    )
    // Built once and handed to both levels, so a Kroko variant behaves identically whether it is tapped
    // in the list or inside its family's dialog — including which of the two slots it lands in and what
    // the delete dialog afterwards has to repair.
    val rowActions = LocalModelActions(
        onSelect = { spec ->
            if (spec.id in installed) {
                if (spec.isStreaming) onActiveStreamingModelChange(spec.id)
                else onActiveModelChange(spec.id)
                // The row says "tap to use", so it had better be the thing that transcribes.
                onModelChosen()
            }
        },
        onInstall = { spec ->
            LocalModelDownloads.clearError(spec.id)
            LocalModelDownloads.start(context, spec)
            // Tell the user right away that they can leave — it keeps going in the background.
            Toast.makeText(context, backgroundHint, Toast.LENGTH_SHORT).show()
        },
        onCancel = { spec -> LocalModelDownloads.cancel(spec.id) },
        onDelete = { spec -> pendingDelete = spec },
    )

    // A model the user just downloaded is what they want to use, so it is selected the moment it lands —
    // into its own slot, since a streaming and a one-shot model are active side by side and must not
    // evict each other. Diffing against the previously known set is what identifies the *new* one;
    // seeding [known] from the current install state means opening the dialog changes nothing.
    var known by remember { mutableStateOf(LocalModelManager.installedIds(context).toSet()) }
    LaunchedEffect(installedTick, refreshTick) {
        val ids = LocalModelManager.installedIds(context)
        val installedNow = ids.toSet()
        (installedNow - known).forEach { id ->
            if (LocalModelCatalog.isStreaming(id)) onActiveStreamingModelChange(id) else onActiveModelChange(id)
            // Several hundred megabytes are not downloaded by accident, and not while looking at
            // something else: on this page, a model that lands is one the user asked for (issue #343).
            onModelChosen()
        }
        known = installedNow
        // Safety net for a pick that is gone (deleted, or a leftover id from an older version): fall back
        // to something installed of the same kind rather than leaving the slot pointing at nothing.
        val (streamingIds, batchIds) = ids.partition { LocalModelCatalog.isStreaming(it) }
        if (activeModelId.isNotBlank() && activeModelId !in installedNow) {
            onActiveModelChange(batchIds.firstOrNull().orEmpty())
        }
        if (activeStreamingModelId.isNotBlank() && activeStreamingModelId !in installedNow) {
            onActiveStreamingModelChange(streamingIds.firstOrNull().orEmpty())
        }
    }

    Column {
        Text(
            text = stringRes(R.string.dictate__local_models_header),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Long-press "send with local model" shortcut (issue #228): a short explainer + checkbox below the
        // general on-device intro. When on, holding the send button while recording transcribes with the
        // selected on-device model instead of the cloud provider (plain recording only).
        val scope = rememberCoroutineScope()
        // Local state (persisted immediately) — avoids importing the jetpref collectAsState, which would
        // clash by name with the runtime collectAsState already used for the download flows above.
        var longPressLocal by remember { mutableStateOf(prefs.dictate.longPressSendLocalModel.get()) }
        fun setLongPressLocal(value: Boolean) {
            longPressLocal = value
            scope.launch { prefs.dictate.longPressSendLocalModel.set(value) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { setLongPressLocal(!longPressLocal) }
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = longPressLocal,
                onCheckedChange = { setLongPressLocal(it) },
            )
            Text(
                text = stringRes(R.string.dictate__local_longpress_send_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
        }

        // Idle-unload timeout: after how long an idle on-device model is freed from RAM. The model is
        // always also freed on an Android memory-pressure signal; this only covers the "app alive but not
        // dictating" window. 0 = only on memory pressure.
        var unloadMin by remember { mutableStateOf(prefs.dictate.localModelUnloadMinutes.get()) }
        Text(
            text = if (unloadMin <= 0) {
                stringRes(R.string.dictate__local_unload_pressure)
            } else {
                stringRes(R.string.dictate__local_unload_after, "n" to unloadMin)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Slider(
            value = unloadMin.toFloat(),
            onValueChange = { unloadMin = it.roundToInt() },
            onValueChangeFinished = {
                scope.launch { prefs.dictate.localModelUnloadMinutes.set(unloadMin) }
            },
            valueRange = 0f..30f,
            steps = 5,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

        // One list, in catalog order, with only the live models set apart. Ordered there by what most
        // people will want rather than by architecture, so the small broadly-useful models come first
        // and Whisper sits at the bottom.
        ModelRows(LocalModelCatalog.visibleTopLevel(installed), rowState, rowActions) { openFamily = it }
    }

    openFamily?.let { entry ->
        LocalModelFamilyDialog(
            entry = entry,
            state = rowState,
            actions = rowActions,
            onDismiss = { openFamily = null },
        )
    }


    pendingDelete?.let { spec ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(spec.displayName) },
            text = {
                Text(
                    stringRes(R.string.dictate__local_model_delete_confirm).replace("{model}", spec.displayName),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LocalModelManager.delete(context, spec.id)
                    if (spec.isStreaming) {
                        if (activeStreamingModelId == spec.id) onActiveStreamingModelChange("")
                    } else if (activeModelId == spec.id) {
                        onActiveModelChange("")
                    }
                    refreshTick++
                    pendingDelete = null
                }) { Text(stringRes(R.string.dictate__local_model_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringRes(R.string.action__cancel))
                }
            },
        )
    }
}

/**
 * One group of the first level, with the "Live" heading injected where the streaming rows begin.
 *
 * The heading belongs to the capability rather than to Kroko — a future live model under another name
 * would otherwise be swallowed by a brand's family row — and it lands correctly because the streaming
 * entries are a contiguous tail of the catalog, which a test holds them to.
 */
@Composable
private fun ModelRows(
    entries: List<LocalModelEntry>,
    state: LocalModelState,
    actions: LocalModelActions,
    onOpenFamily: (LocalModelEntry.Family) -> Unit,
) {
    var liveHeaderShown = false
    entries.forEach { entry ->
        if (entry.isStreaming && !liveHeaderShown) {
            liveHeaderShown = true
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
            Text(
                text = stringRes(R.string.dictate__local_models_live_header),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        when (entry) {
            is LocalModelEntry.Single -> ModelRow(entry.spec, state, actions)
            is LocalModelEntry.Family -> FamilyRow(entry, state) { onOpenFamily(entry) }
        }
    }
}

