/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.symbols

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The user's own symbol pages (issue #342), kept in a file of their own next to the preferences.
 *
 * Not a preference: jetpref's string escaping is lossy around a backslash (#367), and `\` is one of the
 * default keys. The settings screen writes here and the keyboard rebuilds itself from [symbols]; both
 * run in the app's one process.
 */
object CustomSymbolsStore {
    private const val FILE_NAME = "custom_symbols.json"

    private val lock = Any()
    @Volatile private var loaded = false

    val symbols: StateFlow<CustomSymbols>
        field = MutableStateFlow(CustomSymbols())

    /** The pages as stored. The first call reads the file, so it belongs off the main thread. */
    fun current(context: Context): CustomSymbols {
        ensureLoaded(context)
        return symbols.value
    }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            symbols.value = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()
                ?.let(CustomSymbolsJson::decode)
                ?: CustomSymbols()
            loaded = true
        }
    }

    suspend fun set(context: Context, updated: CustomSymbols) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            loaded = true
            symbols.value = updated
            val file = file(context)
            if (updated.changed.isEmpty()) {
                file.delete()
            } else {
                // Written aside and moved over, so a crash mid-write leaves the old pages rather than half a file.
                val temp = File(file.parentFile, "$FILE_NAME.tmp")
                temp.writeText(CustomSymbolsJson.encode(updated, full = false))
                if (!temp.renameTo(file)) {
                    file.delete()
                    temp.renameTo(file)
                }
            }
        }
    }

    /** What a backup carries: the stored changes, or null when every key is its default. */
    fun backupText(context: Context): String? =
        current(context).takeIf { it.changed.isNotEmpty() }?.let { CustomSymbolsJson.encode(it, full = false) }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
