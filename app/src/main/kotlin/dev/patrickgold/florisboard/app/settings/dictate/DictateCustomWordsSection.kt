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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
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
import org.florisboard.lib.compose.FlorisInfoCard
import org.florisboard.lib.compose.FlorisWarningCard
import org.florisboard.lib.compose.florisDialogScroll
import org.florisboard.lib.compose.stringRes

/**
 * The custom-words list on the formatting screen: the editor, what it costs, and the two ways a list
 * can arrive without being typed one word at a time (issue #389).
 *
 * The editor itself is untouched — pasting a block into it always worked, so the thing that was
 * missing is a file, not a better text field. What is new around it is the part the text field could
 * never show: this list is appended to the transcription prompt on **every** request, the speech model
 * truncates that prompt at a fixed number of tokens, and nothing anywhere says so. A word past the end
 * is not a slow word, it is a word that does nothing while still being sent.
 *
 * So the section states its own cost in the summary line, warns before the line is reached, says
 * plainly what is happening once it is crossed, and points at custom mappings — which are exact, run
 * offline and cost nothing — for the vocabulary that will never fit. See [CustomWordList] for why the
 * budget is what it is.
 */
@Composable
internal fun PreferenceUiScope<FlorisPreferenceModel>.CustomWordsSection(pref: PreferenceData<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val raw by pref.collectAsState()
    val words = remember(raw) { CustomWordList.parse(raw) }
    val tokens = remember(words) { CustomWordList.estimateTokens(words) }

    // An import worked out but not yet written: the user still has to see what it would do.
    var pendingImport by remember { mutableStateOf<CustomWordList.ImportReport?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // A word list can be an address book's worth of names, so reading and planning stay off
            // the main thread — and nothing is written before the user has seen how much it is.
            val report = withContext(Dispatchers.IO) {
                readText(context, uri)?.let { CustomWordList.plan(pref.get(), it) }
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
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                writeText(context, uri, CustomWordList.toFileText(CustomWordList.parse(pref.get())))
            }
            context.showLongToast(
                if (ok) R.string.dictate__file_export_done else R.string.dictate__file_export_failed,
            )
        }
    }

    // Both summaries are resolved here rather than inside the provider lambda, which is an ordinary
    // function and cannot read resources.
    val emptySummary = stringRes(R.string.dictate__custom_words_summary_empty)
    val countSummary = stringRes(
        R.string.dictate__custom_words_summary,
        "count" to words.size,
        "tokens" to tokens,
        "budget" to CustomWordList.TOKEN_BUDGET,
    )
    TextInputPreference(
        pref = pref,
        icon = Icons.Default.MenuBook,
        title = stringRes(R.string.dictate__custom_words_title),
        placeholder = stringRes(R.string.dictate__custom_words_placeholder),
        multiline = true,
        // What the list costs belongs on the row itself: it is the only number that explains both the
        // cap and why a word that is in the list still does not arrive.
        summaryProvider = { value -> if (value.isBlank()) emptySummary else countSummary },
    )

    when {
        tokens > CustomWordList.TOKEN_BUDGET -> FlorisWarningCard(
            modifier = Modifier.padding(all = 8.dp),
            text = stringRes(
                R.string.dictate__custom_words_budget_over,
                "fits" to CustomWordList.countWithinBudget(words),
                "count" to words.size,
            ),
        )
        tokens > CustomWordList.WARN_TOKENS -> FlorisInfoCard(
            modifier = Modifier.padding(all = 8.dp),
            text = stringRes(
                R.string.dictate__custom_words_budget_info,
                "budget" to CustomWordList.TOKEN_BUDGET,
                "tokens" to tokens,
            ),
        )
    }

    Preference(
        icon = Icons.Default.FileDownload,
        modifier = Modifier.settingsSearchAnchor("dictate__custom_words_import"),
        title = stringRes(R.string.dictate__custom_words_import),
        summary = stringRes(R.string.dictate__custom_words_import_summary),
        // "text/*" is the honest filter, but plenty of SAF providers hand a .txt over as an opaque
        // byte stream, and a file the user can see in their file manager but not here is a support
        // ticket rather than a safety feature.
        onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
    )
    if (words.isNotEmpty()) {
        Preference(
            icon = Icons.Default.FileUpload,
            modifier = Modifier.settingsSearchAnchor("dictate__custom_words_export"),
            title = stringRes(R.string.dictate__custom_words_export),
            summary = stringRes(R.string.dictate__custom_words_export_summary),
            onClick = {
                exportLauncher.launch(context.getString(R.string.dictate__custom_words_export_filename))
            },
        )
    }

    pendingImport?.let { report ->
        ImportWordsDialog(
            report = report,
            onDismiss = { pendingImport = null },
            onConfirm = { alsoToDictionary ->
                pendingImport = null
                scope.launch {
                    val addedToDictionary = withContext(Dispatchers.IO) {
                        report.merged?.let { pref.set(it) }
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
