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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.dictate.DictateLanguages
import dev.patrickgold.florisboard.dictate.provider.LocalModelEntry
import dev.patrickgold.florisboard.dictate.provider.LocalModelSpec
import dev.patrickgold.florisboard.lib.compose.FlorisHyperlinkText
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import org.florisboard.lib.compose.florisDialogScroll
import org.florisboard.lib.compose.stringRes

/**
 * The picker's second level: one family's variants, over the provider editor that opened it.
 *
 * A second dialog stacked on the first is how this codebase already goes one level deeper
 * (`RealtimeModelPickerDialog`); there is no precedent anywhere for reaching a sub-screen from a
 * dialog. It holds no state of its own — installs, downloads, the active picks and the delete
 * confirmation all stay with [LocalModelSection], so choosing a variant repaints the family row behind
 * this dialog and deleting one works the same from either level.
 *
 * No confirm button, like its sibling: everything a row does either takes effect at once (install,
 * delete) or is reported upward (select), so there is nothing left to confirm.
 */
@Composable
internal fun LocalModelFamilyDialog(
    entry: LocalModelEntry.Family,
    state: LocalModelState,
    actions: LocalModelActions,
    onDismiss: () -> Unit,
) {
    JetPrefAlertDialog(
        scrollModifier = florisDialogScroll(),
        title = entry.family.displayName,
        dismissLabel = stringRes(R.string.action__back),
        onDismiss = onDismiss,
    ) {
        Column {
            // The live models' one condition — the real-time switch — belongs here, where the choice is
            // actually made, rather than above a list where it was the only thing anyone read first.
            if (entry.isStreaming) {
                Text(
                    text = stringRes(R.string.dictate__local_models_live_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            entry.members.forEach { spec ->
                ModelRow(spec = spec, state = state, actions = actions)
            }
        }
    }
}

/**
 * Everything known about one model, reached from the ⓘ next to it on either level.
 *
 * Assembled from the catalog's fields and a fixed set of labels — there is no per-model prose here and
 * there never should be, which is what keeps twenty-four models from costing twenty-four sentences in
 * twenty locales. This is also the one place the full language list belongs: the row summarises
 * Whisper as "99 languages" precisely so that the names can be read out here instead.
 *
 * A dialog rather than a tooltip. `PlainTooltip` listens on the initial pointer pass and swallows the
 * release, which is the dead-tap of #257/#261 — and this sits on a row that is itself tappable.
 */
@Composable
internal fun LocalModelInfoDialog(spec: LocalModelSpec, onDismiss: () -> Unit) {
    JetPrefAlertDialog(
        scrollModifier = florisDialogScroll(),
        title = spec.displayName,
        confirmLabel = stringRes(R.string.action__ok),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        // Selectable, like the app's other info dialog: a licence or a model name is the kind of thing
        // people copy out.
        SelectionContainer {
            Column {
                InfoLine(
                    label = stringRes(R.string.dictate__local_model_info_languages),
                    value = spec.languages.joinToString(", ") { DictateLanguages.displayNameOf(it) },
                )
                InfoLine(
                    label = stringRes(R.string.dictate__local_model_info_size),
                    value = modelSizeLabel(spec.totalBytes),
                )
                InfoLine(
                    label = stringRes(R.string.dictate__local_model_info_punctuation),
                    value = stringRes(
                        if (spec.punctuates) R.string.dictate__local_model_info_punctuation_yes
                        else R.string.dictate__local_model_info_punctuation_no,
                    ),
                )
                // Only Canary, and only because getting it wrong is silent: told the wrong language it
                // transcribes German as though it were English rather than failing.
                if (!spec.detectsLanguage) {
                    Text(
                        text = stringRes(R.string.dictate__local_model_info_language_fixed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (spec.isStreaming) {
                    Text(
                        text = stringRes(R.string.dictate__local_models_live_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                spec.credit?.let { credit ->
                    Text(
                        text = stringRes(R.string.dictate__local_model_info_credit)
                            .replace("{author}", credit.author)
                            .replace("{license}", credit.license),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    FlorisHyperlinkText(
                        text = credit.url,
                        url = credit.url,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** One label-and-value line of [LocalModelInfoDialog]. */
@Composable
private fun InfoLine(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
