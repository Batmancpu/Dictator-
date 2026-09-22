/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.overlay

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.jetpref.datastore.model.PreferenceData

/**
 * An entry the user may add to the menu the floating button's hold opens (issue #408).
 *
 * These are the things the bubble can do that are not a dictation, so they are kept together in one
 * place: the settings dialog that ticks them and the menu that shows them read the same list, in the
 * same order, with the same label and icon. Declaration order is menu order.
 *
 * A separate preference per entry rather than one stored list: a list would have to be migrated every
 * time an entry is added or renamed, while a boolean that nobody has written simply reads as its
 * default — which for all of these is off ([DictateBubbleController] explains why).
 */
enum class BubbleMenuAction(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    TRANSCRIBE_FILE(R.string.dictate__import_menu, R.drawable.ic_dictate_audio_file),
    HISTORY(R.string.dictate__history_title, R.drawable.ic_dictate_history),
    SETTINGS(R.string.dictate__floating_button_menu_settings_title, R.drawable.ic_dictate_tune);

    /** Whether this entry is in the menu. */
    fun pref(prefs: FlorisPreferenceModel): PreferenceData<Boolean> = when (this) {
        TRANSCRIBE_FILE -> prefs.dictate.floatingButtonMenuTranscribeFile
        HISTORY -> prefs.dictate.floatingButtonMenuHistory
        SETTINGS -> prefs.dictate.floatingButtonMenuSettings
    }
}
