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

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.dictate.data.prompts.CustomWordList
import dev.patrickgold.florisboard.glideTypingManager
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.dictionary.PersonalDictionaryWriter
import dev.patrickgold.jetpref.datastore.model.PreferenceData
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceUiScope
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.compose.florisDialogScroll
import org.florisboard.lib.compose.stringRes

/**
 * The custom-words list on the formatting screen: one row, and behind it everything that belongs to
 * the list (issue #389).
 *
 * The editor itself is untouched in spirit — pasting a block into it always worked, so what was
 * missing is a file, not a better text field. Importing and exporting live **inside** the editor
 * dialog rather than as rows beside it: they are two more ways of filling the same field, and a
 * settings screen that lists them separately makes one feature look like three.
 *
 * What the row and the dialog say about cost is the other half. This list is appended to the
 * transcription prompt on **every** request, the speech model truncates that prompt at a fixed number
 * of tokens, and nothing anywhere used to say so. A word past the end is not a slow word, it is a word
 * that does nothing while still being sent. So the summary line states the cost, the dialog restates
 * it live while you paste, and a line of small text below the buttons says what that costs — grey while
 * the list is only filling up, red once it is past the line and words are actually being dropped,
 * pointing at custom mappings for the vocabulary that will never fit. All of it inside the dialog:
 * the screen behind is a list of settings, not the place to explain one. See [CustomWordList] for why
 * the budget is what it is.
 */
@Composable
internal fun PreferenceUiScope<FlorisPreferenceModel>.CustomWordsSection(pref: PreferenceData<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val raw by pref.collectAsState()
    val words = remember(raw) { CustomWordList.parse(raw) }
    val tokens = remember(words) { CustomWordList.estimateTokens(words) }

    // The editor's text while it is open. Saveable because the file picker is another activity, and a
    // low-memory device may rebuild this one underneath it.
    var editorText by rememberSaveable { mutableStateOf<String?>(null) }
    // An import worked out but not yet applied: the user still has to see what it would do.
    var pendingImport by remember { mutableStateOf<CustomWordList.ImportReport?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val existing = editorText ?: pref.get()
        scope.launch {
            // A word list can be an address book's worth of names, so reading and planning stay off
            // the main thread — and nothing is written before the user has seen how much it is.
            val report = withContext(Dispatchers.IO) {
                readText(context, uri)?.let { CustomWordList.plan(existing, it) }
            }
            when {
                report == null -> context.showLongToast(R.string.dictate__file_unreadable)
                report.fromFile.isEmpty() -> context.showLongToast(R.string.dictate__custom_words_import_empty)
                else -> pendingImport = report
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // What is on screen, not what was last saved: the export button sits next to the text field.
        val text = CustomWordList.toFileText(CustomWordList.parse(editorText ?: pref.get()))
        scope.launch {
            val ok = withContext(Dispatchers.IO) { writeText(context, uri, text) }
            context.showLongToast(
                if (ok) R.string.dictate__file_export_done else R.string.dictate__file_export_failed,
            )
        }
    }

    Preference(
        icon = Icons.Default.MenuBook,
        modifier = Modifier.settingsSearchAnchor("dictate__custom_words_title"),
        title = stringRes(R.string.dictate__custom_words_title),
        // What the list costs belongs on the row itself: it is the only number that explains both the
        // cap and why a word that is in the list still does not arrive.
        summary = if (words.isEmpty()) {
            stringRes(R.string.dictate__custom_words_summary_empty)
        } else {
            stringRes(
                R.string.dictate__custom_words_summary,
                "count" to words.size,
                "tokens" to tokens,
                "budget" to CustomWordList.TOKEN_BUDGET,
            )
        },
        onClick = { editorText = raw },
    )

    editorText?.let { text ->
        CustomWordsEditorDialog(
            text = text,
            onTextChange = { editorText = it },
            onImport = {
                // "text/*" is the honest filter, but plenty of SAF providers hand a .txt over as an
                // opaque byte stream, and a file the user can see in their file manager but not here
                // is a support ticket rather than a safety feature.
                importLauncher.launch(arrayOf("text/*", "application/octet-stream"))
            },
            onExport = {
                exportLauncher.launch(context.getString(R.string.dictate__custom_words_export_filename))
            },
            onConfirm = {
                scope.launch { pref.set(text.trim()) }
                editorText = null
            },
            onDismiss = { editorText = null },
        )
    }

    pendingImport?.let { report ->
        ImportWordsDialog(
            report = report,
            onDismiss = { pendingImport = null },
            onConfirm = { alsoToDictionary ->
                pendingImport = null
                // An import is its own confirmed action, so it is written straight through rather than
                // left to the editor's OK — otherwise a later Cancel would silently undo a file the
                // user just approved, and the personal-dictionary half (which is written now) could
                // not be undone with it anyway.
                report.merged?.let { merged ->
                    editorText = merged
                    scope.launch { pref.set(merged) }
                }
                scope.launch {
                    val addedToDictionary = withContext(Dispatchers.IO) {
                        if (alsoToDictionary) addToPersonalDictionary(context, report.fromFile) else 0
                    }
                    if (alsoToDictionary) {
                        context.showLongToast(
                            R.string.dictate__custom_words_import_done_dictionary,
                            "count" to report.accepted.size,
                            "dict" to addedToDictionary,
                        )
                    } else {
                        context.showLongToast(
                            R.string.dictate__custom_words_import_done,
                            "count" to report.accepted.size,
                        )
                    }
                }
            },
        )
    }
}

/**
 * The editor: a text field with room to actually read a list in, what that list costs, and the two
 * buttons that fill it from a file or hand it to somebody else.
 *
 * Wider and taller than a stock alert dialog on purpose. This field can hold fifty proper nouns, and
 * the platform default width turned that into a four-line porthole — the same reason the import and
 * export buttons are in here rather than on the screen behind it: it is all one job.
 */
@Composable
private fun CustomWordsEditorDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val words = remember(text) { CustomWordList.parse(text) }
    val tokens = remember(words) { CustomWordList.estimateTokens(words) }
    val overBudget = tokens > CustomWordList.TOKEN_BUDGET
    val hasNote = tokens > CustomWordList.WARN_TOKENS

    JetPrefAlertDialog(
        modifier = Modifier.fillMaxWidth(0.96f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        scrollModifier = florisDialogScroll(),
        title = stringRes(R.string.dictate__custom_words_title),
        confirmLabel = stringRes(R.string.action__ok),
        dismissLabel = stringRes(R.string.action__cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    // The dialog's own height is bounded, so a taller field does not make the dialog
                    // taller — it pushes whatever follows out of sight. The device test found exactly
                    // that at 360.dp: with a full list the buttons started below the fold, and the
                    // only way back to them was a scroll gesture the text field swallows.
                    //
                    // Hence two sizes. With nothing below the buttons the field can have the room;
                    // once the budget note appears the field yields some of it — the note only shows
                    // when the list is long enough to be scrolling anyway, and at that point what it
                    // says matters more than four more visible lines of the list.
                    .heightIn(
                        min = if (hasNote) 160.dp else 200.dp,
                        max = if (hasNote) 200.dp else 280.dp,
                    ),
                singleLine = false,
                placeholder = { Text(stringRes(R.string.dictate__custom_words_placeholder)) },
            )
            Text(
                modifier = Modifier.padding(top = 6.dp),
                text = if (words.isEmpty()) {
                    stringRes(R.string.dictate__custom_words_summary_empty)
                } else {
                    stringRes(
                        R.string.dictate__custom_words_summary,
                        "count" to words.size,
                        "tokens" to tokens,
                        "budget" to CustomWordList.TOKEN_BUDGET,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                // Red is the whole point of the live count: it goes red exactly when pasting one more
                // word stops adding anything.
                color = if (overBudget) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(12.dp))
            // Above the explanation, not below it: the buttons must keep the same place whatever the
            // list does, and the explanation is the one thing here that can be read on the screen
            // behind this dialog instead.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DialogFileButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.FileDownload,
                    label = stringRes(R.string.action__import),
                    onClick = onImport,
                )
                DialogFileButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.FileUpload,
                    label = stringRes(R.string.action__export),
                    enabled = words.isNotEmpty(),
                    onClick = onExport,
                )
            }
            // Plain small text, not a card. Two states, and the difference between them is whether
            // anything is being lost yet: an approaching list still sends every word, so it is said in
            // the same muted grey as the count; a list past the line is dropping words on every
            // dictation, so it is said in red. Both live here rather than on the screen behind,
            // because this is where the list is edited and where the fix is.
            if (hasNote) {
                Text(
                    modifier = Modifier.padding(top = 12.dp),
                    text = if (overBudget) {
                        stringRes(
                            R.string.dictate__custom_words_budget_over,
                            "fits" to CustomWordList.countWithinBudget(words),
                            "count" to words.size,
                        )
                    } else {
                        stringRes(
                            R.string.dictate__custom_words_budget_info,
                            "budget" to CustomWordList.TOKEN_BUDGET,
                            "tokens" to tokens,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overBudget) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** One of the two file buttons in the editor dialog. */
@Composable
private fun DialogFileButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(modifier = Modifier.padding(start = 8.dp), text = label)
    }
}

/**
 * What the import would do, before it does it.
 *
 * Every line here exists because the import can silently do less than it looks like it did: words that
 * were already known, lines that were not words, and — the one that matters — words there is no room
 * for in the prompt. The last of those carries its reason rather than just its count, because "37 were
 * left out" without the why reads like a bug in the importer.
 */
@Composable
private fun ImportWordsDialog(
    report: CustomWordList.ImportReport,
    onDismiss: () -> Unit,
    onConfirm: (alsoToDictionary: Boolean) -> Unit,
) {
    var alsoToDictionary by remember { mutableStateOf(false) }
    val preview = report.accepted.ifEmpty { report.fromFile }

    JetPrefAlertDialog(
        modifier = Modifier.fillMaxWidth(0.96f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        scrollModifier = florisDialogScroll(),
        title = stringRes(R.string.dictate__custom_words_import_title),
        confirmLabel = stringRes(R.string.action__add),
        dismissLabel = stringRes(R.string.action__cancel),
        onConfirm = { onConfirm(alsoToDictionary) },
        onDismiss = onDismiss,
    ) {
        Column {
            Text(
                text = stringRes(
                    R.string.dictate__custom_words_import_fitting,
                    "count" to report.accepted.size,
                ),
            )
            if (report.alreadyKnown > 0) {
                Text(
                    modifier = Modifier.padding(top = 4.dp),
                    text = stringRes(
                        R.string.dictate__custom_words_import_known,
                        "count" to report.alreadyKnown,
                    ),
                )
            }
            if (report.didNotFit > 0) {
                Text(
                    modifier = Modifier.padding(top = 4.dp),
                    text = stringRes(
                        R.string.dictate__custom_words_import_nofit,
                        "count" to report.didNotFit,
                        "budget" to CustomWordList.TOKEN_BUDGET,
                    ),
                )
            }
            if (report.unusable > 0) {
                Text(
                    modifier = Modifier.padding(top = 4.dp),
                    text = stringRes(
                        R.string.dictate__custom_words_import_unusable,
                        "count" to report.unusable,
                    ),
                )
            }
            if (report.fileTruncated) {
                Text(
                    modifier = Modifier.padding(top = 4.dp),
                    text = stringRes(
                        R.string.dictate__custom_words_import_truncated,
                        "count" to CustomWordList.MAX_IMPORT_ENTRIES,
                    ),
                )
            }
            Text(
                modifier = Modifier.padding(top = 8.dp),
                text = preview.take(8).joinToString(", ") + if (preview.size > 8) ", …" else "",
                fontStyle = FontStyle.Italic,
            )
            // The split between the two lists is deliberate and stays — but an import is exactly the
            // moment somebody expects one list to be the other, so the offer is made here in words
            // rather than left to be discovered as a missing feature.
            CheckboxRow(
                title = stringRes(R.string.dictate__custom_words_import_also_dictionary),
                summary = stringRes(R.string.dictate__custom_words_import_also_dictionary_summary),
                checked = alsoToDictionary,
                onCheckedChange = { alsoToDictionary = it },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** Reads [uri] as UTF-8 text, or null when it cannot be read at all. */
internal fun readText(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        input.readBytes().toString(Charsets.UTF_8)
    }
}.getOrNull()

/** Writes [text] to [uri] as UTF-8, reporting whether it worked. */
internal fun writeText(context: Context, uri: Uri, text: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
    } ?: return false
    true
}.getOrDefault(false)

/**
 * Writes [words] into the internal personal dictionary and returns how many were new.
 *
 * The internal one rather than the system one: it is the dictionary the keyboard's own engine reads,
 * and the system one belongs to the device rather than to this app.
 */
private fun addToPersonalDictionary(context: Context, words: List<String>): Int {
    val manager = DictionaryManager.default()
    manager.loadUserDictionariesIfNecessary()
    val dao = manager.florisUserDictionaryDao() ?: return 0
    val added = PersonalDictionaryWriter.addWords(dao, words)
    // Glide typing indexes the vocabulary once per subtype, so without this a swipe would keep using
    // the list as it was before the import (issue #263).
    if (added.isNotEmpty()) context.glideTypingManager().value.invalidateWordData()
    return added.size
}

/** A checkbox with a title and an explanation under it, for use inside a dialog. */
@Composable
private fun CheckboxRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = checked, onClick = { onCheckedChange(!checked) }),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
