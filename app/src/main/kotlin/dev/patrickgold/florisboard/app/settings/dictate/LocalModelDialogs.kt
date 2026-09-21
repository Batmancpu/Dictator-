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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.dictate.provider.LocalModelCatalog
import dev.patrickgold.florisboard.dictate.provider.LocalModelEntry
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
            LocalModelCatalog.visibleMembers(entry, state.installed).forEach { spec ->
                ModelRow(spec = spec, state = state, actions = actions)
            }
        }
    }
}
