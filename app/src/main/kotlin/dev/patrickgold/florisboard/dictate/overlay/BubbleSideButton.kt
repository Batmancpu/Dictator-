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

import android.content.Context
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.ime.input.InputFeedbackActivationMode
import dev.patrickgold.jetpref.datastore.model.PreferenceData

/**
 * A small button the floating button can carry beside it, ticked in its settings' "Extra buttons" dialog.
 *
 * Cancel and pause stand there while a recording runs, undo right after a dictation, so at most two of
 * them are on screen at a time. Declaration order is the order of the dialog and the order outwards
 * from the bubble: cancel keeps the spot right beside it that it has always had, and pause comes after it,
 * or takes that spot when cancel is not wanted. A long-form dictation puts its Next button in pause's
 * place, ticked or not (#460), so it is not one of these.
 *
 * One preference per button rather than a stored list, for the reason [BubbleMenuAction] gives.
 */
enum class BubbleSideButton(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    CANCEL(R.string.dictate__action_cancel, R.drawable.ic_dictate_overlay_close),
    PAUSE(R.string.dictate__action_pause, R.drawable.ic_dictate_overlay_pause),
    UNDO(R.string.quick_action__undo, R.drawable.ic_dictate_overlay_undo);

    /** Whether this button is wanted beside the bubble. */
    fun pref(prefs: FlorisPreferenceModel): PreferenceData<Boolean> = when (this) {
        CANCEL -> prefs.dictate.floatingButtonCancelEnabled
        PAUSE -> prefs.dictate.floatingButtonPauseEnabled
        UNDO -> prefs.dictate.floatingButtonUndoEnabled
    }

    companion object {
        /**
         * The left edge of a side button [slots] places outward from the bubble's visible shape — slot 0 right
         * beside it, each further one a button and a gap beyond — on the inward side, clamped to the screen.
         *
         * [bubbleLeft] and [bubbleWidth] are the bubble window's; [inset] is how far the visible shape sits
         * inside it, so the gap looks the same whichever design is on.
         */
        fun slotX(
            slot: Int,
            sideIsRight: Boolean,
            bubbleLeft: Int,
            bubbleWidth: Int,
            inset: Int,
            gap: Int,
            size: Int,
            screenWidth: Int,
        ): Int {
            val step = slot * (size + gap)
            val rawX = if (sideIsRight) {
                bubbleLeft + inset - gap - size - step
            } else {
                bubbleLeft + bubbleWidth - inset + gap + step
            }
            return rawX.coerceIn(0, (screenWidth - size).coerceAtLeast(0))
        }
    }
}

/**
 * Whether a tap on the floating button, or on a button beside it, buzzes: whenever a key press on the
 * keyboard would — the same switches in Sounds & Vibration, including the choice to defer to the
 * system's touch feedback setting. It used to have a switch of its own; one rule for every button the
 * user presses in this app is easier to find and cannot disagree with itself.
 */
internal fun floatingButtonTapBuzzes(context: Context, prefs: FlorisPreferenceModel): Boolean {
    val feedback = prefs.inputFeedback
    if (!feedback.hapticEnabled.get() || !feedback.hapticFeatKeyPress.get()) return false
    if (feedback.hapticActivationMode.get() != InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS) return true
    return Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) != 0
}
