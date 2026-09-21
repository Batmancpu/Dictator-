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
import androidx.compose.foundation.layout.size
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
 * Gap between the radio and the text, kept at what the 48 dp box used to give it: Material's radio is
 * 24 dp, so centring it in 48 left exactly 12 dp on each side.
 */
private val RADIO_TEXT_GAP = 12.dp

/**
 * The leading slot: the radio's own 24 dp plus that gap, shared with a family's spacer so both kinds of
 * row start their text on the same line.
 *
 * It is not Material's 48 dp touch target, and does not need to be — neither the radio nor the spacer is
 * what gets tapped, the row is. That box was margin, and in a dialog this narrow it was being paid for
 * out of the model names. What it cost on the *left* is reclaimed; what it gave between the radio and
 * the text is not.
 */
private val LEADING_SLOT = 24.dp + RADIO_TEXT_GAP

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
                // Left at its own size with the gap spelled out, instead of centred in a 48 dp box: the
                // gap to the text is the half that was doing something, the leading half was not.
                modifier = Modifier.padding(end = RADIO_TEXT_GAP),
            )
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(text = spec.displayName, style = MaterialTheme.typography.titleSmall)
                // What the model is stays on the line; only the second half changes with its state. It
                // used to be replaced by the state, so an installed model stopped saying what it covers
                // — exactly when several are installed and one has to be chosen between them. The size
                // is what the second half says until it is installed, because that is the number the
                // decision turns on; afterwards it is no longer news.
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
        //
        // One action button, not two. A details button next to it left the text about 130 dp inside a
        // dialog roughly 280 dp wide, which broke "SenseVoice Small" across two lines and its languages
        // across four. What that dialog held now lives on the rows above and on the attributions screen.
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
        Column(modifier = Modifier.size(LEADING_SLOT)) {}
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
