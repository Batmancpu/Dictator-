/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app

import android.content.Context
import android.util.Log
import dev.patrickgold.jetpref.datastore.runtime.AndroidAppDataStorage
import dev.patrickgold.jetpref.datastore.runtime.FileBasedStorage
import dev.patrickgold.jetpref.datastore.runtime.ImportStrategy
import dev.patrickgold.jetpref.datastore.runtime.jetprefDatastoreDir
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * Keeps the settings file when it cannot be read at start, and offers it back (#383).
 *
 * The load's failure used to be logged and nothing else. The app carried on with the defaults, the
 * migrators wrote at once, and that write replaced the unreadable file with the defaults: one failed read
 * meant every key, prompt and setting lost for good, with the setup wizard opening as on a fresh install.
 * Measured on the emulator with the file made unreadable, 33 entries became 24.
 *
 * Now the file is moved aside before anything can write over it. The app starts with the defaults as
 * before, and Settings offers the old file back until it is restored or discarded.
 */
object PreferenceRecovery {
    private const val TAG = "PREFS"
    private const val ASIDE_SUFFIX = ".unreadable"

    private val _pending = MutableStateFlow(false)

    /** Whether an unreadable settings file is waiting to be restored or discarded. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    private fun datastoreFile(context: Context) = File(
        context.jetprefDatastoreDir,
        "${FlorisPreferenceModel.NAME}.${AndroidAppDataStorage.JETPREF_FILE_EXT}",
    )

    private fun asideFile(context: Context) =
        File(datastoreFile(context).path + ASIDE_SUFFIX)

    /**
     * Before the load: a file that exists but cannot be read is moved aside, so the load starts clean
     * instead of from a file the next write would replace.
     */
    fun setAsideIfUnreadable(context: Context) {
        val file = datastoreFile(context)
        if (!file.exists()) return
        val readable = try {
            file.inputStream().use { it.read() }
            true
        } catch (e: IOException) {
            Log.w(TAG, "settings unreadable before load: ${e.javaClass.simpleName}")
            false
        }
        if (!readable) setAside(context)
    }

    /** After a load that failed anyway: whatever is in the file has not been read, so it is kept. */
    fun setAsideAfterFailedLoad(context: Context) {
        if (datastoreFile(context).exists()) setAside(context)
    }

    /** Called once the load is done, so Settings knows whether to offer a restore. */
    fun refresh(context: Context) {
        _pending.value = asideFile(context).exists()
    }

    private fun setAside(context: Context) {
        val aside = asideFile(context)
        // An older file set aside and never restored is the older copy of the same settings; it stays.
        if (aside.exists()) {
            Log.w(TAG, "settings unreadable, an earlier copy is already set aside")
            return
        }
        val moved = datastoreFile(context).renameTo(aside)
        Log.w(TAG, "settings unreadable, set aside: $moved")
    }

    /** Reads the set-aside file back in, replacing the defaults. Fails when it still cannot be read. */
    suspend fun restore(context: Context): Result<Unit> {
        val aside = asideFile(context)
        if (!aside.canRead()) return Result.failure(IOException("still unreadable"))
        return FlorisPreferenceStore.import(ImportStrategy.Erase, FileBasedStorage(aside.path)).onSuccess {
            aside.delete()
            refresh(context)
        }
    }

    /** Gives up on the set-aside file. */
    fun discard(context: Context) {
        asideFile(context).delete()
        refresh(context)
    }
}
