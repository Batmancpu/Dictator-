/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.devemperor.dictate.wear.ime

import android.content.Context
import android.view.inputmethod.EditorInfo

/** Settings of the watch keyboard itself, kept on the watch (nothing the phone needs to know). */
object WearKeyboardPrefs {
    private const val PREFS = "dictate_wear_keyboard"
    private const val KEY_AUTO_SEND = "auto_send"

    /**
     * Take the field's action — send, search, go or done — as soon as a dictation lands in it (#294).
     * Off by default: a misheard sentence would then be out before anyone read it, so the keyboard asks
     * with a ✓ first.
     *
     * Done counts too. On Galaxy watches WhatsApp and Keep hand their input to Samsung's RemoteInput
     * screen, whose field finishes with DONE and passes the text on only then (found by m5991 on a Watch8
     * in #351) — so auto-send that skipped DONE would never have sent a WhatsApp message.
     */
    fun autoSend(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_SEND, false)

    fun setAutoSend(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SEND, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The action a dictation can finish with in this field (#294), or null for a field that has none to
 * offer — a note, a multi-line text, or an app that keeps sending to its own button.
 *
 * On a watch the keyboard covers the whole screen, so the field is invisible while it is up, and some
 * fields only exist while it is: Samsung Browser's search lives in a field that is thrown away the moment
 * the keyboard closes, so a dictation that just closed the keyboard never reached the search. Such fields
 * get their action from the keyboard instead. [EditorInfo.IME_FLAG_NO_ENTER_ACTION] is the app saying the
 * keyboard should not trigger it — sending is often that kind of action — so it is respected.
 */
fun EditorInfo.dictationAction(): Int? {
    if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
    return (imeOptions and EditorInfo.IME_MASK_ACTION).takeIf { it in FINISHING_ACTIONS }
}

private val FINISHING_ACTIONS = setOf(
    EditorInfo.IME_ACTION_SEND,
    EditorInfo.IME_ACTION_SEARCH,
    EditorInfo.IME_ACTION_GO,
    EditorInfo.IME_ACTION_DONE,
)
