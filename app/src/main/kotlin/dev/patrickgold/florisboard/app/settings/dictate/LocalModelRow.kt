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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.dictate.provider.LocalModelDownloads
import dev.patrickgold.florisboard.dictate.provider.LocalModelEntry
import dev.patrickgold.florisboard.dictate.provider.LocalModelSpec
import org.florisboard.lib.compose.stringRes

/**
 * The rows the on-device picker is built from, shared by its two levels: the list in the provider
 * editor and the dialog a family opens.
 *
 * They are here rather than in [LocalModelSection] because a Kroko variant must behave identically
 * whether it is reached directly or through its family — including which slot it lands in and what the
 * delete dialog then repairs.
 */

/**
 * What a row can do to the model it shows. One bundle instead of four parameters per call site, because
 * the two levels pass exactly the same four and a divergence between them would be a bug.
 */
internal class LocalModelActions(
    val onSelect: (LocalModelSpec) -> Unit,
    val onInstall: (LocalModelSpec) -> Unit,
    val onCancel: (LocalModelSpec) -> Unit,
    val onDelete: (LocalModelSpec) -> Unit,
)

/** Everything the rows read to work out what state a model is in. */
internal class LocalModelState(
    val installed: Set<String>,
    val downloads: Map<String, LocalModelDownloads.State>,
    val activeModelId: String,
    val activeStreamingModelId: String,
) {
    fun isInstalled(spec: LocalModelSpec) = spec.id in installed

    fun isActive(spec: LocalModelSpec) =
        spec.id == if (spec.isStreaming) activeStreamingModelId else activeModelId

    fun percentOf(spec: LocalModelSpec) = downloads[spec.id]?.takeIf { it.error == null }?.percent

    fun hasError(spec: LocalModelSpec) = downloads[spec.id]?.error != null
}

@Composable
internal fun ModelRow(
    spec: LocalModelSpec,
    state: LocalModelState,
    actions: LocalModelActions,
) {
    val isInstalled = state.isInstalled(spec)
    val isActive = state.isActive(spec)
    val downloadPercent = state.percentOf(spec)
    val downloading = downloadPercent != null
    val error = if (state.hasError(spec)) stringRes(R.string.dictate__local_model_download_failed) else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The name is the obvious thing to aim at, so the whole of it selects the model — the radio is a
        // small target to have to hit. It reports the click to the row rather than handling its own, which
        // is what keeps this one control to a screen reader instead of two.
        Row(
            modifier = Modifier
                .weight(1f)
                .selectable(
                    selected = isActive,
                    enabled = isInstalled && !downloading,
                    role = Role.RadioButton,
                    onClick = { actions.onSelect(spec) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = isActive,
                enabled = isInstalled && !downloading,
                onClick = null,
                // Handing the click to the row costs the radio the touch-target padding Material puts
                // around a clickable one, which is what set the spacing to the text and the height of the
                // row. Asked for explicitly, both stay exactly as they were.
                modifier = Modifier.minimumInteractiveComponentSize(),
            )
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(text = spec.displayName, style = MaterialTheme.typography.titleSmall)
                // What the model is stays on the line; only the second half changes with its state. It
                // used to be replaced by the state, so an installed model stopped saying what it covers
                // — exactly when several are installed and one has to be chosen between them. The size
                // is what the second half says until it is installed, because that is the number the
                // decision turns on; afterwards it is no longer news and lives in the details instead.
                val status = when {
                    downloading -> stringRes(R.string.dictate__local_model_downloading)
                        .replace("{percent}", downloadPercent.toString())
                    isActive -> stringRes(R.string.dictate__local_model_status_active)
                    isInstalled -> stringRes(R.string.dictate__local_model_status_installed)
                    else -> modelSizeLabel(spec.totalBytes)
                }
                Text(
                    text = error ?: "${modelLanguagesLabel(spec)} · $status",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { (downloadPercent ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
        // Icon-only actions (keep the row compact); labels live on as the accessibility descriptions.
        // Deliberately outside the selectable wrapper above — inside it, a screen reader would announce
        // a radio button with a button inside it.
        when {
            downloading -> IconButton(onClick = { actions.onCancel(spec) }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringRes(R.string.dictate__local_model_action_cancel),
                )
            }
            isInstalled -> IconButton(onClick = { actions.onDelete(spec) }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringRes(R.string.dictate__local_model_action_delete),
                )
            }
            else -> IconButton(onClick = { actions.onInstall(spec) }) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringRes(R.string.dictate__local_model_action_install),
                )
            }
        }
    }
}

/**
 * The stand-in row for a whole family: Whisper's six size and language variants, Kroko's ten languages.
 * Tapping it opens the family's own dialog.
 *
 * Not a radio and not installable — a family is a place to go, not one of the options. Giving it a
 * radio would announce it as a choice it cannot be. The leading spacer is layout only, never clickable,
 * so family and model rows share a text baseline without the row growing a second control.
 */
@Composable
internal fun FamilyRow(
    entry: LocalModelEntry.Family,
    state: LocalModelState,
    onOpen: () -> Unit,
) {
    val members = entry.members
    val downloadingMember = members.firstOrNull { state.percentOf(it) != null }
    val failedMember = members.firstOrNull { state.hasError(it) }
    val activeMember = members.firstOrNull { state.isActive(it) }
    val installedCount = members.count { state.isInstalled(it) }

    val downloadFailed = stringRes(R.string.dictate__local_model_download_failed)
    val downloadingLabel = stringRes(R.string.dictate__local_model_downloading)
    val activeLabel = stringRes(R.string.dictate__local_model_status_active)
    val installedLabel = stringRes(R.string.dictate__local_model_family_installed)
        .replace("{count}", installedCount.toString())
        .replace("{total}", members.size.toString())
    // Every language any member covers, so Kroko reads "10 languages" rather than whichever one is first.
    val coverage = languagesLabel(members.flatMap { it.languages }.distinct())
    val sizes = sizeRangeLabel(members)

    // Strict order, and the first two matter most: a download running inside a collapsed family must
    // never be invisible from out here, and neither must the model that is actually transcribing.
    val subtitle = when {
        downloadingMember != null -> "${downloadingMember.displayName} · " +
            downloadingLabel.replace("{percent}", state.percentOf(downloadingMember).toString())
        failedMember != null -> "${failedMember.displayName} · $downloadFailed"
        activeMember != null -> "${activeMember.displayName} · $activeLabel"
        installedCount > 0 -> "$installedLabel · $sizes"
        else -> "$coverage · $sizes"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = stringRes(R.string.dictate__local_model_family_open),
                onClick = onOpen,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.minimumInteractiveComponentSize()) {}
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = entry.family.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (failedMember != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (downloadingMember != null) {
                LinearProgressIndicator(
                    progress = { (state.percentOf(downloadingMember) ?: 0) / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        // Decorative: the row already carries the meaning and its own click label, and a description
        // here would make a screen reader read the row twice.
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
