/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/**
 * What a screen reader needs from dictation beyond labelled buttons (issue #159).
 *
 * A blind user starts a dictation by double-tapping the mic and then has nothing to look at. The state
 * changes that follow — transcribing, rewording, an error — are spoken from here, driven by the same
 * transitions as [DictateHaptics].
 *
 * The start of a recording is deliberately **not** spoken. The screen reader talks through the speaker
 * while the microphone has just opened, so its own "recording" would be captured and turn up at the head
 * of the transcript. The start is felt instead: [DictateHaptics] buzzes for it whenever a screen reader
 * is running, whatever its own switch says.
 */
object DictateAccessibility {
    /**
     * A screen reader that explores by touch is running — TalkBack, or a third-party one such as Jieshuo.
     * Touch exploration rather than "any accessibility service", because Dictate's own floating button is
     * an accessibility service too, and so are password managers and automation apps.
     */
    fun isScreenReaderOn(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.isEnabled && manager.isTouchExplorationEnabled
    }

    /**
     * Has the screen reader speak [text].
     *
     * An announcement event rather than a live region: the status it reports is drawn by Smartbar nodes
     * that are thrown away and rebuilt on every state change, and the classic layout draws it somewhere
     * else entirely, while the transitions all pass through one place in [DictateController]. AOSP's
     * LatinIME speaks its keyboard changes the same way. `TYPE_ANNOUNCEMENT` is deprecated on API 36 in
     * favour of those semantic routes, but it is still delivered.
     */
    fun announce(context: Context, text: CharSequence) {
        if (text.isBlank() || !isScreenReaderOn(context)) return
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return
        @Suppress("DEPRECATION")
        val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
        } else {
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
        }
        event.packageName = context.packageName
        event.className = DictateAccessibility::class.java.name
        event.text.add(text)
        // Throws if the service went away between the check above and here.
        runCatching { manager.sendAccessibilityEvent(event) }
    }
}
